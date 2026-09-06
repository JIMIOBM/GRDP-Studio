package com.grdp.studio.wellbore;
import com.grdp.studio.gaspvt.dto.GasViscosityCurveRequest;
import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.integration.OriginalPlatformClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PvtPropertySessionTest {
 @Test void gasVolumeDensityViscositySessionReusesThreeToolboxesAndCachesState() throws Exception {
  var client=mock(OriginalPlatformClient.class);var json=new ObjectMapper();
  when(client.post(eq("/api/toolbox"),any(),eq(JsonNode.class),anyMap())).thenReturn(json.readTree("{\"id\":7}"));
  when(client.get(eq("/api/toolbox/7"),eq(JsonNode.class),anyMap())).thenReturn(json.readTree("{\"result\":{\"volumeFactor\":0.003,\"density\":40,\"viscosity\":0.015}}"));
  var s=new GasPvtService(client,json).flowPropertiesSession(new GasViscosityCurveRequest(1L,0,.65,0d,0d,0d,30d,0d,0d,1d,0,0,0),"token",null,"prod");
  var first=s.apply(8d,80d);var cached=s.apply(8d,80d);
  assertEquals(.003,first.volumeFactor());assertEquals(40,first.density());assertEquals(.015,first.viscosity());assertSame(first,cached);
  verify(client,times(3)).post(eq("/api/toolbox"),any(),eq(JsonNode.class),anyMap());
  verify(client,times(3)).post(eq("/api/toolbox/calc"),any(),eq(JsonNode.class),anyMap());
  verify(client,times(3)).get(eq("/api/toolbox/7"),eq(JsonNode.class),anyMap());
 }
}
