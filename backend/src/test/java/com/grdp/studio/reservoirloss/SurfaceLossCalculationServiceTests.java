package com.grdp.studio.reservoirloss;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.reservoirloss.dto.SurfaceLossDtos.*;
import com.grdp.studio.reservoirloss.service.SurfaceLossCalculationService;
import com.grdp.studio.reservoirloss.service.WellboreLossCalculationService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SurfaceLossCalculationServiceTests {
    static SurfaceInput direct(Double ventLoss, Double condensateLoss) {
        return new SurfaceInput("direct", ventLoss, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, condensateLoss);
    }

    @Test
    void directVentingAndCondensateHaveIndependentResultsWithoutPvt() {
        var pvt = mock(GasPvtService.class);
        var service = new SurfaceLossCalculationService(new WellboreLossCalculationService(pvt));
        var result = service.calculate(new CalculateRequest(9, 6, direct(12d, 10d)), null, null, null);
        assertEquals(12, result.ventLossVolume());
        assertEquals(10, result.condensateLossVolume()); // 两项最终气量均原样返回。
        assertNull(result.deviationFactorBefore());
        assertEquals(0, service.calculate(new CalculateRequest(9, 6, direct(0d, 0d)),
                null, null, null).condensateLossVolume());
        verifyNoInteractions(pvt);
    }

    @Test
    void formulaReusesTheAverageTemperatureAndDynamicProjectForPvt() {
        var pvt = mock(GasPvtService.class);
        when(pvt.calculateWellboreDeviationFactors(any(), eq(20d), eq(10d), eq(313.15d),
                any(), any(), any())).thenReturn(new GasPvtService.DeviationFactorPairResult(1343, .9, .95));
        var service = new SurfaceLossCalculationService(new WellboreLossCalculationService(pvt));
        var result = service.calculate(new CalculateRequest(9, 6, SurfaceLossStorageTests.formula(100)), "t", "c", "prod");
        assertEquals(SurfaceLossStorageTests.output(150).ventLossVolume(), result.ventLossVolume(), 1e-10);
        assertEquals(10, result.condensateLossVolume());
        verify(pvt).calculateWellboreDeviationFactors(argThat(input -> input.projectId() == 9),
                eq(20d), eq(10d), eq(313.15d), eq("t"), eq("c"), eq("prod"));
    }

    @Test
    void rejectsMissingNegativeAndNonFiniteDirectInputsWithoutPvt() {
        var pvt = mock(GasPvtService.class);
        var service = new SurfaceLossCalculationService(new WellboreLossCalculationService(pvt));
        for (var input : new SurfaceInput[]{ direct(null, 1d), direct(1d, null),
                direct(-1d, 1d), direct(1d, -1d), direct(Double.NaN, 2d), direct(1d, Double.POSITIVE_INFINITY) }) {
            assertThrows(BusinessException.class,
                    () -> service.calculate(new CalculateRequest(9, 6, input), null, null, null));
        }
        verifyNoInteractions(pvt);
    }
}
