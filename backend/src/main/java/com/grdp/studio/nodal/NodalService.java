package com.grdp.studio.nodal;

import com.grdp.studio.coefficient.CoefficientStorage;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import com.grdp.studio.wellbore.pressure.method.*;
import com.grdp.studio.wellbore.pressure.service.PvtPropertyProvider;
import com.grdp.studio.wellbore.pressure.service.PressureStorageService;
import com.grdp.studio.wellbore.risk.service.*;
import com.grdp.studio.wellbore.risk.dto.HydrateRequest;
import com.grdp.studio.wellbore.erosion.model.ErosionCalculator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.grdp.studio.nodal.NodalModels.*;

@Service
public class NodalService {
    public static final String VERSION = "nodal-binomial-v5";
    private final CoefficientStorage coefficients;
    private final PvtPropertyProvider pvt;
    private final LiquidLoadingCalculator liquid;
    private final HydrateCalculator hydrate;
    private final ObjectMapper json;
    private final PressureStorageService pressureStorage;
    public NodalService(CoefficientStorage coefficients, PvtPropertyProvider pvt, LiquidLoadingCalculator liquid,
            HydrateCalculator hydrate, ObjectMapper json, PressureStorageService pressureStorage) {
        this.coefficients = coefficients; this.pvt = pvt; this.liquid = liquid; this.hydrate = hydrate; this.json = json;
        this.pressureStorage = pressureStorage;
    }
    public List<CoefficientStorage.Detail> sources(long project, long reservoir, String well, String operation) {
        return coefficients.list(project, reservoir, well).stream().filter(s -> "二项式".equals(s.method()))
                .map(s -> coefficients.detail(s.id(), project, reservoir, well)).filter(d -> d.operation().equals(operation)).toList();
    }
    public Result calculate(Input input, String token, String cookie, String environment) {
        validate(input);
        var source = coefficients.detail(input.coefficientId, input.projectId, input.gasReservoirId, input.wellName);
        if (!"二项式".equals(source.method()) || !input.operationMode.equals(source.operation())) fail("请选择当前方向的二项式系数方案");
        boolean corrected = input.coefficientSet.equals("corrected");
        Double selectedA = corrected ? source.parameters().correctedA() : source.parameters().a();
        Double selectedB = corrected ? source.parameters().correctedB() : source.parameters().b();
        Double a = selectedA, b = selectedB;
        if (input.coefficientA != null || input.coefficientB != null) {
            if (input.coefficientA == null || input.coefficientB == null) fail("请同时填写产能系数A和B");
            a = input.coefficientA; b = input.coefficientB;
        }
        if (a == null || b == null) fail("系数方案缺少所选A、B");
        boolean customCoefficients = selectedA == null || selectedB == null
                || Math.abs(a - selectedA) > 1e-12 * Math.max(1, Math.abs(selectedA))
                || Math.abs(b - selectedB) > 1e-12 * Math.max(1, Math.abs(selectedB));
        var curve = new BinomialCurve(a, b, source.pressureMethod(), source.pvtSnapshot());
        boolean injection = input.operationMode.equals("injection");
        Object pressureSourceSnapshot = null;
        if (input.pressureSourceId != null) {
            var record = pressureStorage.detail(input.pressureSourceId, input.projectId, input.gasReservoirId, input.wellName).record();
            if (!input.operationMode.equals(record.getOperationMode() == null ? "production" : record.getOperationMode())) fail("压力来源注采方向不匹配");
            pressureSourceSnapshot = Map.of("id", input.pressureSourceId, "name", record.getPressureName(), "input", json.readValue(record.getInputJson(), Map.class));
        }
        for (double pr : input.reservoirPressures) {
            if (injection ? input.maximumPressure <= pr : input.minimumPressure >= pr) fail("井底压力搜索范围与地层压力不匹配");
            curve.potential(pr); curve.potential(injection ? input.maximumPressure : input.minimumPressure);
        }
        var base = copy(input.wellbore);
        base.projectId = input.projectId; base.gasReservoirId = input.gasReservoirId; base.wellName = input.wellName;
        base.operationMode = input.operationMode; base.boundaryPosition = "wellhead"; base.pvtSnapshot = null;
        // Load/validate selected PVT exactly once. Preserve the established local DAK/LGE pressure algorithms.
        pvt.open(base, token, cookie, environment);
        if (input.constraints.hydrate) {
            // Check non-hydrocarbon consistency against the same explicitly selected PVT as pressure conversion.
            Object gasInput = base.pvtSnapshot == null ? null : base.pvtSnapshot.get("gasInput");
            if (gasInput == null) fail("所选PVT缺少气体组成依据");
            Map<?, ?> gas = json.convertValue(gasInput, Map.class);
            for (var entry : Map.of("H2S", "hydrogenSulfide", "CO2", "carbonDioxide", "N2", "nitrogen").entrySet()) {
                Object value = gas.get(entry.getValue());
                if (!(value instanceof Number)) fail("PVT缺少非烃组分，无法核对水合物输入");
                if (Math.abs(((Number)value).doubleValue() - input.constraints.composition.getOrDefault(entry.getKey(), 0d)) > 1e-6)
                    fail(entry.getKey() + "须与所选PVT组分一致");
            }
        }
        double maxQ = input.reservoirPressures.stream().mapToDouble(pr -> curve.rate(pr,
                injection ? input.maximumPressure : input.minimumPressure, injection)).max().orElseThrow();
        base.qGas = maxQ;
        PressureCalculator.validate(base, List.of(0d, base.depth), List.of(base.tWh, base.tWh + base.tGrad * base.depth / 100));
        var boundaryCache = new HashMap<Double, PressureCalculator.MethodResult>();
        var boundaryFailures = new HashMap<Double, String>();
        java.util.function.DoubleFunction<PressureCalculator.MethodResult> boundary = q -> {
            if (boundaryFailures.containsKey(q)) throw new BusinessException(400, boundaryFailures.get(q));
            if (!boundaryCache.containsKey(q)) {
                try { boundaryCache.put(q, profile(base, q, null)); }
                catch (BusinessException e) { boundaryFailures.put(q, e.getMessage()); throw e; }
            }
            return boundaryCache.get(q);
        };
        var wellCurve = new ArrayList<Point>();
        // The flowing correlations are undefined at exactly zero gas rate. Evaluate a
        // documented near-zero limit so the visible TPR/OPR has a meaningful low-rate end.
        double nearZeroQ = Math.min(maxQ, Math.max(1e-6, maxQ / input.samples / 1_000_000));
        try { wellCurve.add(new Point(nearZeroQ, boundary.apply(nearZeroQ).profile().getLast().pressure(),
                "近零气量极限点；不是零流量静压")); }
        catch (BusinessException e) { wellCurve.add(new Point(nearZeroQ, null, e.getMessage())); }
        for (int i = 1; i <= input.samples; i++) {
            double q = maxQ * i / input.samples;
            try { wellCurve.add(new Point(q, boundary.apply(q).profile().getLast().pressure(), null)); }
            catch (BusinessException e) { wellCurve.add(new Point(q, null, e.getMessage())); }
        }
        var scenarios = new ArrayList<Scenario>();
        // Evaluate intermediate reservoir pressures before interpolating a feasible envelope.
        // Display only the user-selected curves; persist all supporting slices in the snapshot.
        var pressures = new TreeSet<Double>(input.reservoirPressures);
        var selectedPressures = new ArrayList<>(pressures);
        for (int i = 1; i < selectedPressures.size(); i++) {
            double lo = selectedPressures.get(i - 1), hi = selectedPressures.get(i);
            for (int j = 1; j < 4; j++) pressures.add(lo + (hi - lo) * j / 4);
        }
        var regionScenarios = new ArrayList<Scenario>();
        for (double pr : pressures) {
            var cache = new HashMap<Double, Candidate>();
            regionScenarios.add(NodalSolver.solve(pr, injection ? input.maximumPressure : input.minimumPressure, input.samples,
                    pwf -> cache.computeIfAbsent(pwf, key -> {
                        double q = curve.rate(pr, pwf, injection);
                        try {
                            double boundaryP = boundary.apply(q).profile().getLast().pressure();
                            double margin = injection ? boundaryP - pwf : pwf - boundaryP;
                            var checks = new ArrayList<Check>();
                            boolean flowConstraint = input.constraints.liquidLoading || input.constraints.erosion;
                            boolean insideBoundary = margin >= -NodalSolver.PRESSURE_TOLERANCE;
                            // Liquid-loading and erosion boundaries are fields in q-Pwf space.
                            // Evaluate them on every calculable candidate instead of only after
                            // the tubing boundary has already passed, otherwise the displayed
                            // critical curve is truncated by the very boundary it should cross.
                            if (flowConstraint || insideBoundary && input.constraints.hydrate) {
                                try {
                                    var actual = profile(base, q, pwf);
                                    double wh = actual.profile().getFirst().pressure();
                                    double reverseMargin = injection ? base.boundaryPressure - wh : wh - base.boundaryPressure;
                                    if (insideBoundary && reverseMargin < -0.001)
                                        return new Candidate(q, pwf, null, List.of(), "正反压力折算边界不一致，请减小井深步长");
                                    checks.addAll(checkProfile(input, base, q, actual, insideBoundary));
                                } catch (BusinessException e) {
                                    if (insideBoundary) return new Candidate(q, pwf, null, List.of(), e.getMessage());
                                }
                            }
                            return new Candidate(q, pwf, margin, checks, null);
                        } catch (BusinessException e) { return new Candidate(q, pwf, null, List.of(), e.getMessage()); }
                    }), false));
        }
        for (double pr : input.reservoirPressures)
            scenarios.add(regionScenarios.stream().filter(s -> s.reservoirPressure() == pr).findFirst().orElseThrow());
        var constraintCurves = buildConstraintCurves(regionScenarios, input.constraints, injection);
        var feasibleRegions = buildFeasibleRegions(regionScenarios);
        // Root searches evaluate additional low-rate and boundary points. Include them
        // in the visible tubing curve, otherwise a coarse uniform q grid hides the
        // liquid-loaded low-rate branch and can make a computed intersection look detached.
        var displayedBoundary = new TreeMap<Double, Point>();
        wellCurve.forEach(p -> displayedBoundary.put(p.rate(), p));
        boundaryCache.forEach((q, profile) -> displayedBoundary.put(q, new Point(q, profile.profile().getLast().pressure(), null)));
        boundaryFailures.forEach((q, reason) -> displayedBoundary.put(q, new Point(q, null, reason)));
        wellCurve = new ArrayList<>(displayedBoundary.values());
        var states = new ArrayList<Check>();
        states.add(new Check("liquidLoading", injection ? "NOT_APPLICABLE" : input.constraints.liquidLoading ? "ENABLED" : "DISABLED", null, "采气：全剖面Turner+20%携液阈值"));
        states.add(new Check("hydrate", input.constraints.hydrate ? "ENABLED" : "DISABLED", null, "按实际工况剖面检查温度裕量"));
        states.add(new Check("erosion", input.constraints.erosion ? "ENABLED" : "DISABLED", null, "P110现有公式；按气相表观流速反算临界气量，超标定范围属于模型外推"));
        states.add(new Check("sanding", "RESERVED", null, "出砂暂未接入计算，选择仅保存偏好"));
        var coefficientSnapshot = new LinkedHashMap<String, Object>(json.convertValue(source, Map.class));
        coefficientSnapshot.put("effectiveA", a); coefficientSnapshot.put("effectiveB", b);
        coefficientSnapshot.put("coefficientSet", input.coefficientSet);
        var warnings = new ArrayList<String>();
        warnings.add("标况20℃、101.325kPa；请确认系数方案气量采用相同标况。压力为绝压。");
        warnings.add("井筒沿用等内径、单井斜及线性温度模型；未进行瞬态稳定性判断。");
        warnings.add("阴影为四个地层压力之间、经井筒边界与所选约束共同校核的能力包络；有限采样不能排除极窄不可行区间。");
        warnings.add("井筒曲线左端为近零气量极限，不代表零流量静压。");
        warnings.add(input.constraints.erosion ? "冲蚀按指定P110现有公式和气相表观流速口径接入；速度定义沿用当前模型假设。GAS注气仍为单相压降，持液/含砂仅用于冲蚀评价。" : "未启用冲蚀约束，阴影不包含冲蚀限制。");
        if (customCoefficients) warnings.add("本次采用手动编辑的A、B；请确认其压力口径、PVT及气量标况与所选方案一致。");
        if (source.pvtId() != null && !Objects.equals(source.pvtId(), base.pvtId))
            warnings.add("井筒PVT与二项式系数方案的PVT来源不同；能力交点可能受物性口径差异影响。");
        return new Result(VERSION, coefficientSnapshot, base, pressureSourceSnapshot, wellCurve, scenarios, states, List.copyOf(warnings),
                regionScenarios, constraintCurves, feasibleRegions);
    }
    private List<Check> checkProfile(Input input, PressureCalculateRequest base, double q,
            PressureCalculator.MethodResult actual, boolean includeHydrate) {
        double liquidMargin = Double.POSITIVE_INFINITY, hydrateMargin = Double.POSITIVE_INFINITY, erosionMargin = Double.POSITIVE_INFINITY;
        var erosion = new ErosionCalculator();
        var domainNotes = new LinkedHashSet<String>();
        for (var point : actual.profile()) {
            if (input.constraints.erosion) {
                var c = input.constraints;
                var props = PressureCorrelations.originalProperties(point.pressure(), point.temperature() + 273.15, base.gammaG, base.rhoL, base.muL);
                try {
                    double coefficient = erosion.calculateCriticalCoefficient(erosion.calculateLiquidHoldupFactor(c.liquidHoldupPercent), erosion.calculateSandFactor(c.sandContentPercent));
                    double density = erosion.calculateMixtureDensity(c.liquidHoldupPercent, c.sandContentPercent, props.gasDensity(), c.erosionLiquidDensity, c.sandDensity);
                    double velocity = erosion.calculateCriticalVelocity(coefficient, density);
                    double critical = erosion.calculateCriticalGasRate(velocity, base.idTubing, props.gasVolumeFactor());
                    erosionMargin = Math.min(erosionMargin, critical - q);
                    domainNotes.addAll(erosion.validateModelDomain(c.liquidHoldupPercent, c.sandContentPercent,
                            erosion.calculateActualVelocity(q, base.idTubing, props.gasVolumeFactor())));
                } catch (IllegalArgumentException | ArithmeticException e) {
                    throw new BusinessException(400, "冲蚀模型不可计算：" + e.getMessage());
                }
            }
            if (input.constraints.liquidLoading) {
                var props = PressureCorrelations.originalProperties(point.pressure(), point.temperature() + 273.15, base.gammaG, base.rhoL, base.muL);
                double critical = liquid.criticalRate(input.constraints.surfaceTension, props.liquidDensity(), props.gasDensity(), base.idTubing, props.gasVolumeFactor());
                liquidMargin = Math.min(liquidMargin, q - critical);
            }
            if (includeHydrate && input.constraints.hydrate) {
                var result = hydrate.calculate(new HydrateRequest(input.projectId, input.gasReservoirId, input.wellName, base.pvtId,
                        null, null, input.constraints.composition, point.pressure(), point.temperature(), input.constraints.fugacityScale));
                if (!result.droppedComponents().isEmpty()) throw new BusinessException(400, "水合物模型存在不支持的组分，无法完成全组成校核");
                double margin = result.temperatureMarginC() - input.constraints.hydrateMargin;
                if (!Double.isFinite(margin)) throw new BusinessException(400, "水合物计算未返回有效温度裕量");
                hydrateMargin = Math.min(hydrateMargin, margin);
            }
        }
        var checks = new ArrayList<Check>();
        if (input.constraints.erosion) checks.add(new Check("erosion", erosionMargin >= 0 ? "PASS" : "FAIL", erosionMargin,
                "P110全剖面临界气量最小裕量，10⁴m³/d；" + String.join("；", domainNotes)));
        if (input.constraints.liquidLoading) checks.add(new Check("liquidLoading", liquidMargin >= 0 ? "PASS" : "FAIL", liquidMargin, "全剖面最小气量裕量，10⁴m³/d；与压力折算同口径DAK物性"));
        if (includeHydrate && input.constraints.hydrate) checks.add(new Check("hydrate", hydrateMargin >= 0 ? "PASS" : "FAIL", hydrateMargin, "全剖面最小温度裕量，℃（扣除设置裕量）"));
        return checks;
    }

    private List<ConstraintCurve> buildConstraintCurves(List<Scenario> scenarios, Constraints constraints, boolean injection) {
        var curves = new ArrayList<ConstraintCurve>();
        if (constraints.liquidLoading && !injection)
            addConstraintCurve(curves, scenarios, "liquidLoading", "临界携液流量");
        if (constraints.erosion)
            addConstraintCurve(curves, scenarios, "erosion", "临界冲蚀流量");
        return List.copyOf(curves);
    }

    private void addConstraintCurve(List<ConstraintCurve> output, List<Scenario> scenarios, String code, String name) {
        var segments = new ArrayList<List<Point>>();
        var current = new ArrayList<Point>();
        for (Scenario scenario : scenarios.stream().sorted(Comparator.comparingDouble(Scenario::reservoirPressure)).toList()) {
            var crossings = constraintCrossings(scenario.candidates(), code);
            if (crossings.size() == 1) current.add(crossings.getFirst());
            else if (!current.isEmpty()) { segments.add(List.copyOf(current)); current.clear(); }
        }
        if (!current.isEmpty()) segments.add(List.copyOf(current));
        var drawable = segments.stream().filter(segment -> segment.size() >= 2).toList();
        if (!drawable.isEmpty()) output.add(new ConstraintCurve(code, name, drawable));
    }

    private List<Point> constraintCrossings(List<Candidate> candidates, String code) {
        var points = new ArrayList<Point>();
        for (int i = 1; i < candidates.size(); i++) {
            Candidate a = candidates.get(i - 1), b = candidates.get(i);
            Check x = a.checks().stream().filter(c -> code.equals(c.code())).findFirst().orElse(null);
            Check y = b.checks().stream().filter(c -> code.equals(c.code())).findFirst().orElse(null);
            if (x == null || y == null || x.margin() == null || y.margin() == null
                    || !Set.of("PASS", "FAIL").contains(x.status()) || !Set.of("PASS", "FAIL").contains(y.status())
                    || x.margin() * y.margin() > 0 || x.margin().equals(y.margin())) continue;
            double t = x.margin() / (x.margin() - y.margin());
            Point p = new Point(a.rate() + (b.rate() - a.rate()) * t,
                    a.pressure() + (b.pressure() - a.pressure()) * t, null);
            if (points.stream().noneMatch(existing -> Math.abs(existing.rate() - p.rate()) < 1e-6)) points.add(p);
        }
        return points;
    }

    private List<List<Point>> buildFeasibleRegions(List<Scenario> scenarios) {
        var slices = scenarios.stream().sorted(Comparator.comparingDouble(Scenario::reservoirPressure)).toList();
        var polygons = new ArrayList<List<Point>>();
        for (int i = 1; i < slices.size(); i++) {
            Scenario a = slices.get(i - 1), b = slices.get(i);
            if (!regionValid(a) || !regionValid(b)) continue;
            for (Interval left : a.intervals()) {
                var matches = b.intervals().stream().filter(right -> overlaps(left, right)).toList();
                if (matches.size() != 1) continue;
                Interval right = matches.getFirst();
                long reverseMatches = a.intervals().stream().filter(other -> overlaps(other, right)).count();
                if (reverseMatches != 1) continue;
                var polygon = new ArrayList<Point>(regionEdge(a, left));
                var opposite = new ArrayList<Point>(regionEdge(b, right));
                Collections.reverse(opposite); polygon.addAll(opposite);
                if (polygon.size() >= 4) polygons.add(List.copyOf(polygon));
            }
        }
        return List.copyOf(polygons);
    }

    private boolean regionValid(Scenario scenario) {
        return !"INCOMPLETE_CONSTRAINTS".equals(scenario.status()) && !scenario.intervals().isEmpty();
    }
    private boolean overlaps(Interval a, Interval b) {
        return Math.max(a.from().rate(), b.from().rate()) < Math.min(a.to().rate(), b.to().rate());
    }
    private List<Point> regionEdge(Scenario scenario, Interval interval) {
        var edge = new ArrayList<Point>(); edge.add(interval.from());
        scenario.formation().stream().filter(p -> p.rate() > interval.from().rate() && p.rate() < interval.to().rate()).forEach(edge::add);
        edge.add(interval.to()); return edge;
    }
    private PressureCalculator.MethodResult profile(PressureCalculateRequest base, double q, Double bottom) {
        var r = copy(base); r.qGas = q;
        if (bottom != null) {
            r.boundaryPosition = "bottomhole"; r.boundaryPressure = bottom;
            r.tWh = base.tWh + base.tGrad * base.depth / 100;
        }
        var result = PressureCalculator.calculateUnrounded(r).methods().get(r.models.getFirst());
        if (!result.allSegmentsConverged()) throw new BusinessException(400, "压力剖面未收敛，请减小井深步长");
        return result;
    }
    private PressureCalculateRequest copy(PressureCalculateRequest r) { return json.readValue(json.writeValueAsString(r), PressureCalculateRequest.class); }
    private void validate(Input r) {
        if (r == null || r.wellbore == null || r.constraints == null) fail("缺少节点分析输入");
        if (!Set.of("production", "injection").contains(Objects.toString(r.operationMode, ""))) fail("注采方向无效");
        if (!Set.of("original", "corrected").contains(Objects.toString(r.coefficientSet, ""))) fail("请选择原始或修正系数");
        if (r.reservoirPressures == null || r.reservoirPressures.size() != 4
                || new HashSet<>(r.reservoirPressures).size() != 4) fail("节点分析需要4个互不重复的地层压力场景");
        if (r.samples < 20 || r.samples > 240) fail("采样数应为20至240");
        for (Double p : r.reservoirPressures) if (p == null || !Double.isFinite(p) || p <= 0) fail("地层压力必须为正绝压");
        if (!Double.isFinite(r.minimumPressure) || r.minimumPressure <= 0 || !Double.isFinite(r.maximumPressure) || r.maximumPressure <= 0) fail("井底压力范围必须为正绝压");
        if (r.wellbore.models == null || r.wellbore.models.size() != 1) fail("每次分析请选择一个井筒方法");
        if (!Double.isFinite(r.wellbore.step) || r.wellbore.step <= 0 || !Double.isFinite(r.wellbore.depth)
                || r.wellbore.depth <= 0 || r.wellbore.depth / r.wellbore.step > 250) fail("井深和步长无效，单剖面最多250段");
        if (r.operationMode.equals("injection") && r.constraints.liquidLoading) fail("注气不适用采气携液判据");
        if (r.constraints.liquidLoading && (!Double.isFinite(r.constraints.surfaceTension) || r.constraints.surfaceTension <= 0)) fail("表面张力必须大于零");
        if (r.constraints.erosion) {
            var c = r.constraints;
            if (c.liquidHoldupPercent == null || c.sandContentPercent == null || c.sandDensity == null || c.erosionLiquidDensity == null)
                fail("请读取冲蚀记录或补齐持液率、含砂率、砂粒密度及液相密度");
            if (!Double.isFinite(c.liquidHoldupPercent) || c.liquidHoldupPercent < 0 || !Double.isFinite(c.sandContentPercent)
                    || c.sandContentPercent <= 0 || c.liquidHoldupPercent + c.sandContentPercent >= 100
                    || !Double.isFinite(c.sandDensity) || c.sandDensity <= 0 || !Double.isFinite(c.erosionLiquidDensity) || c.erosionLiquidDensity <= 0)
                fail("冲蚀参数无效：持液率非负、含砂率及密度为正，体积分数合计须小于100%");
        }
        if (r.constraints.hydrate) {
            if (!Double.isFinite(r.constraints.hydrateMargin) || r.constraints.hydrateMargin < 0
                    || !Double.isFinite(r.constraints.fugacityScale) || r.constraints.fugacityScale <= 0) fail("水合物裕量和逸度系数无效");
            if (r.constraints.composition == null || r.constraints.composition.isEmpty()) fail("请输入完整气体组成");
            double sum = 0;
            for (var e : r.constraints.composition.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || !Double.isFinite(e.getValue()) || e.getValue() < 0) fail("气体组成无效");
                if (!Set.of("C1", "C2", "C3", "IC4", "NC4", "O2", "N2", "CO2", "H2S").contains(e.getKey())) fail("水合物仅接受C1、C2、C3、IC4、NC4、O2、N2、CO2、H2S组分；不支持的组分不能忽略");
                sum += e.getValue();
            }
            if (Math.abs(sum - 100) > 0.01) fail("气体组成摩尔百分数必须合计100%");
        }
    }
    private static void fail(String message) { throw new BusinessException(400, message); }
}

