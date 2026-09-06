package com.grdp.studio.wellbore;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import com.grdp.studio.wellbore.pressure.method.HagedornBrownMethod;
import com.grdp.studio.wellbore.pressure.method.MukherjeeBrillMethod;
import com.grdp.studio.wellbore.pressure.method.PressureCalculator;
import com.grdp.studio.wellbore.pressure.method.PressureCorrelations;
import com.grdp.studio.wellbore.temperature.dto.TemperatureCalculateRequest;
import com.grdp.studio.wellbore.temperature.model.TemperatureCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WellboreAlgorithmTest {
    @Test
    void hbMbAndJainMatchSuppliedJavaScript() {
        assertEquals(
                0.01927411839289732,
                PressureCorrelations.frictionJain(0.062, 0.000016, 100000),
                1e-15
        );
        double[] angles = {0, 30, 80};
        double[] hb = {
                0.002128989612261519,
                0.0018485556384069963,
                0.0003992801607328847
        };
        double[] mb = {
                0.0023245702405912125,
                0.002143217364579254,
                0.0006319370974573605
        };
        for (int index = 0; index < angles.length; index++) {
            assertEquals(
                    hb[index],
                    HagedornBrownMethod.gradient(
                            8, 350, 0.062, 3, 0.1, 3.1,
                            1000, 50, 0.9, 0.015, 0.03, 0.000016, angles[index]
                    ),
                    1e-14
            );
            assertEquals(
                    mb[index],
                    MukherjeeBrillMethod.gradient(
                            8, 350, 0.062, 3, 0.1, 3.1,
                            1000, 50, 0.9, 0.015, 0.03, 0.000016, angles[index]
                    ),
                    1e-14
            );
        }
    }

    @Test
    void pressureUsesTenIterationsRelaxationAndRetainsNonconvergedSegment() {
        var request = new PressureCalculateRequest();
        request.qGas = 2.5;
        request.qLiq = 2;
        request.boundaryPressure = 3.8;
        request.models = List.of("HB", "MB");
        var result = PressureCalculator.calculate(
                request,
                List.of(0d, 3100d),
                List.of(30d, 115d),
                (pressure, temperature) -> new PressureCalculator.Properties(
                        0.003, 40, 0.015, 1000, 0.9
                )
        );
        for (var method : result.methods().values()) {
            assertTrue(method.maxIterationCount() <= 10);
            assertEquals(2, method.profile().size());
            assertNotNull(method.profile().getLast().segmentConverged());
        }
    }

    @Test
    void directPressureCalculationMatchesSuppliedJavaScript() {
        var request = new PressureCalculateRequest();
        request.depth = 100;
        request.step = 50;
        request.idTubing = 62;
        request.boundaryPressure = 3.8;
        request.tWh = 30;
        request.tGrad = 3;
        request.qGas = 2.5;
        request.qLiq = 2;
        request.gammaG = 0.65;
        request.rhoL = 1000;
        request.muL = 0.9;
        request.roughness = 0.016;
        request.angle = 0;
        request.models = List.of("HB", "MB");

        var result = PressureCalculator.calculate(request);

        assertEquals(List.of(0d, 50d, 100d), result.depth());
        assertEquals(
                List.of(3.8, 3.8582, 3.9166),
                result.methods().get("HB").profile().stream()
                        .map(PressureCalculator.Point::pressure)
                        .toList()
        );
        assertEquals(
                List.of(3.8, 3.8343, 3.8688),
                result.methods().get("MB").profile().stream()
                        .map(PressureCalculator.Point::pressure)
                        .toList()
        );
    }

    @Test
    void independentTemperatureHasNoPressureProfileOrJtTerm() {
        var request = new TemperatureCalculateRequest();
        var result = TemperatureCalculator.calculate(request);
        assertFalse(result.pressureCoupled());
        assertEquals(0, result.jtGradient());
        assertEquals(0, result.pvtIterations());
        assertEquals("wellhead", result.calculationPosition());
        assertEquals(30, result.temp().getFirst());
        assertEquals(result.depth().size(), result.tempFormation().size());

        request.boundaryPosition = "bottomhole";
        assertThrows(
                BusinessException.class,
                () -> TemperatureCalculator.calculate(request)
        );
    }

    @Test
    void linearTemperatureMatchesSuppliedJavaScript() {
        var request = new TemperatureCalculateRequest();
        request.tempModel = "linear";
        request.depth = 100;
        request.step = 50;
        request.tWh = 30;
        request.tSurf = 20;
        request.tGrad = 3;

        var result = TemperatureCalculator.calculate(request);

        assertEquals(List.of(0d, 50d, 100d), result.depth());
        assertEquals(List.of(30d, 31.5d, 33d), result.temp());
        assertEquals(List.of(20d, 21.5d, 23d), result.tempFormation());
        assertNull(result.thermal());
        assertEquals(33, result.inferredBottomTemperature());
    }

    @Test
    void rejectsInjectionAndInvalidTemperatureProfiles() {
        var request = new PressureCalculateRequest();
        request.operationMode = "injection";
        assertThrows(
                BusinessException.class,
                () -> PressureCalculator.calculate(
                        request,
                        List.of(0d, 10d),
                        List.of(30d, 31d),
                        (pressure, temperature) -> null
                )
        );

        request.operationMode = "production";
        assertThrows(
                BusinessException.class,
                () -> PressureCalculator.calculate(
                        request,
                        List.of(0d, 0d),
                        List.of(30d, 31d),
                        (pressure, temperature) -> null
                )
        );
    }
}
