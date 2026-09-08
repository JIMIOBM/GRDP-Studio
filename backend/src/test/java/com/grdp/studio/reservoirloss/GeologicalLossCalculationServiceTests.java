package com.grdp.studio.reservoirloss;

import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.reservoirloss.dto.GeologicalLossDtos.*;
import com.grdp.studio.reservoirloss.service.GeologicalLossCalculationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeologicalLossCalculationServiceTests {
    @Test
    void microscopicLossUsesPercentSaturationAndPvtVolumeFactor() {
        GasPvtService pvt = mock(GasPvtService.class);
        when(pvt.calculateSingleVolumeFactor(any(), eq(20d), eq(40d), any(), any(), any()))
                .thenReturn(new GasPvtService.VolumeFactorResult(1327, 0.004));
        var service = new GeologicalLossCalculationService(pvt);
        var input = new MicroscopicInput(100, 10d, 14d, 20, 40d, 0, 0.73,
                0d, 0d, 0d, 0, 0, 0, null);
        var result = service.calculateMicroscopic(new MicroscopicCalculateRequest(7, 4, input), null, null, null);
        assertEquals(1000d, result.microscopicLossVolume(), 1e-9);
        assertEquals(1327, result.volumeFactorToolboxId());
    }

    @Test
    void escapeLossUsesActualMinusPredictedRate() {
        var service = new GeologicalLossCalculationService(mock(GasPvtService.class));
        var input = new EscapeInput(100, 80d, 30d, 500d, 5d);
        var result = service.calculateEscape(new EscapeCalculateRequest(7, 4, input));
        assertEquals(10d, result.actualChangeRate(), 1e-9);
        assertEquals(25d, result.escapeLossVolume(), 1e-9);
    }
}
