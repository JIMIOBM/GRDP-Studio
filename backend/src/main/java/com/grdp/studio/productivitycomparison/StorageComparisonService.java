package com.grdp.studio.productivitycomparison;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import static com.grdp.studio.productivitycomparison.ComparisonModels.*;
import static com.grdp.studio.productivitycomparison.StorageComparisonModels.*;

@Service
public class StorageComparisonService {
    private final StorageCatalogService catalog;
    private final ComparisonService comparison;
    public StorageComparisonService(StorageCatalogService catalog, ComparisonService comparison) {
        this.catalog = catalog;
        this.comparison = comparison;
    }

    public Response calculate(Request request, Map<String, String> headers) {
        validate(request);
        // 继续一次返回全部周期，切换三维图的展示周期不需要重复计算或查询数据库。
        var response = calculateMethods(new MultiMethodRequest(request.projectId(), request.gasReservoirId(),
                request.storageId(), request.wellIds(), List.of(request.method()), request.operationType(),
                request.pressureMethod(), request.formationPressure(), request.injectionPressure(), request.period()), headers);
        return new Response(request.method(), response.operationType(), response.pressureMethod(),
                response.formationPressure(), response.injectionPressure(), response.wells(), response.periods().stream()
                .map(p -> {
                    var means = p.wells().stream().map(w -> {
                        var m = w.methods().getFirst();
                        return new Mean(w.wellId(), w.wellName(), m.averageOpenFlow(), m.recordCount());
                    }).toList();
                    // 使用当前周期的计数，不使用跨周期 WellSummary，也不对均值再次求平均。
                    int validWells = (int) means.stream().filter(m -> m.averageOpenFlow() != null && m.recordCount() > 0).count();
                    int records = means.stream().mapToInt(Mean::recordCount).sum();
                    return new Period(p.index(), p.startDate(), p.endDate(), means, validWells, records);
                }).toList());
    }

    public MultiMethodResponse calculateMethods(MultiMethodRequest request, Map<String, String> headers) {
        if (request == null || request.methods() == null || request.methods().isEmpty() || request.methods().size() > 6
                || request.methods().stream().anyMatch(m -> m == null || !ComparisonRepository.METHODS.containsKey(m)))
            throw new BusinessException(400, "请选择1至6种有效的对比方法");
        if (new HashSet<>(request.methods()).size() != request.methods().size())
            throw new BusinessException(400, "对比方法不能重复");
        validate(new Request(request.projectId(), request.gasReservoirId(), request.storageId(), request.wellIds(),
                request.methods().getFirst(), request.operationType(), request.pressureMethod(),
                request.formationPressure(), request.injectionPressure(), request.period()));
        return aggregateMethods(request, prepare(request, List.of(request.operationType()), headers));
    }

    public DirectionResponse calculateDirections(DirectionRequest request, Map<String, String> headers) {
        if (request == null) throw new BusinessException(400, "请设置注采对比参数");
        // 两个方向使用各自的计算压力；必须分别校验，不能只校验当前单选的方向。
        var operations = List.of("production", "injection");
        for (var operation : operations) validate(new Request(request.projectId(), request.gasReservoirId(),
                request.storageId(), request.wellIds(), request.method(), operation, request.pressureMethod(),
                request.formationPressure(), request.injectionPressure(), request.period()));
        var data = prepare(new MultiMethodRequest(request.projectId(), request.gasReservoirId(), request.storageId(),
                request.wellIds(), List.of(request.method()), "production", request.pressureMethod(),
                request.formationPressure(), request.injectionPressure(), request.period()), operations, headers);
        // 不按方向分别推导日期：所有井、两个方向必须使用同一套周期边界。
        var grouped = data.periods().stream().map(period -> {
            var means = data.wells().stream().map(well -> {
                    var rows = data.results().get(well.id()).stream()
                            .filter(r -> ComparisonPeriods.contains(period, r.record().date())).toList();
                    return new WellDirections(well.id(), well.wellName(), operations.stream().map(operation -> {
                        var values = rows.stream().filter(r -> operation.equals(r.record().operationType()))
                                .map(Result::openFlowCapacity).toList();
                        return new DirectionMean(operation, mean(values), values.size());
                    }).toList());
                }).toList();
            int validWells = (int) means.stream().filter(well -> well.directions().stream()
                    .anyMatch(m -> m.averageOpenFlow() != null && m.recordCount() > 0)).count();
            int records = means.stream().flatMap(well -> well.directions().stream()).mapToInt(DirectionMean::recordCount).sum();
            return new DirectionPeriod(period.index(), period.startDate(), period.endDate(), means, validWells, records);
        }).toList();
        return new DirectionResponse(request.method(), request.pressureMethod(), request.formationPressure(),
                request.injectionPressure(), data.summaries(), grouped);
    }

    private record PreparedComparison(List<StorageCatalogService.Well> wells, List<PeriodResult> periods,
            Map<Long, List<Result>> results, List<WellSummary> summaries) {}

    /** 三种库级对比共享成员校验、有效记录筛选、共同周期和逐条计算，不修改原始记录。 */
    private PreparedComparison prepare(MultiMethodRequest request, List<String> operations, Map<String, String> headers) {
        // PROJECT_ID + GAS_RESERVOIR_ID 是原系统项目范围；独立储气库使用 storageId。
        var members = catalog.wells(request.storageId(), request.projectId(), request.gasReservoirId());
        var ids = new HashSet<>(request.wellIds());
        if (ids.size() != request.wellIds().size()) throw new BusinessException(400, "参与对比的井不能重复");
        var wells = members.stream().filter(w -> ids.contains(w.id())).toList();
        if (wells.size() != ids.size()) throw new BusinessException(400, "所选井已移出储气库或不属于当前库，请刷新后重新选择");

        record Plan(StorageCatalogService.Well well, List<RecordSummary> records, int excluded) {}
        var plans = new ArrayList<Plan>();
        int total = 0;
        for (var well : wells) {
            // 不信任前端提供井名、系数或流量；复用单井接口对项目、方法和来源的校验。
            var saved = comparison.list(request.projectId(), request.gasReservoirId(), well.wellName(),
                    request.methods(), request.pressureMethod(), null, null, operations);
            var inRange = saved.stream().filter(r -> r.date() == null
                    || (request.period().startDate() == null || !r.date().isBefore(request.period().startDate()))
                    && (request.period().endDate() == null || !r.date().isAfter(request.period().endDate()))).toList();
            var valid = inRange.stream().filter(r -> r.available() && r.date() != null).toList();
            total += valid.size();
            if (total > 10000) throw new BusinessException(400, "一次最多计算10000条有效记录，请缩小日期范围或减少选井");
            plans.add(new Plan(well, valid, inRange.size() - valid.size()));
        }
        // 所有井使用同一套周期边界；省略日期时，按全部选井的有效记录统一推导。
        var settings = ComparisonPeriods.resolve(request.period(), plans.stream()
                .flatMap(p -> p.records().stream()).map(RecordSummary::date).toList());
        var periods = ComparisonPeriods.create(settings);
        var results = new LinkedHashMap<Long, List<Result>>();
        var summaries = new ArrayList<WellSummary>();
        for (var plan : plans) {
            var calculated = new ArrayList<Result>();
            // 单井接口每批最多100条。分批只为复用计算，不分批取平均，避免不等批量产生偏差。
            for (int offset = 0; offset < plan.records().size(); offset += 100) {
                var selections = plan.records().subList(offset, Math.min(offset + 100, plan.records().size()))
                        .stream().map(r -> new Selection(r.method(), r.recordId(), r.operationType())).toList();
                var batch = comparison.calculate(new CalculateRequest(request.projectId(), request.gasReservoirId(),
                        plan.well().wellName(), request.pressureMethod(), request.formationPressure(), selections,
                        // 多方法/注采批次只复用逐条求值；周期由库级统一确定。
                        request.injectionPressure(), request.methods().size() == 1 && operations.size() == 1 ? settings : null), headers);
                calculated.addAll(batch.results());
            }
            results.put(plan.well().id(), calculated);
            summaries.add(new WellSummary(plan.well().id(), plan.well().wellName(), calculated.size(), plan.excluded()));
        }
        return new PreparedComparison(wells, periods, results, List.copyOf(summaries));
    }

    private MultiMethodResponse aggregateMethods(MultiMethodRequest request, PreparedComparison data) {
        var wells = data.wells();
        var periods = data.periods();
        var results = data.results();
        // 所有方法共用跨井推导的周期，均值严格按“周期 + 井 + 方法”分组。
        var grouped = periods.stream().map(period -> {
            var means = wells.stream().map(well -> {
                    var rows = results.get(well.id()).stream().filter(r -> ComparisonPeriods.contains(period, r.record().date())).toList();
                    return new WellMethods(well.id(), well.wellName(), request.methods().stream().map(method -> {
                        var values = rows.stream().filter(r -> method.equals(r.record().method())).map(Result::openFlowCapacity).toList();
                        return new MethodMean(method, mean(values), values.size());
                    }).toList());
                }).toList();
            int validWells = (int) means.stream().filter(well -> well.methods().stream()
                    .anyMatch(m -> m.averageOpenFlow() != null && m.recordCount() > 0)).count();
            int records = means.stream().flatMap(well -> well.methods().stream()).mapToInt(MethodMean::recordCount).sum();
            return new MethodPeriod(period.index(), period.startDate(), period.endDate(), means, validWells, records);
        }).toList();
        return new MultiMethodResponse(List.copyOf(request.methods()), request.operationType(), request.pressureMethod(),
                request.operationType().equals("production") ? request.formationPressure() : null,
                request.operationType().equals("injection") ? request.injectionPressure() : null,
                data.summaries(), grouped);
    }

    // 先逐记录计算无阻流量，再算术平均；空组返回null而非0；不提前四舍五入。
    static Double mean(List<Double> values) {
        if (values.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (double value : values) {
            if (!Double.isFinite(value) || value < 0) throw new BusinessException(400, "计算结果无效，无法计算平均无阻流量");
            sum = sum.add(BigDecimal.valueOf(value));
        }
        return sum.divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL128).doubleValue();
    }

    private static void validate(Request r) {
        if (r == null || r.projectId() <= 0 || r.gasReservoirId() <= 0 || r.storageId() <= 0)
            throw new BusinessException(400, "请先选择具体储气库");
        if (r.wellIds() == null || r.wellIds().isEmpty() || r.wellIds().size() > 2000
                || r.wellIds().stream().anyMatch(id -> id == null || id <= 0))
            throw new BusinessException(400, "请选择1至2000口井");
        if (r.method() == null || !ComparisonRepository.METHODS.containsKey(r.method()))
            throw new BusinessException(400, "请选择有效的对比方法");
        if (r.operationType() == null || !Set.of("production", "injection").contains(r.operationType()))
            throw new BusinessException(400, "请选择有效的注采类型");
        if (r.pressureMethod() == null || !Set.of("pressure", "pressure-squared", "pseudo-pressure").contains(r.pressureMethod()))
            throw new BusinessException(400, "请选择有效的计算方法");
        Double pressure = r.operationType().equals("injection") ? r.injectionPressure() : r.formationPressure();
        if (pressure == null || !Double.isFinite(pressure) || pressure <= .1)
            throw new BusinessException(400, "计算压力必须大于0.1 MPa");
        if (r.pressureMethod().equals("pseudo-pressure") && pressure > 200)
            throw new BusinessException(400, "拟压力接口的计算压力不能超过200 MPa");
        if (r.period() == null) throw new BusinessException(400, "请设置对比周期");
        // 先校验月数与已填日期，避免无效请求先查询每口井。
        ComparisonPeriods.create(new PeriodSettings(
                r.period().startDate() != null ? r.period().startDate()
                        : r.period().endDate() != null ? r.period().endDate() : java.time.LocalDate.of(2000, 1, 1),
                r.period().endDate() != null ? r.period().endDate()
                        : r.period().startDate() != null ? r.period().startDate() : java.time.LocalDate.of(2000, 1, 1),
                r.period().months()));
    }
}
