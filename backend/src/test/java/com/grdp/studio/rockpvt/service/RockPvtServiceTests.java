package com.grdp.studio.rockpvt.service;

import com.grdp.studio.integration.OriginalPlatformClient;
import com.grdp.studio.rockpvt.dto.RockCurveRequest;
import com.grdp.studio.rockpvt.dto.RockCurveTwoPoint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RockPvtServiceTests {

    @Test
    void prefersResultPayloadOverEchoedInputWhenExtractingCompressibility() throws Exception {
        OriginalPlatformClient originalPlatformClient = mock(OriginalPlatformClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<Double> currentPorosity = new AtomicReference<>();

        when(originalPlatformClient.post(
                eq("/api/toolbox"),
                anyMap(),
                eq(JsonNode.class),
                anyMap()
        )).thenReturn(objectMapper.createObjectNode().put("id", 123L));

        when(originalPlatformClient.post(
                eq("/api/toolbox/calc"),
                anyMap(),
                eq(JsonNode.class),
                anyMap()
        )).thenAnswer(invocation -> {
            Map<String, Object> body = invocation.getArgument(1);
            JsonNode input = objectMapper.readTree((String) body.get("input"));
            currentPorosity.set(input.get("porosity").asDouble());
            return objectMapper.createObjectNode();
        });

        when(originalPlatformClient.get(
                eq("/api/toolbox/123"),
                eq(JsonNode.class),
                anyMap()
        )).thenAnswer(invocation -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("input", objectMapper.createObjectNode().put("compressibilityFactor", 0.0));
            ObjectNode result = objectMapper.createObjectNode();
            result.put("compressibilityFactor", currentPorosity.get() + 100.0);
            response.set("result", result);
            return response;
        });

        RockPvtService service = new RockPvtService(originalPlatformClient, objectMapper);

        var response = service.calculateCurveTwo(
                new RockCurveRequest(1L, 0.0, 1.0, 1.0),
                "token",
                "cookie",
                "prod"
        );

        assertThat(response.items())
                .extracting(RockCurveTwoPoint::compressibilityFactor)
                .containsExactly(100.0, 101.0);
    }
}
