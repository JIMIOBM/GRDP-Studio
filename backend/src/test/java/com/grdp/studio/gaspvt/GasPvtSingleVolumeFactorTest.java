package com.grdp.studio.gaspvt;

import com.grdp.studio.gaspvt.dto.GasViscosityCurveRequest;
import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.integration.OriginalPlatformClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GasPvtSingleVolumeFactorTest {
    @Test
    void singleVolumeFactorKeepsLegacyToolboxUnitsAndRequiredPressureFields() throws Exception {
        var client = mock(OriginalPlatformClient.class);
        var json = new ObjectMapper();
        when(client.post(eq("/api/toolbox"), any(), eq(JsonNode.class), anyMap()))
                // 原平台实际返回数组：[ { "id": ... } ]。
                .thenReturn(json.readTree("[{\"id\":1343}]"));
        when(client.get(eq("/api/toolbox/1343"), eq(JsonNode.class), anyMap()))
                .thenReturn(json.readTree("{\"output\":{\"volumeFactor\":0.0043}}"));

        var request = new GasViscosityCurveRequest(7L, 0, 0.7336,
                14.62, 8.96, 0d, 39.85, 20d, 20d, 1d, 0, 0, 0);
        var result = new GasPvtService(client, json).calculateSingleVolumeFactor(
                request, 20d, 39.85, "token", "cookie", "prod");

        var body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq("/api/toolbox/calc"), body.capture(), eq(JsonNode.class), anyMap());
        var createBody = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq("/api/toolbox"), createBody.capture(), eq(JsonNode.class), anyMap());
        var createWrapper = (Map<?, ?>) createBody.getValue();
        assertEquals("GasPVT_VolumeFactor", createWrapper.get("algorithm"));
        assertEquals(7L, createWrapper.get("projectId"));

        var wrapper = (Map<?, ?>) body.getValue();
        JsonNode input = json.readTree((String) wrapper.get("input"));

        assertEquals(20d, input.get("pressure").doubleValue());
        assertEquals(39.85, input.get("temperature").doubleValue());
        assertEquals(8.96, input.get("co2MoleFraction").doubleValue());
        assertEquals(14.62, input.get("h2SMoleFraction").doubleValue());
        assertEquals(40d, input.get("originalPressure").doubleValue());
        assertEquals(4e-8d, input.get("pseudoPressure").doubleValue());
        assertEquals(40d, input.get("regularizedPseudoPressure").doubleValue());
        assertEquals(40d, input.get("apparentPressure").doubleValue());
        assertEquals(1343L, result.toolboxId());
        assertEquals(0.0043, result.volumeFactor());
    }

    @Test
    void wellboreDeviationFactorsReuseToolboxAndConvertKelvinToCelsius() throws Exception {
        var client = mock(OriginalPlatformClient.class);
        var json = new ObjectMapper();
        when(client.post(eq("/api/toolbox"), any(), eq(JsonNode.class), anyMap()))
                .thenReturn(json.readTree("[{\"id\":1350}]"));
        when(client.get(eq("/api/toolbox/1350"), eq(JsonNode.class), anyMap()))
                .thenReturn(json.readTree("{\"output\":{\"deviationFactor\":0.8009}}"))
                .thenReturn(json.readTree("{\"output\":{\"deviationFactor\":0.9001}}"));
        var request = new GasViscosityCurveRequest(7L, 0, 0.7336,
                14.62, 8.96, 0d, 39.85, 10d, 20d, 1d, 0, 0, 0);

        var result = new GasPvtService(client, json).calculateWellboreDeviationFactors(
                request, 20d, 10d, 313d, "token", "cookie", "prod");

        var body = ArgumentCaptor.forClass(Object.class);
        verify(client, org.mockito.Mockito.times(2)).post(eq("/api/toolbox/calc"), body.capture(),
                eq(JsonNode.class), anyMap());
        JsonNode firstInput = json.readTree((String) ((Map<?, ?>) body.getAllValues().get(0)).get("input"));
        JsonNode secondInput = json.readTree((String) ((Map<?, ?>) body.getAllValues().get(1)).get("input"));
        assertEquals(39.85d, firstInput.get("temperature").doubleValue(), 1e-10);
        assertEquals(20d, firstInput.get("pressure").doubleValue());
        assertEquals(10d, secondInput.get("pressure").doubleValue());
        assertEquals(0.8009, result.deviationFactorBefore());
        assertEquals(0.9001, result.deviationFactorAfter());
    }
}
