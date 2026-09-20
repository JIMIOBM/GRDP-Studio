package com.grdp.studio.storagematerialbalance;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static com.grdp.studio.storagematerialbalance.StorageMaterialBalanceCalculator.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageMaterialBalanceTests {
    Sample row(int day, Double p, Double gas, Double water) {
        return new Sample(LocalDate.of(2020, 1, day), p, gas, water, false);
    }
    Source well(long id, Double g, Sample... samples) {
        return new Source(id, "X-" + id, id, g, .99, null, "", List.of(samples));
    }
    @Test void weightsPressureButSumsProductionAndPreservesUnits() {
        var result = calculate(List.of(well(1, 10d, row(1, 30d, 1d, .1)), well(2, 30d, row(1, 10d, 2d, .2))));
        assertEquals(15, result.rows().getFirst().pressure());
        assertEquals(3, result.rows().getFirst().gas());
        assertEquals(.3, result.rows().getFirst().water(), 1e-12);
        assertEquals(.25, result.wells().getFirst().weight());
        assertEquals(40, result.sourceGasVolume());
    }
    @Test void excludesWholeWellWithNoMeasuredSource() {
        var missing = new Source(2, "X-2", null, null, null, "无实测", "", List.of(row(1, 99d, 900d, 300d)));
        var result = calculate(List.of(well(1, 10d, row(1, 30d, 1d, .1)), missing));
        assertEquals(1, result.includedWellCount()); assertEquals(1, result.rows().getFirst().gas());
        assertEquals(30, result.rows().getFirst().pressure()); assertNull(result.wells().get(1).weight());
    }
    @Test void allMissingIsAnExplicitEmptyResultNotZeroReserves() {
        var result = calculate(List.of(well(1, 0d, row(1, 30d, 1d, .1))));
        assertNull(result.sourceGasVolume()); assertTrue(result.rows().isEmpty()); assertEquals(0, result.includedWellCount());
    }
    @Test void matchesDatesNotRowIndicesAndDoesNotInterpolateOrChangeWeights() {
        var result = calculate(List.of(well(1, 10d, row(2, 20d, 2d, .2), row(1, 30d, 1d, .1)),
                well(2, 30d, row(2, 10d, 3d, .3), row(3, 8d, 4d, .4))));
        assertEquals(1, result.rows().size()); assertEquals(LocalDate.of(2020, 1, 2), result.rows().getFirst().date());
        assertEquals(12.5, result.rows().getFirst().pressure()); assertEquals(5, result.rows().getFirst().gas());
        assertEquals(List.of("X-2"), result.skippedDates().getFirst().missingWells());
        assertEquals(List.of("X-1"), result.skippedDates().getLast().missingWells());
    }
    @Test void noCommonDatesDoesNotPretendToHaveAStorageCurve() {
        var result = calculate(List.of(well(1, 10d, row(1, 30d, 1d, 0d)), well(2, 30d, row(2, 10d, 2d, 0d))));
        assertTrue(result.rows().isEmpty()); assertEquals(2, result.skippedDates().size());
    }
    @Test void invalidWeightsAreExcludedIncludingNonFinite() {
        for (Double weight : new Double[]{null, 0d, -1d, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(0, calculate(List.of(well(1, weight, row(1, 30d, 1d, 0d)))).includedWellCount());
        }
    }
    @Test void duplicateDatesAreNotSummedOrArbitrarilyPicked() {
        var result = calculate(List.of(well(1, 10d, row(1, 30d, 1d, 0d), row(1, 29d, 1d, 0d), row(2, 28d, 2d, 0d))));
        assertEquals(1, result.rows().size()); assertEquals(2, result.wells().getFirst().discardedRows());
        assertEquals(28, result.rows().getFirst().pressure());
    }
    @Test void duplicateDateWithAnInvalidRowStillRejectsEntireDate() {
        var result = calculate(List.of(well(1, 10d, row(1, null, 1d, 0d), row(1, 29d, 1d, 0d))));
        assertEquals(0, result.includedWellCount()); assertEquals(2, result.wells().getFirst().discardedRows());
    }
    @Test void missingZeroNegativeNonFiniteAndDeletedSamplesAreNotZeroFilled() {
        var rows = new Sample[]{row(1, 0d, 1d, 0d), row(2, 10d, null, 0d), row(3, 10d, 1d, -1d),
                row(4, Double.NaN, 1d, 0d), row(5, 10d, Double.POSITIVE_INFINITY, 0d),
                new Sample(null, 10d, 1d, 0d, false), new Sample(LocalDate.of(2020, 1, 6), 10d, 1d, 0d, true)};
        var result = calculate(List.of(well(1, 10d, rows)));
        assertTrue(result.rows().isEmpty()); assertEquals(7, result.wells().getFirst().discardedRows());
    }
    @Test void genuineZeroCumulativeProductionIsAllowed() {
        var result = calculate(List.of(well(1, 10d, row(1, 30d, 0d, 0d))));
        assertEquals(1, result.rows().size()); assertEquals(0, result.rows().getFirst().gas());
    }
    @Test void fallingCumulativeGasOrWaterExcludesWholeWell() {
        for (Sample last : List.of(row(2, 28d, .5, .2), row(2, 28d, 2d, .05))) {
            var result = calculate(List.of(well(1, 10d, row(1, 30d, 1d, .1), last)));
            assertEquals(0, result.includedWellCount()); assertTrue(result.wells().getFirst().reason().contains("下降"));
        }
    }
    @Test void duplicatedMembershipAndOverflowAreRejected() {
        var one = well(1, 10d, row(1, 30d, 1d, 0d));
        assertThrows(BusinessException.class, () -> calculate(List.of(one, one)));
        assertThrows(BusinessException.class, () -> calculate(List.of(well(1, Double.MAX_VALUE, row(1, 30d, 1d, 0d)),
                well(2, Double.MAX_VALUE, row(1, 30d, 1d, 0d)))));
        assertThrows(BusinessException.class, () -> calculate(List.of(well(1, 10d, row(1, 30d, Double.MAX_VALUE, 0d)),
                well(2, 20d, row(1, 30d, Double.MAX_VALUE, 0d)))));
    }
}
