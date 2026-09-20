package com.grdp.studio.storagematerialbalance;

import com.grdp.studio.common.BusinessException;
import java.time.LocalDate;
import java.util.*;

/** 库级输入汇总，不重算或覆盖单井的储量回归。所有参数均已转换为界面工程单位。 */
public final class StorageMaterialBalanceCalculator {
    private StorageMaterialBalanceCalculator() {}

    public record Sample(LocalDate date, Double pressure, Double gas, Double water, boolean deleted) {}
    public record Source(long wellId, String wellName, Long resultId, Double gasVolume,
                         Double rSquared, String problem, String warning, List<Sample> samples) {}
    public record Well(long wellId, String wellName, Long resultId, Double gasVolume,
                       Double rSquared, boolean included, Double weight, String reason,
                       String warning, int discardedRows, List<Sample> samples) {}
    public record Row(LocalDate date, double pressure, double gas, double water) {}
    public record SkippedDate(LocalDate date, List<String> missingWells) {}
    public record Result(List<Well> wells, List<Row> rows, List<SkippedDate> skippedDates,
                         Double sourceGasVolume, int includedWellCount, String message) {}

    public static Result calculate(List<Source> sources) {
        List<Well> wells = new ArrayList<>();
        Map<Long, NavigableMap<LocalDate, Sample>> series = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        for (Source source : sources) {
            if (!seen.add(source.wellId())) throw new BusinessException(400, "库内井不能重复计入");
            String problem = source.problem();
            if (problem == null && !positive(source.gasVolume())) problem = "实测静压动态储量无效，整井排除";
            NavigableMap<LocalDate, Sample> dates = new TreeMap<>();
            Set<LocalDate> repeated = new HashSet<>();
            Set<LocalDate> encountered = new HashSet<>();
            int discarded = 0;
            for (Sample sample : source.samples()) {
                if (sample.deleted()) { discarded++; continue; }
                // 同日重复不任取一条或相加，连同该日其他记录全部排除。
                if (sample.date() != null && !encountered.add(sample.date())) repeated.add(sample.date());
                if (sample.date() == null || !positive(sample.pressure())
                        || !nonNegative(sample.gas()) || !nonNegative(sample.water())) {
                    discarded++;
                    repeated.add(sample.date());
                    continue;
                }
                if (dates.putIfAbsent(sample.date(), sample) != null) discarded++;
            }
            for (LocalDate date : repeated) {
                if (date != null && dates.remove(date) != null) discarded++;
            }
            if (problem == null && dates.isEmpty()) problem = "没有有效的实测静压输入行，整井排除";
            Sample previous = null;
            for (Sample sample : dates.values()) {
                if (previous != null && (sample.gas() < previous.gas() || sample.water() < previous.water())) {
                    if (problem == null) problem = "累产气量或累产水量随日期下降，请先核对单井数据；整井排除";
                    break;
                }
                previous = sample;
            }
            boolean included = problem == null;
            if (included) series.put(source.wellId(), dates);
            wells.add(new Well(source.wellId(), source.wellName(), source.resultId(), finite(source.gasVolume()),
                    finite(source.rSquared()), included, null, included ? "参与汇总" : problem,
                    source.warning(), discarded, List.copyOf(dates.values())));
        }
        List<Well> included = wells.stream().filter(Well::included).toList();
        if (included.isEmpty()) return new Result(List.copyOf(wells), List.of(), List.of(), null, 0,
                "库内没有可用的实测静压结果。缺失井不以计算静压替代，也不计入产气、产水总量。");
        double total = included.stream().mapToDouble(Well::gasVolume).sum();
        requireFinite(total);
        wells = wells.stream().map(w -> new Well(w.wellId(), w.wellName(), w.resultId(), w.gasVolume(),
                w.rSquared(), w.included(), w.included() ? w.gasVolume() / total : null,
                w.reason(), w.warning(), w.discardedRows(), w.samples())).toList();
        SortedSet<LocalDate> allDates = new TreeSet<>();
        series.values().forEach(dates -> allDates.addAll(dates.keySet()));
        List<Row> rows = new ArrayList<>();
        List<SkippedDate> skipped = new ArrayList<>();
        for (LocalDate date : allDates) {
            List<String> missing = included.stream().filter(w -> !series.get(w.wellId()).containsKey(date))
                    .map(Well::wellName).toList();
            if (!missing.isEmpty()) { skipped.add(new SkippedDate(date, missing)); continue; }
            double pressure = 0, gas = 0, water = 0;
            for (Well well : included) {
                Sample sample = series.get(well.wellId()).get(date);
                pressure += (well.gasVolume() / total) * sample.pressure();
                gas += sample.gas();
                water += sample.water();
            }
            requireFinite(pressure); requireFinite(gas); requireFinite(water);
            rows.add(new Row(date, pressure, gas, water));
        }
        return new Result(wells, List.copyOf(rows), List.copyOf(skipped), total, included.size(),
                rows.isEmpty() ? "参与井没有共同的有效日期，未生成汇总曲线；不插值、不补零。"
                        : "按共同有效日期汇总；压力按动态储量加权，累产气量和累产水量求和。来源储量之和不是库级回归结果。");
    }

    private static Double finite(Double value) { return value != null && Double.isFinite(value) ? value : null; }
    private static boolean positive(Double value) { return finite(value) != null && value > 0; }
    private static boolean nonNegative(Double value) { return finite(value) != null && value >= 0; }
    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) throw new BusinessException(400, "库级汇总结果溢出，请核对来源数据量级");
    }
}
