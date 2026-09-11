package com.grdp.studio.productivitycomparison;

import com.grdp.studio.common.BusinessException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static com.grdp.studio.productivitycomparison.ComparisonModels.*;

/** Calendar-month buckets anchored to the original start date, not fixed 30-day intervals. */
final class ComparisonPeriods {
    private ComparisonPeriods() {}

    /** Resolve omitted limits from every matching saved record, not only the checked records. */
    static PeriodSettings resolve(PeriodSettings settings, List<LocalDate> dates) {
        if (settings == null || settings.startDate() != null && settings.endDate() != null) return settings;
        var matching = dates.stream().filter(java.util.Objects::nonNull)
                .filter(date -> (settings.startDate() == null || !date.isBefore(settings.startDate()))
                        && (settings.endDate() == null || !date.isAfter(settings.endDate())))
                .sorted().toList();
        if (matching.isEmpty()) throw new BusinessException(400, "所选方法和日期范围内没有带计算日期的记录");
        LocalDate start = settings.startDate() != null ? settings.startDate() : matching.getFirst();
        LocalDate end = settings.endDate() != null ? settings.endDate()
                : matching.getLast().with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        return new PeriodSettings(start, end, settings.months());
    }

    static List<PeriodResult> create(PeriodSettings settings) {
        if (settings == null) return List.of();
        if (settings.startDate() == null || settings.endDate() == null)
            throw new BusinessException(400, "多周期对比请选择开始日期和结束日期");
        if (settings.startDate().isAfter(settings.endDate()))
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        if (settings.months() == null || settings.months().compareTo(java.math.BigDecimal.ONE) < 0
                || settings.months().compareTo(java.math.BigDecimal.valueOf(1200)) > 0
                || settings.months().stripTrailingZeros().scale() > 0)
            throw new BusinessException(400, "周期时长必须为1至1200的整数（月）");
        if (settings.startDate().getYear() < 1 || settings.endDate().getYear() > 9999)
            throw new BusinessException(400, "请选择有效的计算日期范围");
        List<PeriodResult> periods = new ArrayList<>();
        LocalDate start = settings.startDate();
        for (int index = 1; !start.isAfter(settings.endDate()); index++) {
            if (index > 240) throw new BusinessException(400, "一次最多对比240个周期，请缩短日期范围或增加周期时长");
            // Always offset the original anchor: Jan 31 -> Feb 28 -> Mar 31.
            LocalDate next = settings.startDate().plusMonths((long) index * settings.months().intValueExact());
            LocalDate end = next.minusDays(1).isAfter(settings.endDate()) ? settings.endDate() : next.minusDays(1);
            periods.add(new PeriodResult(index, start, end, List.of()));
            start = next;
        }
        return List.copyOf(periods);
    }

    static boolean contains(PeriodResult period, LocalDate date) {
        return date != null && !date.isBefore(period.startDate()) && !date.isAfter(period.endDate());
    }

    static List<PeriodResult> group(List<PeriodResult> periods, List<Result> results) {
        return periods.stream().map(period -> new PeriodResult(period.index(), period.startDate(), period.endDate(),
                results.stream().filter(row -> contains(period, row.record().date()))
                        .sorted(java.util.Comparator.comparing((Result row) -> row.record().date())
                                .thenComparingLong(row -> row.record().recordId())).toList())).toList();
    }
}
