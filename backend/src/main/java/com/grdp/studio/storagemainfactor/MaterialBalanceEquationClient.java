package com.grdp.studio.storagemainfactor;

import com.grdp.studio.integration.OriginalPlatformClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.ToolboxInput;

/**
 * 调用原平台「工具箱 → 物质平衡方程 → 计算地层压力」，得到**理论地层压力**。
 *
 * <p>算法编码 {@link #ALGORITHM} 取自原平台自身前端 bundle
 * （{@code assets/index-3fe4405a.js}），不是猜测值。
 *
 * <p>三步法与仓库里其它 7 个调用原平台的模块一致：
 * <ol>
 *   <li>{@code POST /api/toolbox} —— 用 algorithm + projectId 创建工具箱，取 id；</li>
 *   <li>{@code POST /api/toolbox/calc} —— 提交该点的 input，注意 {@code input} 必须是
 *       <b>JSON 字符串</b>而不是嵌套对象；</li>
 *   <li>{@code GET /api/toolbox/{id}} —— 读取结果，理论地层压力在 {@code output.formationPressure}。</li>
 * </ol>
 * 工具箱是**有状态**的：同一个 id 必须 calc 后立即 GET，不能并发复用。
 *
 * <p><b>降级是需求，不是异常</b>：原平台不可用、会话过期、超时、结果缺失，
 * 一律返回 {@code null} 交给前端切手输；只有"入参不齐"才抛错（那是用户还没填完，
 * 应该告诉他缺什么，而不是悄悄降级）。
 */
@Service
public class MaterialBalanceEquationClient {

    public static final String ALGORITHM = "MaterialBalanceEquationAppl_FormationPressure";

    /**
     * 原平台返回的地层压力按 **MPa** 解释（该平台其它压力输出也是 MPa）。
     * 这个区间只用于兜住"口径变了"的情况：若哪天它开始返回 Pa，
     * 19650000 会被判为不可信而走手输，而不是当成 MPa 显示出去。
     */
    private static final double MIN_PLAUSIBLE_MPA = 0.01d;
    private static final double MAX_PLAUSIBLE_MPA = 300d;

    /** 工具箱是异步落结果的，calc 返回只代表受理，所以要给结果几次机会。 */
    private static final int RESULT_ATTEMPTS = 3;
    private static final long RESULT_RETRY_MILLIS = 300L;

    private static final Logger log = LoggerFactory.getLogger(MaterialBalanceEquationClient.class);

    private final OriginalPlatformClient platform;
    private final ObjectMapper json;

    public MaterialBalanceEquationClient(OriginalPlatformClient platform, ObjectMapper json) {
        this.platform = platform;
        this.json = json;
    }

    /**
     * 组装转发给原平台的请求头。{@code x-project-id} 必须带：原平台用它校验项目权限，
     * 只在请求体里传 projectId 会被挡掉。缺 token/Cookie 时不写入空串，避免把会话打坏。
     */
    public static Map<String, String> forwardedHeaders(String token, String cookie, String processEnv, long projectId) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("token", token);
        }
        if (cookie != null && !cookie.isBlank()) {
            headers.put(HttpHeaders.COOKIE, cookie);
        }
        headers.put("Process-Env", processEnv == null || processEnv.isBlank() ? "prod" : processEnv);
        headers.put("x-project-id", Long.toString(projectId));
        return headers;
    }

    /**
     * 从工具箱状态里取理论地层压力。缺失返回 {@code null}——返回 0 会让差异列
     * 凭空多出一个 -理论值 的偏差，比没有值更糟。
     */
    public static Double extractFormationPressure(JsonNode toolboxState) {
        if (toolboxState == null) {
            return null;
        }
        JsonNode output = toolboxState.path("output");
        JsonNode value = output.path("formationPressure");
        if (!value.isNumber()) {
            return null;
        }
        double pressure = value.asDouble();
        return Double.isFinite(pressure) ? pressure : null;
    }

    /**
     * 原平台会把业务错误放在 HTTP 200 的 {@code {status: 400, msg: "..."}} 里，
     * {@link OriginalPlatformClient} 只看 HTTP 状态码，会把它当成成功数据返回，
     * 所以必须在这里显式识别。
     */
    public static boolean isLegacyErrorEnvelope(JsonNode body) {
        if (body == null) {
            return false;
        }
        JsonNode status = body.path("status");
        if (!status.isNumber()) {
            return false;
        }
        int code = status.asInt();
        return code == 400 || code == 401;
    }

    /** 地层压力是否落在可解释为 MPa 的范围内；超出即认为口径异常，交给人工填写。 */
    public static boolean isPlausiblePressure(Double pressureMpa) {
        return pressureMpa != null && Double.isFinite(pressureMpa)
                && pressureMpa >= MIN_PLAUSIBLE_MPA && pressureMpa <= MAX_PLAUSIBLE_MPA;
    }

    /**
     * 创建工具箱的响应形状不唯一，id 直接找、找不到再往 data 里找一层。
     */
    public static Long extractToolboxId(JsonNode created) {
        if (created == null) {
            return null;
        }
        Long direct = asLong(created.path("id"));
        if (direct != null) {
            return direct;
        }
        return asLong(created.path("data").path("id"));
    }

    /**
     * 三步法取理论地层压力。原平台侧的任何失败都返回 {@code null}（降级为手输）。
     *
     * @return 理论地层压力；原平台不可用或没有结果时为 {@code null}
     * @throws com.grdp.studio.common.BusinessException 入参不齐（400），此时不降级
     */
    public Double calculateFormationPressure(long projectId, ToolboxInput input, Map<String, String> headers) {
        // 入参校验发生在 try 之外，因此它的 BusinessException 不会被下面的 catch 吞掉：
        // "用户没填完" 与 "原平台不可用" 是两件事，前者要报出缺哪个字段，后者才降级为手输。
        Map<String, Object> payload = StorageMainFactorCalculator.toolboxPayload(input);
        // 记录**真正发给原平台**的那份入参：合并模板之后它和 payload 不是一回事，
        // 日志里打 payload 会让人误判成"我发的就是这些字段"。
        Map<String, Object> sent = payload;
        // 平台的参数契约：fields 给出每个参数的键与单位，inputRange 给出取值范围。
        // 出现"参数校验失败"时，这两样是唯一能一次说清原因的凭据——
        // 靠对比入参反推一个内部算法的契约是不收敛的。
        String contract = "";
        String step = "创建工具箱";
        try {
            JsonNode created = platform.post("/api/toolbox",
                    Map.of("algorithm", ALGORITHM, "projectId", projectId), JsonNode.class, headers);
            if (isLegacyErrorEnvelope(created)) {
                log.warn("创建物质平衡方程工具箱失败：{}", created.path("msg").asText("原平台返回错误"));
                return null;
            }
            Long toolboxId = extractToolboxId(created);
            if (toolboxId == null) {
                log.warn("创建物质平衡方程工具箱后未取到 id：{}", created);
                return null;
            }

            // 以**平台自己返回的入参对象**为底再覆盖（原平台前端就是这么做的：
            // 它 clone 服务端返回的 input，只改其中几个字段）。
            // 工具箱的参数集是平台按算法声明的；自己从零拼一份不完整的，
            // 缺的字段会被当成 0，于是报"某某 取值范围 (0, ...]"——之前两轮
            // 只修我们发出去的字段，所以怎么改都没用，因为问题在**没发的字段**。
            step = "读取入参模板";
            JsonNode templateState = platform.get("/api/toolbox/" + toolboxId, JsonNode.class, headers);
            if (isLegacyErrorEnvelope(templateState)) {
                log.warn("读取物质平衡方程入参模板失败：{}", templateState.path("msg").asText("原平台返回错误"));
                return null;
            }
            Map<String, Object> template = asMap(templateState.path("input"));
            contract = describeContract(templateState);
            // 模板与我们的 payload 都是**界面单位**（fields.unit_label 为 MPa/℃/%），
            // 直接合并即可，不要再做换算——上一版把模板的 20 当成 MPa 乘了 10⁶，
            // 反而把 maxOriginalPressure=200 撑爆。
            Map<String, Object> effective = mergeOverTemplate(template, payload);
            sent = effective;
            if (log.isInfoEnabled() && !effective.keySet().equals(payload.keySet())) {
                log.info("物质平衡方程工具箱参数集以平台模板为准，额外字段：{}",
                        effective.keySet().stream().filter(k -> !payload.containsKey(k)).toList());
            }

            step = "提交计算";
            JsonNode calculated = platform.post("/api/toolbox/calc",
                    Map.of("id", toolboxId, "input", json.writeValueAsString(effective)), JsonNode.class, headers);
            if (isLegacyErrorEnvelope(calculated)) {
                log.warn("物质平衡方程计算失败：{}", calculated.path("msg").asText("原平台返回错误"));
                return null;
            }

            step = "读取结果";
            JsonNode state = null;
            Double pressure = null;
            // 工具箱计算异步落结果：calc 之后立即 GET 可能仍是上一次的值或空结果，
            // 所以这里给几次机会；直接把第一次读到的值当成"自动算出来的"会展示一个过期结果。
            for (int attempt = 0; attempt < RESULT_ATTEMPTS; attempt++) {
                state = platform.get("/api/toolbox/" + toolboxId, JsonNode.class, headers);
                if (isLegacyErrorEnvelope(state)) {
                    log.warn("读取物质平衡方程结果失败：{}", state.path("msg").asText("原平台返回错误"));
                    return null;
                }
                pressure = extractFormationPressure(state);
                if (pressure != null) {
                    break;
                }
                if (attempt < RESULT_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(RESULT_RETRY_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
            if (pressure == null) {
                log.warn("物质平衡方程结果里没有 output.formationPressure：{}", state);
                return null;
            }
            if (!isPlausiblePressure(pressure)) {
                log.warn("物质平衡方程返回的地层压力 {} 不在可解释为 MPa 的范围内，降级为手动填写", pressure);
                return null;
            }
            return pressure;
        } catch (RuntimeException e) {
            // 带上**失败的步骤**和**实际发出的入参**：原平台只回状态码时，
            // 这两样是唯一能定位原因的东西（原平台自己的说明由 OriginalPlatformClient 带在消息里）。
            log.warn("原平台物质平衡方程工具箱调用失败（{}），降级为手动填写理论地层压力：{}；已发送入参 {}；平台参数契约 {}",
                    step, e.getMessage(), sent, contract);
            return null;
        }
    }

    /**
     * 以原平台返回的 {@code input} 对象为底，把我们算出来的值覆盖上去。
     *
     * <p>工具箱的参数集由平台按算法声明。只发我们"认识"的那几个字段，
     * 其余参数在平台的校验里就是 0，于是报出
     * {@code 参数校验失败: 原始地层压力 取值范围 (0, 500000000]} 这类错误——
     * 数值本身合法，缺的是**没发出去的参数**。
     *
     * <p>嵌套对象（{@code gasPvtParam}）必须**逐键合并**，不能整块替换，
     * 否则又会把平台需要的 PVT 参数丢掉。
     */
    public static Map<String, Object> mergeOverTemplate(Map<String, Object> template, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (template != null) {
            merged.putAll(template);
        }
        if (overrides == null) {
            return merged;
        }
        overrides.forEach((key, value) -> {
            Object base = merged.get(key);
            if (base instanceof Map<?, ?> baseMap && value instanceof Map<?, ?> overrideMap) {
                merged.put(key, mergeOverTemplate(stringKeyed(baseMap), stringKeyed(overrideMap)));
                return;
            }
            merged.put(key, value);
        });
        return merged;
    }

    /**
     * 模板里的压力字段是界面单位（MPa），换算成算法单位（Pa）。
     *
     * <p>依据：原平台 {@code toolbox_result} 里一次**成功**的
     * {@code MaterialBalanceEquationAppl_FormationPressure} 调用存的是
     * {@code formationPressure=20000000}、{@code pressure=20000000}、
     * {@code regularizedPseudoPressure=40000000}、{@code apparentPressure=40000000}，
     * 而同一套工具 GET 回来的模板给的是 20 / 20 / 40 / 40——正好相差 10⁶。
     *
     * <p>{@code pseudoPressure} 不在此列：本仓库能工作的 GasPVT 调用直接发
     * {@code pseudoPressure=4e-8}（见 GasPvtService.buildLegacySinglePointInput），
     * 说明该字段不是 MPa 量纲，不能一起乘。
     *
     * <p>这些辅助字段算法会自己重算，值不重要，只要落在合法区间内且量纲正确。
     */
    private static final java.util.List<String> TEMPLATE_PRESSURE_MPA_KEYS =
            java.util.List.of("formationPressure", "originalPressure", "pressure",
                    "regularizedPseudoPressure", "apparentPressure");

    private static void scaleTemplatePressures(Map<String, Object> template) {
        template.replaceAll((key, value) ->
                TEMPLATE_PRESSURE_MPA_KEYS.contains(key) ? mpaToPa(value) : value);
        Object nested = template.get("gasPvtParam");
        if (nested instanceof Map<?, ?> map) {
            Map<String, Object> typed = stringKeyed(map);
            typed.replaceAll((key, value) ->
                    TEMPLATE_PRESSURE_MPA_KEYS.contains(key) ? mpaToPa(value) : value);
            template.put("gasPvtParam", typed);
        }
    }

    private static Object mpaToPa(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue() * 1_000_000d;
        }
        return value;
    }

    /**
     * 把平台的参数契约压成一行：{@code fields} 给每个参数的键与单位，
     * {@code inputRange} 给每个参数的取值范围。
     *
     * <p>参数校验失败时，这两样直接说明"哪个键、什么单位、什么区间"，
     * 比反复对比入参去反推要可靠得多。
     */
    private static String describeContract(JsonNode state) {
        return "fields=" + truncate(state.path("fields").toString(), 1500)
                + " inputRange=" + truncate(state.path("inputRange").toString(), 1500);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> source) {
        Map<String, Object> typed = new LinkedHashMap<>();
        source.forEach((key, value) -> typed.put(String.valueOf(key), value));
        return typed;
    }

    /** JsonNode 对象转 Map；不是对象就返回空表（模板缺失时退化为只用我们自己的入参）。 */
    private Map<String, Object> asMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return stringKeyed(json.convertValue(node, Map.class));
    }

    private static Long asLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        // 原平台有时把 id 当字符串返回（"7"）。canConvertToLong() 对文本节点是 false，
        // 只判断它会把合法响应当成"没取到 id"。
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return node.canConvertToLong() ? node.asLong() : null;
    }
}
