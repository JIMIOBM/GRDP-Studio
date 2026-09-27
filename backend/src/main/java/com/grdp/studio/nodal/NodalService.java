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
    public static final String VERSION = "nodal-binomial-v4";
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
        Double a = corrected ? source.parameters().correctedA() : source.parameters().a();
        Double b = corrected ? source.parameters().correctedB() : source.parameters().b();
        if (input.coefficientA != null || input.coefficientB != null) {
            if (input.coefficientA == null || input.coefficientB == null) fail("请同时填写产能系数A和B");
            a = input.coefficientA; b = input.coefficientB;
        }
        if (a == null || b == null) fail("系数方案缺少所选A、B");
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
        wellCurve.add(new Point(0, null, "流动压力折算不评价零气量"));
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
                            if (margin >= -NodalSolver.PRESSURE_TOLERANCE && (input.constraints.liquidLoading || input.constraints.hydrate || input.constraints.erosion)) {
                                var actual = profile(base, q, pwf);
                                double wh = actual.profile().getFirst().pressure();
                                double reverseMargin = injection ? base.boundaryPressure - wh : wh - base.boundaryPressure;
                                if (reverseMargin < -0.001) return new Candidate(q, pwf, null, List.of(), "正反压力折算边界不一致，请减小井深步长");
                                checks.addAll(checkProfile(input, base, q, actual));
                            }
                            return new Candidate(q, pwf, margin, checks, null);
                        } catch (BusinessException e) { return new Candidate(q, pwf, null, List.of(), e.getMessage()); }
                    }), false));
        }
        for (double pr : input.reservoirPressures)
            scenarios.add(regionScenarios.stream().filter(s -> s.reservoirPressure() == pr).findFirst().orElseThrow());
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
        return new Result(VERSION, coefficientSnapshot, base, pressureSourceSnapshot, wellCurve, scenarios, states, List.of(
                "标况20℃、101.325kPa；请确认系数方案气量采用相同标况。压力为绝压。",
                "井筒沿用等内径、单井斜及线性温度模型；未进行瞬态稳定性判断。",
                "阴影按已校核的中间地层压力场景插值，仅针对所选有效约束；有限采样不能排除极窄不可行区间。", 
                input.constraints.erosion ? "冲蚀按指定P110现有公式和气相表观流速口径接入；速度定义沿用当前模型假设。GAS注气仍为单相压降，持液/含砂仅用于冲蚀评价。" : "未启用冲蚀约束，阴影不包含冲蚀限制。"), regionScenarios);
    }
    private List<Check> checkProfile(Input input, PressureCalculateRequest base, double q, PressureCalculator.MethodResult actual) {
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
            if (input.constraints.hydrate) {
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
        if (input.constraints.hydrate) checks.add(new Check("hydrate", hydrateMargin >= 0 ? "PASS" : "FAIL", hydrateMargin, "全剖面最小温度裕量，℃（扣除设置裕量）"));
        return checks;
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
        if (r.reservoirPressures == null || r.reservoirPressures.isEmpty() || r.reservoirPressures.size() > 10) fail("地层压力场景需要1至10个");
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

