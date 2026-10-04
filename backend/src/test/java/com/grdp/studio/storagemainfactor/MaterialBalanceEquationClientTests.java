package com.grdp.studio.storagemainfactor;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.GasPvtParam;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.ToolboxInput;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 原平台物质平衡方程工具箱客户端中**纯逻辑部分**的测试：结果解析、错误信封识别、请求头组装。
 * 不需要起 HTTP：真实网络调用由 Task 8 的端到端验收覆盖。
 */
class MaterialBalanceEquationClientTests {

    static final ObjectMapper M = new ObjectMapper();

    static JsonNode json(String text) throws Exception {
        return M.readTree(text);
    }

    @Test
    void readsFormationPressureFromToolboxOutput() throws Exception {
        var state = json("{\"id\":7,\"output\":{\"formationPressure\":32.1534}}");
        assertEquals(32.1534, MaterialBalanceEquationClient.extractFormationPressure(state), 1e-9);
    }

    @Test
    void returnsNullWhenOutputMissingInsteadOfZero() throws Exception {
        // 缺结果必须返回 null（→ 前端降级为手输），返回 0 会让差异列凭空多出 -32.15
        assertNull(MaterialBalanceEquationClient.extractFormationPressure(json("{\"id\":7,\"output\":{}}")));
        assertNull(MaterialBalanceEquationClient.extractFormationPressure(json("{}")));
        assertNull(MaterialBalanceEquationClient.extractFormationPressure(null));
    }

    @Test
    void detectsLegacyErrorEnvelopeHiddenInHttp200() throws Exception {
        // 原平台会把 {status:400} 放在 HTTP 200 里；只看 HTTP 状态码会把它当成成功数据
        assertTrue(MaterialBalanceEquationClient.isLegacyErrorEnvelope(json("{\"status\":400,\"msg\":\"参数错误\"}")));
        assertTrue(MaterialBalanceEquationClient.isLegacyErrorEnvelope(json("{\"status\":401}")));
        assertFalse(MaterialBalanceEquationClient.isLegacyErrorEnvelope(json("{\"status\":200,\"output\":{}}")));
        assertFalse(MaterialBalanceEquationClient.isLegacyErrorEnvelope(json("{\"id\":7}")));
        assertFalse(MaterialBalanceEquationClient.isLegacyErrorEnvelope(null));
    }

    @Test
    void readsToolboxIdFromSeveralResponseShapes() throws Exception {
        assertEquals(7L, MaterialBalanceEquationClient.extractToolboxId(json("{\"id\":7}")));
        assertEquals(7L, MaterialBalanceEquationClient.extractToolboxId(json("{\"id\":\"7\"}")));
        assertEquals(9L, MaterialBalanceEquationClient.extractToolboxId(json("{\"data\":{\"id\":9}}")));
        assertNull(MaterialBalanceEquationClient.extractToolboxId(json("{}")));
        assertNull(MaterialBalanceEquationClient.extractToolboxId(null));
    }

    @Test
    void forwardedHeadersAlwaysCarryProjectIdAndProcessEnv() {
        var headers = MaterialBalanceEquationClient.forwardedHeaders("tk", "cs", null, 8);
        assertEquals("8", headers.get("x-project-id"));
        assertEquals("prod", headers.get("Process-Env"));
        assertEquals("cs", headers.get("Cookie"));
        assertEquals("tk", headers.get("token"));
        assertEquals("dev", MaterialBalanceEquationClient.forwardedHeaders(null, null, "dev", 8).get("Process-Env"));
        // 缺 token / Cookie 时不要塞进空串，否则会把原平台会话打坏
        var bare = MaterialBalanceEquationClient.forwardedHeaders(null, null, null, 8);
        assertFalse(bare.containsKey("token"));
        assertFalse(bare.containsKey("Cookie"));
    }

    @Test
    void rejectsImplausiblePressureInsteadOfPresentingItAsAutomatic() {
        // 原平台压力按 MPa 解释。若某天口径变成 Pa（或返回 0/负值），
        // 直接把 19650000 当成 MPa 显示，就是又一个"看着正常的错结果"——宁可降级为手输。
        assertTrue(MaterialBalanceEquationClient.isPlausiblePressure(32.1534));
        assertTrue(MaterialBalanceEquationClient.isPlausiblePressure(50.0));
        assertTrue(MaterialBalanceEquationClient.isPlausiblePressure(0.01));
        assertFalse(MaterialBalanceEquationClient.isPlausiblePressure(19650000.0));
        assertFalse(MaterialBalanceEquationClient.isPlausiblePressure(0.0));
        assertFalse(MaterialBalanceEquationClient.isPlausiblePressure(-1.0));
        assertFalse(MaterialBalanceEquationClient.isPlausiblePressure(null));
    }

    @Test
    void incompleteInputFailsLoudlyInsteadOfSilentlyFallingBack() {
        // 入参不齐 ≠ 原平台不可用：前者要告诉用户缺哪个字段（400），
        // 后者才降级为手输。两者混为一谈会把"没填完"变成一个查不出原因的静默降级。
        // platform 传 null：若实现吞掉校验异常，这里会退化成返回 null，断言即失败。
        var client = new MaterialBalanceEquationClient(null, M);
        var noGasInPlace = new ToolboxInput(null, 353.15, 23.398270898104453, 12.306372768,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
        assertThrows(com.grdp.studio.common.BusinessException.class,
                () -> client.calculateFormationPressure(8, noGasInPlace, Map.of()));
    }
}
