package com.grdp.studio.productivitycomparison;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.common.BinomialOpenFlow;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;
import static com.grdp.studio.productivitycomparison.ComparisonModels.*;
import static com.grdp.studio.productivitycomparison.ComparisonRepository.*;

@Service
public class ComparisonService {
    private final ComparisonRepository repository;
    private final ComparisonPseudoPressure pseudoPressure;
    public ComparisonService(ComparisonRepository repository, ComparisonPseudoPressure pseudoPressure) {
        this.repository = repository; this.pseudoPressure = pseudoPressure;
    }
    private void validatePressureMethod(String method) {
        if (!Set.of("pressure", "pressure-squared", "pseudo-pressure").contains(method == null ? "" : method))
            throw new BusinessException(400, "请选择有效的压力处理方法");
    }
    public List<RecordSummary> list(long projectId, long reservoirId, String wellName, List<String> methods,
            String pressureMethod, LocalDate startDate, LocalDate endDate) {
        return list(projectId, reservoirId, wellName, methods, pressureMethod, startDate, endDate, List.of("production"));
    }
    public List<RecordSummary> list(long projectId, long reservoirId, String wellName, List<String> methods,
            String pressureMethod, LocalDate startDate, LocalDate endDate, List<String> operationTypes) {
        validatePressureMethod(pressureMethod);
        repository.requireWell(projectId, reservoirId, wellName);
        if (startDate != null && endDate != null && startDate.isAfter(endDate))
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        List<Saved> records = load(projectId, reservoirId, wellName, methods, pressureMethod, operationTypes);
        return records.stream().filter(r -> (startDate == null || r.date() != null && !r.date().isBefore(startDate))
                && (endDate == null || r.date() != null && !r.date().isAfter(endDate)))
                .sorted(Comparator.comparing(Saved::date, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Saved::method).thenComparingLong(Saved::id).thenComparing(Saved::operationType))
                .map(r -> summary(r, pressureMethod)).toList();
    }
    private List<Saved> load(long projectId, long reservoirId, String wellName, List<String> methods, String pressureMethod,
            List<String> operationTypes) {
        if (methods == null || methods.size() > 6) throw new BusinessException(400, "产能对比方法数量不正确");
        if (operationTypes == null || operationTypes.isEmpty() || operationTypes.size() > 2
                || operationTypes.stream().anyMatch(type -> !Set.of("production", "injection").contains(type == null ? "" : type)))
            throw new BusinessException(400, "请选择有效的注采类型");
        List<Saved> records = new ArrayList<>();
        for (String method : new LinkedHashSet<>(methods))
            for (String operationType : new LinkedHashSet<>(operationTypes))
                records.addAll(repository.load(projectId, reservoirId, wellName, method, pressureMethod, operationType));
        return records;
    }
    private RecordSummary summary(Saved r, String pressureMethod) {
        String reason = "";
        try {
            validateCoefficients(r.a(), r.b());
            if (pressureMethod.equals("pseudo-pressure")) ComparisonPseudoPressure.validate(r.pvt());
        } catch (BusinessException error) { reason = error.getMessage(); }
        return new RecordSummary(key(r.method(), r.id(), r.operationType()), r.method(), METHODS.get(r.method()), r.id(), r.name(),
                r.date(), "计算日期", reason.isEmpty(), reason, r.operationType());
    }
    private static String key(String method, long id, String operationType) { return method + ":" + operationType + ":" + id; }
    private void validateCalculationPressure(Double pressure, String label, String method) {
        if (pressure == null || !Double.isFinite(pressure) || pressure <= .1)
            throw new BusinessException(400, "计算无阻流量的" + label + "必须大于0.1 MPa");
        if (method.equals("pseudo-pressure") && pressure > 200)
            throw new BusinessException(400, "拟压力接口的计算压力不能超过200 MPa");
    }
    public CalculateResponse calculate(CalculateRequest request, Map<String, String> headers) {
        return calculate(request, headers, false);
    }
    public DirectionComparisonResponse compareDirections(CalculateRequest request, Map<String, String> headers) {
        var calculation = calculate(request, headers, true);
        var groups = new TreeMap<LocalDate, List<Result>>();
        for (var row : calculation.results())
            groups.computeIfAbsent(row.record().date(), date -> new ArrayList<>()).add(row);
        return new DirectionComparisonResponse(calculation, groups.entrySet().stream()
                .map(entry -> new DirectionGroup(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparing((Result row) -> row.record().operationType())
                                .thenComparingLong(row -> row.record().recordId())).toList())).toList());
    }
    private CalculateResponse calculate(CalculateRequest request, Map<String, String> headers, boolean compareDirections) {
        validatePressureMethod(request.pressureMethod());
        if (compareDirections && request.period() != null)
            throw new BusinessException(400, "注采对比按计算日期分组，不接收多周期设置");
        repository.requireWell(request.projectId(), request.gasReservoirId(), request.wellName());
        if (request.records() == null || request.records().isEmpty() || request.records().size() > 100)
            throw new BusinessException(400, "请选择1至100条记录");
        var selected = new LinkedHashSet<>(request.records());
        if (selected.size() != request.records().size()) throw new BusinessException(400, "参与对比的记录不能重复");
        var methods = selected.stream().map(Selection::method).distinct().toList();
        var directions = selected.stream().map(Selection::operationType).distinct().toList();
        if (compareDirections && methods.size() != 1)
            throw new BusinessException(400, "注采对比请选择同一方法下的记录");
        if (request.period() != null && (methods.size() != 1 || directions.size() != 1))
            throw new BusinessException(400, "多周期对比请选择同一方法和同一注采类型的记录");
        if (directions.contains("production")) validateCalculationPressure(request.formationPressure(), "地层压力", request.pressureMethod());
        if (directions.contains("injection")) validateCalculationPressure(request.injectionPressure(), "注气压力", request.pressureMethod());
        Map<String, Saved> saved = new HashMap<>();
        for (Saved r : load(request.projectId(), request.gasReservoirId(), request.wellName(), methods, request.pressureMethod(), directions))
            saved.put(key(r.method(), r.id(), r.operationType()), r);
        var periods = ComparisonPeriods.create(ComparisonPeriods.resolve(request.period(),
                saved.values().stream().map(Saved::date).toList()));
        // Validate all selections before creating any external toolbox. Client never supplies A/B or PVT.
        List<Saved> ordered = new ArrayList<>();
        for (Selection s : selected) {
            Saved r = saved.get(key(s.method(), s.recordId(), s.operationType()));
            if (r == null) throw new BusinessException(400, "所选记录已删除、不属于当前井或未保存该注采方向和压力形式的二项式结果，请刷新记录");
            RecordSummary info = summary(r, request.pressureMethod());
            if (!info.available()) throw new BusinessException(400, r.name() + "：" + info.unavailableReason());
            if (compareDirections && r.date() == null)
                throw new BusinessException(400, r.name() + "：缺少计算日期，请先重新计算并保存该记录");
            if (request.period() != null && periods.stream().noneMatch(period -> ComparisonPeriods.contains(period, r.date())))
                throw new BusinessException(400, r.name() + "：计算日期缺失或不在所选周期范围内，请刷新记录");
            ordered.add(r);
        }
        record PotentialKey(PvtSnapshot pvt, double pressure) {}
        Map<PotentialKey, ComparisonPseudoPressure.Pair> cache = new HashMap<>();
        List<Result> results = new ArrayList<>();
        for (Saved r : ordered) {
            boolean injection = r.operationType().equals("injection");
            double highPressure = injection ? request.injectionPressure() : request.formationPressure();
            double pr = injection ? .1 : highPressure;
            double pwf = injection ? highPressure : .1;
            Double mp = null, mwf = null;
            double difference = highPressure - .1;
            if (request.pressureMethod().equals("pressure-squared")) difference = (highPressure - .1) * (highPressure + .1);
            if (request.pressureMethod().equals("pseudo-pressure")) {
                var pair = cache.computeIfAbsent(new PotentialKey(r.pvt(), highPressure),
                        p -> pseudoPressure.calculate(request.projectId(), p.pvt(), p.pressure(), headers));
                mp = injection ? pair.flowing() : pair.reservoir();
                mwf = injection ? pair.reservoir() : pair.flowing();
                difference = injection ? mwf - mp : mp - mwf;
            }
            double q = solve(r.a(), r.b(), difference);
            results.add(new Result(summary(r, request.pressureMethod()), r.a(), r.b(), q, mp, mwf, pr, pwf));
        }
        return new CalculateResponse(request.pressureMethod(), directions.contains("production") ? request.formationPressure() : null,
                .1, List.copyOf(results), directions.contains("injection") ? request.injectionPressure() : null,
                ComparisonPeriods.group(periods, results));
    }
    static void validateCoefficients(Double a, Double b) {
        BinomialOpenFlow.validateCoefficients(a, b);
    }
    static double solve(double a, double b, double difference) {
        return BinomialOpenFlow.solve(a, b, difference);
    }
}
