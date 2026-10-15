package com.grdp.studio.waterinvasion;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WaterInvasionServiceTests {
    @Test
    void releasesLockWhenLegacyCallAndWellCompletionAreConfirmedButResultCannotBeRead() {
        var storage = mock(WaterInvasionStorage.class);
        var legacy = mock(WaterInvasionLegacyGateway.class);
        var scope = new WaterInvasionModels.Scope(6, 3, "X-1");
        var request = new WaterInvasionModels.Start(6, 3, "X-1", "request-1", true, -1.0);
        var saved = Map.<String,Object>of(
            "input", Map.of("gasType", "dry"),
            "identifyInputItems", List.of(), "influxInputItems", List.of());
        var oldResult = Map.<String,Object>of("outputs", List.of(Map.of("analysisId", 3221)));
        when(storage.recalculationSnapshot(scope)).thenReturn(saved);
        when(legacy.resultIfPresent(scope, "session"))
            .thenReturn(oldResult)
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "旧平台暂不可用"));
        when(legacy.calculate(eq("X-1"), anyMap(), eq("session")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(legacy.completedSince(anyLong(), eq(scope), eq("session"))).thenReturn(true);

        var service = new WaterInvasionService(storage, legacy, JsonMapper.builder().build());
        try {
            service.execute(34, request, "session", false);
        } finally {
            service.close();
        }

        @SuppressWarnings("unchecked")
        var payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(legacy).calculate(eq("X-1"), payload.capture(), eq("session"));
        assertEquals(3221L, payload.getValue().get("analysisId"));
        verify(storage).fail(34, "旧平台暂不可用", false);
        verify(storage, never()).failSubmitted(anyLong(), anyString(), anyBoolean());
    }
}
