package com.grdp.studio.storagemainfactor;

import com.grdp.studio.integration.OriginalPlatformClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    void mergeKeepsEveryPlatformParameterAndOverridesOnlyWhatWeKnow() {
        // 原平台前端 clone 服务端返回的 input 再改几个字段；工具箱的参数集是平台声明的，
        // 自己拼一份不完整的会被参数校验判为不合法（缺的按 0 → "取值范围 (0, ...]"）。
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("originalPressure", 56340000);
        template.put("formationPressure", 20000000);
        template.put("gasReservoirType", 0);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("gasType", 0);
        nested.put("pressure", 20000000);
        nested.put("pseudoPressure", 40000000);
        template.put("gasPvtParam", nested);

        Map<String, Object> ours = new LinkedHashMap<>();
        ours.put("originalPressure", 50000000);
        Map<String, Object> ourNested = new LinkedHashMap<>();
        ourNested.put("gasType", 0);
        ours.put("gasPvtParam", ourNested);

        Map<String, Object> merged = MaterialBalanceEquationClient.mergeOverTemplate(template, ours);

        assertEquals(50_000_000, merged.get("originalPressure"));
        // 模板里有、我们没有的参数必须原样保留
        assertEquals(20000000, merged.get("formationPressure"));
        // 嵌套对象逐键合并，不能整块替换，否则又会丢掉平台需要的 PVT 参数
        Map<?, ?> pvt = (Map<?, ?>) merged.get("gasPvtParam");
        assertEquals(0, pvt.get("gasType"));
        assertEquals(20000000, pvt.get("pressure"));
        assertEquals(40000000, pvt.get("pseudoPressure"));
    }

    @Test
    void mergeWithoutATemplateStillYieldsOurPayload() {
        Map<String, Object> ours = new LinkedHashMap<>();
        ours.put("originalPressure", 50000000);
        assertEquals(50_000_000, MaterialBalanceEquationClient.mergeOverTemplate(null, ours).get("originalPressure"));
        assertEquals(Map.of(), MaterialBalanceEquationClient.mergeOverTemplate(null, null));
    }

    // ------------------------------------------------------------------
    // 会话复用：一条地层压力序列要算 20 个点，不能建 20 个工具箱
    // ------------------------------------------------------------------

    /** 与 ServiceTests 同一组可用入参。 */
    static ToolboxInput inputs(double cumulativeGasProduction) {
        return new ToolboxInput(50_000_000d, 353.15, 50_000_000_000d, cumulativeGasProduction,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
    }

    /**
     * 假原平台：记录收到的请求，GET 一律回一份带 input 模板与 output 结果的工具箱状态。
     *
     * <p>刻意手写而不是用 Mockito：原平台交互的形状（同一个 id 交替被当模板源和结果源读）
     * 是这里要验证的东西，用真实可读的假实现比一堆 when(...) 更能说清它在模拟什么。
     */
    private static final class FakePlatform extends OriginalPlatformClient {

        final List<Map<String, Object>> createBodies = new ArrayList<>();
        /** 每次 calc 发出的 input 载荷（JSON 字符串）。 */
        final List<String> calcInputs = new ArrayList<>();
        Double pressure = 40.5;
        RuntimeException failure;
        /** GET 时在 {@code input} 里回显的累产气量；null 表示平台不回显。 */
        Double echoGas = null;
        /** true：回显上一次提交的累产气量（模拟正常平台）。 */
        boolean mirrorSubmittedGas = false;
        Double lastSubmittedGas = null;

        FakePlatform() {
            super(null);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <B, T> T post(String relativePath, B body, Class<T> responseType, Map<String, String> headers) {
            if (failure != null) {
                throw failure;
            }
            Map<String, Object> sent = (Map<String, Object>) body;
            if ("/api/toolbox".equals(relativePath)) {
                createBodies.add(sent);
                return responseType.cast(parse("{\"id\":7}"));
            }
            String payload = String.valueOf(sent.get("input"));
            calcInputs.add(payload);
            lastSubmittedGas = node(payload).path("cumulativeGasProduction").asDouble();
            if (mirrorSubmittedGas) {
                echoGas = lastSubmittedGas;
            }
            return responseType.cast(parse("{\"status\":200}"));
        }

        @Override
        public <T> T get(String relativePath, Class<T> responseType, Map<String, String> headers) {
            if (failure != null) {
                throw failure;
            }
            String echoedGas = echoGas == null ? "" : ",\"cumulativeGasProduction\":" + echoGas;
            return responseType.cast(parse("{\"input\":{\"formationPressure\":20000000" + echoedGas
                    + ",\"gasPvtParam\":{\"pressure\":20000000,\"pseudoPressure\":40000000}},"
                    + "\"output\":{\"formationPressure\":" + (pressure == null ? "null" : pressure) + "}}"));
        }

        /** 每次提交的累产气；用来证明"变的只有这一个量"。 */
        List<Double> gasValues() {
            return calcInputs.stream().map(text -> node(text).path("cumulativeGasProduction").asDouble()).toList();
        }

        /** 每次提交的入参**抹掉累产气**后的字符串；全都一样才说明其余参数全程未变。 */
        List<String> payloadsWithoutGas() {
            return calcInputs.stream().map(text -> {
                ObjectNode copy = node(text).deepCopy();
                copy.remove("cumulativeGasProduction");
                return copy.toString();
            }).toList();
        }

        private static ObjectNode node(String text) {
            return (ObjectNode) parse(text);
        }

        private static JsonNode parse(String text) {
            try {
                return M.readTree(text);
            } catch (Exception e) {
                throw new IllegalStateException("假原平台遇到了无法解析的 JSON：" + text, e);
            }
        }
    }

    @Test
    void openToolboxThenCalculateWithReusesOneToolboxId() {
        var platform = new FakePlatform();
        var client = new MaterialBalanceEquationClient(platform, M);

        var session = client.openToolbox(8, Map.of());
        assertNotNull(session, "工具箱建起来了就应该有会话，否则整个序列都算不出理论值");
        assertEquals(7L, session.toolboxId());

        Double first = client.calculateWith(session, inputs(0.0012918), Map.of());
        Double second = client.calculateWith(session, inputs(37.356667794), Map.of());
        Double third = client.calculateWith(session, inputs(57.43970823), Map.of());

        assertEquals(40.5, first, 1e-9);
        assertEquals(40.5, third, 1e-9);
        assertNotNull(second);
        assertEquals(1, platform.createBodies.size(),
                "20 个点只建一个工具箱：每个点都新建会让请求数翻倍，而且平台侧会堆一堆无用记录");
        assertEquals(List.of(0.0012918, 37.356667794, 57.43970823), platform.gasValues(),
                "累产气必须逐点换成该点的值，不能被第一个点定住");
        assertEquals(1, platform.payloadsWithoutGas().stream().distinct().count(),
                "除累产气外其余参数全程不能变——这是「只有累产气随时间变化」这条要求的落点");
    }

    @Test
    void calculateWithKeepsThePlatformTemplateParameters() {
        var platform = new FakePlatform();
        var client = new MaterialBalanceEquationClient(platform, M);

        client.calculateWith(client.openToolbox(8, Map.of()), inputs(1.0), Map.of());

        var sent = FakePlatform.node(platform.calcInputs.getFirst());
        // 发出去的是**原平台界面口径**：50_000_000 Pa 在载荷里是 50 MPa。
        // 写成 50000000 会让参数校验报"取值范围 (0, 200]"，这正是之前两轮排查的那个坑。
        assertEquals(50, sent.path("originalPressure").asLong(),
                "工具箱载荷是界面单位（MPa / ℃ / %），不是库里的 Pa / K / 小数");
        assertEquals(20000000, sent.path("formationPressure").asLong(),
                "平台模板里我们没给过的参数必须原样带上，否则会被参数校验判为不合法");
        assertEquals(20000000, sent.path("gasPvtParam").path("pressure").asLong());
    }

    @Test
    void calculateWithRejectsIncompleteInputInsteadOfReturningNull() {
        // 入参不齐 ≠ 原平台不可用：前者要报出缺哪个字段（400），后者才降级为 null。
        var platform = new FakePlatform();
        var client = new MaterialBalanceEquationClient(platform, M);
        var session = client.openToolbox(8, Map.of());
        var noGasInPlace = new ToolboxInput(null, 353.15, 50_000_000_000d, 12.306372768,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));

        assertThrows(com.grdp.studio.common.BusinessException.class,
                () -> client.calculateWith(session, noGasInPlace, Map.of()));
    }

    @Test
    void openToolboxReturnsNullWhenThePlatformIsUnavailable() {
        var platform = new FakePlatform();
        platform.failure = new IllegalStateException("原平台连接被拒绝");
        var client = new MaterialBalanceEquationClient(platform, M);

        assertNull(client.openToolbox(8, Map.of()),
                "原平台不可用时返回 null（降级为「理论值算不出来」），不能把异常抛到页面上");
    }

    @Test
    void calculateWithReturnsNullWhenThePlatformIsUnavailable() {
        var platform = new FakePlatform();
        var client = new MaterialBalanceEquationClient(platform, M);
        var session = client.openToolbox(8, Map.of());

        platform.failure = new IllegalStateException("原平台连接被拒绝");

        assertNull(client.calculateWith(session, inputs(1.0), Map.of()),
                "某一个点算失败只能是该点理论值为空，不能让整条序列崩掉");
    }

    @Test
    void calculateWithRejectsImplausiblePressure() {
        var platform = new FakePlatform();
        var client = new MaterialBalanceEquationClient(platform, M);
        var session = client.openToolbox(8, Map.of());

        platform.pressure = 0.0;
        assertNull(client.calculateWith(session, inputs(1.0), Map.of()),
                "平台回 0 必须当成「没有结果」，否则折线图上会画出一个假的压力谷底");
        platform.pressure = 19650000.0;
        assertNull(client.calculateWith(session, inputs(1.0), Map.of()),
                "量级到了 Pa，说明口径变了，宁可降级也不要当成 MPa 显示出去");
        platform.pressure = null;
        assertNull(client.calculateWith(session, inputs(1.0), Map.of()));
    }

    @Test
    void acceptsTheResultOnlyWhenThePlatformEchoesTheSubmittedGas() {
        var platform = new FakePlatform();
        platform.mirrorSubmittedGas = true; // 正常平台：回显本次提交的累产气量
        var client = new MaterialBalanceEquationClient(platform, M);

        Double pressure = client.calculateWith(client.openToolbox(8, Map.of()), inputs(57.43970823), Map.of());

        assertEquals(40.5, pressure, 1e-9, "回显与提交一致时正常采信");
    }

    @Test
    void rejectsAStaleResultThatStillBelongsToThePreviousPoint() {
        var platform = new FakePlatform();
        platform.echoGas = 0.0012918; // 回显的是第一个点的累产气量
        var client = new MaterialBalanceEquationClient(platform, M);

        // 20 个点共用一个工具箱，"上一次的结果"不再意味着"什么都没有"，
        // 而是一个看着正常、却属于别的累产气量的压力——差异会被静默算错。
        // 宁可该点为空（线断开），也不能采信它。
        assertNull(client.calculateWith(client.openToolbox(8, Map.of()), inputs(57.43970823), Map.of()));
        assertEquals(1, platform.createBodies.size(), "只是不采信结果，不该重建工具箱");
    }

    @Test
    void acceptsResultsWhenThePlatformDoesNotEchoTheInput() {
        // 平台不回显 input 时不能据此判失败：否则平台一改响应形状，
        // 整条序列会全部变空——那是把"没法核对"当成了"核对失败"。
        var platform = new FakePlatform();
        platform.echoGas = null;
        var client = new MaterialBalanceEquationClient(platform, M);

        assertEquals(40.5, client.calculateWith(client.openToolbox(8, Map.of()), inputs(1.0), Map.of()), 1e-9);
    }
}
