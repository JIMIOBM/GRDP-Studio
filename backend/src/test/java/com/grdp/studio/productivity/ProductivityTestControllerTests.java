package com.grdp.studio.productivity;

import com.grdp.studio.pvtstorage.dto.PvtRecordDetail;
import com.grdp.studio.pvtstorage.service.PvtStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static com.grdp.studio.productivity.ModifiedIsochronalExponentialModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductivityTestControllerTests {
    @Test
    void loadsPseudoPressureFromOwnedPvtInsteadOfTrustingClientPoints() {
        ProductivityTestService storage = mock(ProductivityTestService.class);
        ModifiedIsochronalExponentialCalculator calculator = mock(ModifiedIsochronalExponentialCalculator.class);
        PvtStorageService pvtStorage = mock(PvtStorageService.class);
        var pvt = new PvtRecordDetail(null, null, null, null, Map.of(),
                List.of(new PvtRecordDetail.GasResultPoint(1, 0d, 120d, 1d, 0d,
                                null, null, null, null),
                        new PvtRecordDetail.GasResultPoint(2, 20d, 120d, 1d, 200d,
                                null, null, null, null)), List.of(), List.of());
        when(pvtStorage.getDetail(4, 6, 1, "A1-3")).thenReturn(pvt);
        when(calculator.calculate(any())).thenReturn(new CalculateResponse("exponential", "pseudo-pressure",
                3d, .75, 2d, 100d, .99, "可靠", "q=CΔΦ^n",
                List.of(), List.of(), List.of(), List.of()));
        var controller = new ProductivityTestController(storage, calculator, pvtStorage);
        var request = new CalculateRequest(6, 1, "A1-3", 4, "production", "pseudo-pressure", 20d,
                List.of(new InputPoint(1, 1d, 20d, 19d), new InputPoint(2, 2d, 20d, 18d),
                        new InputPoint(3, 3d, 20d, 17d)),
                List.of(new PseudoPressurePoint(0d, 999d)));

        controller.calculateExponential(request);

        ArgumentCaptor<CalculateRequest> captor = ArgumentCaptor.forClass(CalculateRequest.class);
        verify(calculator).calculate(captor.capture());
        assertEquals(List.of(new PseudoPressurePoint(0d, 0d), new PseudoPressurePoint(20d, 200d)),
                captor.getValue().pseudoPressurePoints());
    }
}
