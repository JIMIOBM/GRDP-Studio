package com.grdp.studio.reservoirloss;

import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.reservoirloss.dto.WellboreLossDtos.*;
import com.grdp.studio.reservoirloss.service.WellboreLossCalculationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WellboreLossCalculationServiceTests {
    @Test
    void directModeReturnsInputWithoutCallingPvt() {
        var pvt = mock(GasPvtService.class);
        var service = new WellboreLossCalculationService(pvt);
        var input = new WellboreInput("direct", 12.5, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        Calculation result = service.calculate(new CalculateRequest(7, 4, input), null, null, null);

        assertEquals(12.5, result.wellboreLossVolume());
        assertNull(result.deviationFactorToolboxId());
    }

    @Test
    void formulaModeSumsSegmentsAndUsesTwoDeviationFactors() {
        var pvt = mock(GasPvtService.class);
        when(pvt.calculateWellboreDeviationFactors(any(), eq(20d), eq(10d), eq(313.15d),
                any(), any(), any())).thenReturn(new GasPvtService.DeviationFactorPairResult(1343, 0.9, 0.95));
        var service = new WellboreLossCalculationService(pvt);
        var input = new WellboreInput("formula", null, 313.15, 20d, 10d,
                List.of(new SegmentInput(100d), new SegmentInput(50d)), 0, 0.7336,
                14.62, 8.96, 0d, 0, 0, 0, "pvt.xls");

        Calculation result = service.calculate(new CalculateRequest(7, 4, input), "token", "cookie", "prod");

        double expected = 1e-4 * 150 * 293.15 / (0.101325 * 313.15)
                * (20 / 0.9 - 10 / 0.95);
        assertEquals(expected, result.wellboreLossVolume(), 1e-10);
        assertEquals(150d, result.totalSegmentVolume());
        verify(pvt).calculateWellboreDeviationFactors(any(), eq(20d), eq(10d), eq(313.15d),
                eq("token"), eq("cookie"), eq("prod"));
    }
}
