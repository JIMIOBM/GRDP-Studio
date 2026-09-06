package com.grdp.studio.gaspvt.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.gaspvt.dto.GasCurveOnePoint;
import com.grdp.studio.gaspvt.dto.GasCurveOneResponse;
import com.grdp.studio.gaspvt.dto.GasCurveTwoPoint;
import com.grdp.studio.gaspvt.dto.GasCurveTwoResponse;
import com.grdp.studio.gaspvt.dto.GasCurveThreePoint;
import com.grdp.studio.gaspvt.dto.GasCurveThreeResponse;
import com.grdp.studio.gaspvt.dto.GasViscosityCurveRequest;
import com.grdp.studio.gaspvt.dto.GasViscosityCurveResponse;
import com.grdp.studio.gaspvt.dto.GasViscosityPoint;
import com.grdp.studio.integration.OriginalPlatformClient;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 天然气 PVT 曲线计算的核心服务。
 *
 * <p>每一种原平台算法都遵循相同的三步流程：</p>
 * <ol>
 *   <li>POST /api/toolbox，以 algorithm + projectId 创建工具箱并取得 id；</li>
 *   <li>POST /api/toolbox/calc，把该压力点的 input JSON 交给工具箱计算；</li>
 *   <li>GET /api/toolbox/{id}，读取刚刚计算出的结果。</li>
 * </ol>
 *
 * <p>同一条曲线会复用第一次创建得到的 toolbox id，之后只针对不同压力重复第 2、3 步。
 * 这样既符合原接口的状态模型，也避免为每个压力点重复创建工具箱。</p>
 */
@Service
public class GasPvtService {

    // 原平台登记的算法名称。曲线 1、2 各包含两个指标，因此各需要两个 toolbox。
    private static final String ALGORITHM = "GasPVT_Viscosity";
    private static final String DEVIATION_FACTOR_ALGORITHM = "GasPVT_DeviationFactor";
    private static final String PSEUDO_PRESSURE_ALGORITHM = "GasPVT_PseudoPressure";
    private static final String DENSITY_ALGORITHM = "GasPVT_Density";
    private static final String VOLUME_FACTOR_ALGORITHM = "GasPVT_VolumeFactor";
    private static final String COMPRESSIBILITY_ALGORITHM = "GasPVT_Compressibility";
    // 防止前端误传过小步长，造成大量同步远程请求。
    private static final int MAX_POINT_COUNT = 500;
    // 原算法不能稳定处理绝对零压力时使用的内部替代值；返回给前端的压力仍保持 0。
    private static final double ZERO_PRESSURE_CALCULATION_EPSILON_MPA = 1e-6;
    private static final double PA_PER_MPA = 1_000_000d;
    private static final double KELVIN_OFFSET = 273.15d;
    private static final double FRACTION_PER_PERCENT = 0.01d;

    public record FlowGas(double density, double viscosity) {}
    /**
     * 井筒压力折算的请求内 PVT 会话。三个 toolbox 只创建一次，(P,T) 相同的
     * 物性直接从本次请求缓存返回；不影响已有 flowSession 调用方。
     */
    public record FlowProperties(double volumeFactor, double density, double viscosity) {}
    public java.util.function.BiFunction<Double, Double, FlowProperties> flowPropertiesSession(
            GasViscosityCurveRequest base, String token, String cookie, String processEnv) {
        validateRange(base);
        var headers = forwardedHeaders(token, cookie, processEnv);
        var cache = new java.util.HashMap<String, FlowProperties>();
        long[] ids = {0, 0, 0};
        return (pressure, temperature) -> {
            synchronized (cache) {
                String key = pressure + ":" + temperature;
                if (cache.containsKey(key)) return cache.get(key);
                if (ids[0] == 0) ids[0] = createToolbox(VOLUME_FACTOR_ALGORITHM, base.projectId(), headers);
                if (ids[1] == 0) ids[1] = createToolbox(DENSITY_ALGORITHM, base.projectId(), headers);
                if (ids[2] == 0) ids[2] = createToolbox(ALGORITHM, base.projectId(), headers);
                var input = buildInput(base, pressure, VOLUME_FACTOR_ALGORITHM, temperature);
                double bg = extractCurveValue(calculateAndGetResult(ids[0], input, headers), List.of("volumeFactor", "gasVolumeFactor"), "天然气体积系数");
                input = buildInput(base, pressure, DENSITY_ALGORITHM, temperature);
                double density = extractCurveValue(calculateAndGetResult(ids[1], input, headers), List.of("density"), "天然气密度");
                input = buildInput(base, pressure, ALGORITHM, temperature);
                double viscosity = extractViscosity(calculateAndGetResult(ids[2], input, headers));
                if (!Double.isFinite(bg) || bg <= 0 || !Double.isFinite(density) || density <= 0 || !Double.isFinite(viscosity) || viscosity <= 0)
                    throw new BusinessException(502, "天然气PVT物性无效");
                var result = new FlowProperties(bg, density, viscosity);
                cache.put(key, result);
                return result;
            }
        };
    }
    public java.util.function.BiFunction<Double, Double, FlowGas> flowSession(
            GasViscosityCurveRequest base, String token, String cookie, String processEnv) {
        validateRange(base);
        var headers = forwardedHeaders(token, cookie, processEnv);
        var cache = new java.util.HashMap<String, FlowGas>();
        long[] ids = {0, 0};
        return (pressure, temperature) -> {
            synchronized (cache) {
                String key = pressure + ":" + temperature;
                if (cache.containsKey(key)) return cache.get(key);
                if (ids[0] == 0) ids[0] = createToolbox(DENSITY_ALGORITHM, base.projectId(), headers);
                if (ids[1] == 0) ids[1] = createToolbox(ALGORITHM, base.projectId(), headers);
                var input = buildInput(base, pressure, DENSITY_ALGORITHM, temperature);
                double density = extractCurveValue(calculateAndGetResult(ids[0], input, headers), List.of("density"), "天然气密度");
                input = buildInput(base, pressure, ALGORITHM, temperature);
                double viscosity = extractViscosity(calculateAndGetResult(ids[1], input, headers));
                if (!Double.isFinite(density) || density <= 0 || !Double.isFinite(viscosity) || viscosity <= 0)
                    throw new BusinessException(502, "天然气物性无效");
                var result = new FlowGas(density, viscosity);
                cache.put(key, result);
                return result;
            }
        };
    }

    private final OriginalPlatformClient originalPlatformClient;
    private final ObjectMapper objectMapper;

    public GasPvtService(
            OriginalPlatformClient originalPlatformClient,
            ObjectMapper objectMapper
    ) {
        this.originalPlatformClient = originalPlatformClient;
        this.objectMapper = objectMapper;
    }

    public GasViscosityCurveResponse calculateViscosityCurve(
            GasViscosityCurveRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        // 曲线 4 只使用 GasPVT_Viscosity，一个压力点得到一个 viscosity。
        validateRange(request);
        Map<String, String> headers = forwardedHeaders(token, cookie, processEnv);

        JsonNode created = originalPlatformClient.post(
                "/api/toolbox",
                Map.of(
                        "algorithm", ALGORITHM,
                        "projectId", request.projectId()
                ),
                JsonNode.class,
                headers
        );
        long toolboxId = extractToolboxId(created);

        int pointCount = (int) Math.floor(
                (request.pressureEnd() - request.pressureStart()) / request.pressureStep() + 1e-9
        ) + 1;
        if (pointCount > MAX_POINT_COUNT) {
            throw new BusinessException(400, "压力点数量不能超过 " + MAX_POINT_COUNT);
        }

        List<GasViscosityPoint> points = new ArrayList<>(pointCount);
        // 压力点逐个执行是因为原 toolbox 是“计算后再读取结果”的有状态接口。
        for (int index = 0; index < pointCount; index++) {
            double pressure = request.pressureStart() + index * request.pressureStep();
            double calculationPressure = Math.abs(pressure) < 1e-12
                    ? ZERO_PRESSURE_CALCULATION_EPSILON_MPA
                    : pressure;
            Map<String, Object> input = buildInput(
                    request, calculationPressure, ALGORITHM, request.temperature()
            );

            originalPlatformClient.post(
                    "/api/toolbox/calc",
                    Map.of(
                            "id", toolboxId,
                            "input", writeJson(input)
                    ),
                    JsonNode.class,
                    headers
            );

            JsonNode result = originalPlatformClient.get(
                    "/api/toolbox/" + toolboxId,
                    JsonNode.class,
                    headers
            );
            double viscosity = extractViscosity(result);
            points.add(new GasViscosityPoint(pressure, request.temperature(), viscosity));
        }

        return new GasViscosityCurveResponse(toolboxId, List.copyOf(points));
    }

    public GasCurveOneResponse calculateCurveOne(
            GasViscosityCurveRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        // 曲线 1 同时展示 Z 和 m(p)，必须分别走两套原平台算法流程。
        validateRange(request);
        Map<String, String> headers = forwardedHeaders(token, cookie, processEnv);
        long deviationFactorToolboxId = createToolbox(
                DEVIATION_FACTOR_ALGORITHM,
                request.projectId(),
                headers
        );
        long pseudoPressureToolboxId = createToolbox(
                PSEUDO_PRESSURE_ALGORITHM,
                request.projectId(),
                headers
        );

        int pointCount = calculatePointCount(request);
        List<GasCurveOnePoint> points = new ArrayList<>(pointCount);
        for (int index = 0; index < pointCount; index++) {
            double pressure = request.pressureStart() + index * request.pressureStep();
            double calculationPressure = Math.abs(pressure) < 1e-12
                    ? ZERO_PRESSURE_CALCULATION_EPSILON_MPA
                    : pressure;
            JsonNode deviationFactorResult = calculateAndGetResult(
                    deviationFactorToolboxId,
                    buildInput(
                            request,
                            calculationPressure,
                            DEVIATION_FACTOR_ALGORITHM,
                            request.temperature()
                    ),
                    headers
            );
            double deviationFactor = extractCurveValue(
                    deviationFactorResult,
                    List.of(
                            "gasDeviationFactor",
                            "naturalGasDeviationFactor",
                            "deviationFactor",
                            "zFactor",
                            "z"
                    ),
                    "天然气偏差系数"
            );

            JsonNode pseudoPressureResult = calculateAndGetResult(
                    pseudoPressureToolboxId,
                    buildInput(
                            request,
                            calculationPressure,
                            PSEUDO_PRESSURE_ALGORITHM,
                            request.temperature()
                    ),
                    headers
            );
            double pseudoPressure = extractCurveValue(
                    pseudoPressureResult,
                    List.of(
                            "outPressure",
                            "gasPseudoPressure",
                            "naturalGasPseudoPressure",
                            "pseudoPressureResult",
                            "pseudoPressure"
                    ),
                    "气体拟压力"
            );
            points.add(new GasCurveOnePoint(
                    pressure,
                    request.temperature(),
                    deviationFactor,
                    pseudoPressure
            ));
        }

        return new GasCurveOneResponse(
                deviationFactorToolboxId,
                pseudoPressureToolboxId,
                List.copyOf(points)
        );
    }

    public GasCurveTwoResponse calculateCurveTwo(
            GasViscosityCurveRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        // 曲线 2：GasPVT_VolumeFactor 对应体积系数，GasPVT_Density 对应密度。
        validateRange(request);
        Map<String, String> headers = forwardedHeaders(token, cookie, processEnv);
        long volumeFactorToolboxId = createToolbox(
                VOLUME_FACTOR_ALGORITHM,
                request.projectId(),
                headers
        );
        long densityToolboxId = createToolbox(
                DENSITY_ALGORITHM,
                request.projectId(),
                headers
        );

        int pointCount = calculatePointCount(request);
        List<GasCurveTwoPoint> points = new ArrayList<>(pointCount);
        for (int index = 0; index < pointCount; index++) {
            double pressure = request.pressureStart() + index * request.pressureStep();
            double calculationPressure = Math.abs(pressure) < 1e-12
                    ? ZERO_PRESSURE_CALCULATION_EPSILON_MPA
                    : pressure;
            JsonNode volumeFactorResult = calculateAndGetResult(
                    volumeFactorToolboxId,
                    buildInput(
                            request,
                            calculationPressure,
                            VOLUME_FACTOR_ALGORITHM,
                            request.temperature()
                    ),
                    headers
            );
            double volumeFactor = extractCurveValue(
                    volumeFactorResult,
                    List.of("volumeFactor"),
                    "天然气体积系数"
            );

            JsonNode densityResult = calculateAndGetResult(
                    densityToolboxId,
                    buildInput(
                            request,
                            calculationPressure,
                            DENSITY_ALGORITHM,
                            request.temperature()
                    ),
                    headers
            );
            double density = extractCurveValue(
                    densityResult,
                    List.of("density"),
                    "天然气密度"
            );

            points.add(new GasCurveTwoPoint(
                    pressure,
                    request.temperature(),
                    volumeFactor,
                    density
            ));
        }

        return new GasCurveTwoResponse(
                volumeFactorToolboxId,
                densityToolboxId,
                List.copyOf(points)
        );
    }

    public GasCurveThreeResponse calculateCurveThree(
            GasViscosityCurveRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        // 曲线 3 只需要 GasPVT_Compressibility。
        validateRange(request);
        Map<String, String> headers = forwardedHeaders(token, cookie, processEnv);
        long toolboxId = createToolbox(
                COMPRESSIBILITY_ALGORITHM,
                request.projectId(),
                headers
        );

        int pointCount = calculatePointCount(request);
        List<GasCurveThreePoint> points = new ArrayList<>(pointCount);
        for (int index = 0; index < pointCount; index++) {
            double pressure = request.pressureStart() + index * request.pressureStep();
            Map<String, Object> input = buildInput(
                    request, pressure, COMPRESSIBILITY_ALGORITHM, request.temperature()
            );
            JsonNode result = calculateAndGetResult(toolboxId, input, headers);
            double compressibility = extractCurveValue(
                    result,
                    List.of("compressibility"),
                    "天然气压缩系数"
            );
            points.add(new GasCurveThreePoint(
                    pressure,
                    request.temperature(),
                    compressibility
            ));
        }

        return new GasCurveThreeResponse(toolboxId, List.copyOf(points));
    }

    private int calculatePointCount(GasViscosityCurveRequest request) {
        int pointCount = (int) Math.floor(
                (request.pressureEnd() - request.pressureStart()) / request.pressureStep() + 1e-9
        ) + 1;
        if (pointCount > MAX_POINT_COUNT) {
            throw new BusinessException(400, "压力点数量不能超过 " + MAX_POINT_COUNT);
        }
        return pointCount;
    }

    private long createToolbox(
            String algorithm,
            long projectId,
            Map<String, String> headers
    ) {
        // 只在一组曲线开始时创建一次，返回值 id 会用于该组全部压力点。
        JsonNode created = originalPlatformClient.post(
                "/api/toolbox",
                Map.of("algorithm", algorithm, "projectId", projectId),
                JsonNode.class,
                headers
        );
        return extractToolboxId(created);
    }

    private JsonNode calculateAndGetResult(
            long toolboxId,
            Map<String, Object> input,
            Map<String, String> headers
    ) {
        // 原平台的 input 字段要求是 JSON 字符串，而不是直接嵌套的 JSON 对象。
        originalPlatformClient.post(
                "/api/toolbox/calc",
                Map.of("id", toolboxId, "input", writeJson(input)),
                JsonNode.class,
                headers
        );
        return originalPlatformClient.get(
                "/api/toolbox/" + toolboxId,
                JsonNode.class,
                headers
        );
    }

    private void validateRange(GasViscosityCurveRequest request) {
        if (request.pressureEnd() < request.pressureStart()) {
            throw new BusinessException(400, "pressureEnd 不能小于 pressureStart");
        }
        if (request.gasType() < 0 || request.gasType() > 2) {
            throw new BusinessException(400, "gasType 只能是 0、1、2");
        }
        if (request.modificationMethod() < 0 || request.modificationMethod() > 1) {
            throw new BusinessException(400, "modificationMethod 只能是 0、1");
        }
        if (request.deviationFactorMethod() < 0 || request.deviationFactorMethod() > 2) {
            throw new BusinessException(400, "deviationFactorMethod 只能是 0、1、2");
        }
        if (request.viscosityMethod() < 0 || request.viscosityMethod() > 2) {
            throw new BusinessException(400, "viscosityMethod 只能是 0、1、2");
        }
    }

    private Map<String, String> forwardedHeaders(
            String token,
            String cookie,
            String processEnv
    ) {
        // 将前端登录态继续传给原平台，否则 toolbox 接口可能返回未授权。
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("token", token);
        headers.put(HttpHeaders.COOKIE, cookie);
        headers.put("Process-Env", processEnv == null || processEnv.isBlank() ? "prod" : processEnv);
        return headers;
    }

    private Map<String, Object> buildInput(
            GasViscosityCurveRequest request,
            double pressureMpa,
            String algorithm,
            double temperatureC
    ) {
        /*
         * 这里集中维护“本系统字段 -> 原平台字段”的映射。
         * 当前页面使用 MPa、℃ 和百分数；原平台 toolbox 使用 Pa、K 和 0～1 摩尔分数。
         * 各 toolbox 的输入结构并不相同，只发送该算法登记的字段，避免范围校验把
         * 无关字段当成当前算法输入。
         */
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("gasType", request.gasType());
        input.put("specificGravity", request.specificGravity());
        input.put("co2MoleFraction", request.co2MoleFraction() * FRACTION_PER_PERCENT);
        input.put("n2MoleFraction", request.n2MoleFraction() * FRACTION_PER_PERCENT);
        input.put("h2SMoleFraction", request.h2SMoleFraction() * FRACTION_PER_PERCENT);
        input.put("pressure", pressureMpa * PA_PER_MPA);
        input.put("temperature", temperatureC + KELVIN_OFFSET);
        input.put("modificationMethod", request.modificationMethod());

        if (!COMPRESSIBILITY_ALGORITHM.equals(algorithm)) {
            input.put("deviationFactorMethod", request.deviationFactorMethod());
        }
        if (ALGORITHM.equals(algorithm) || PSEUDO_PRESSURE_ALGORITHM.equals(algorithm)) {
            input.put("viscosityMethod", request.viscosityMethod());
        }
        if (DENSITY_ALGORITHM.equals(algorithm) || PSEUDO_PRESSURE_ALGORITHM.equals(algorithm)) {
            input.put("viscosity", 0d);
        }
        return input;
    }

    private long extractToolboxId(JsonNode source) {
        JsonNode id = findField(source, "id");
        if (id == null || !id.canConvertToLong()) {
            throw new BusinessException(502, "创建工具箱后未返回有效 id");
        }
        return id.longValue();
    }

    private double extractViscosity(JsonNode source) {
        JsonNode value = findViscosityField(source);
        if (value == null || !value.isNumber()) {
            throw new BusinessException(502, "工具箱结果中未找到天然气粘度");
        }
        return value.doubleValue();
    }

    private JsonNode findField(JsonNode node, String targetName) {
        JsonNode parsed = parseTextNode(node);
        if (parsed == null) {
            return null;
        }
        if (parsed.isObject()) {
            JsonNode direct = parsed.get(targetName);
            if (direct != null && !direct.isNull()) {
                return direct;
            }
            for (JsonNode child : parsed) {
                JsonNode found = findField(child, targetName);
                if (found != null) {
                    return found;
                }
            }
        } else if (parsed.isArray()) {
            for (JsonNode child : parsed) {
                JsonNode found = findField(child, targetName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private JsonNode findViscosityField(JsonNode node) {
        JsonNode parsed = parseTextNode(node);
        if (parsed == null) {
            return null;
        }
        if (parsed.isObject()) {
            for (String key : List.of("gasViscosity", "naturalGasViscosity", "viscosity")) {
                JsonNode direct = parsed.get(key);
                if (direct != null && !direct.isNull()) {
                    return direct;
                }
            }
            for (Map.Entry<String, JsonNode> field : parsed.properties()) {
                String normalized = field.getKey().toLowerCase();
                if (normalized.contains("viscosity") && !normalized.contains("method")
                        && field.getValue().isNumber()) {
                    return field.getValue();
                }
            }
            for (JsonNode child : parsed) {
                JsonNode found = findViscosityField(child);
                if (found != null) {
                    return found;
                }
            }
        } else if (parsed.isArray()) {
            for (JsonNode child : parsed) {
                JsonNode found = findViscosityField(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private double extractCurveValue(
            JsonNode source,
            List<String> candidateNames,
            String resultName
    ) {
        JsonNode value = findResultNumericField(source, candidateNames);
        if (!isNumericValue(value)) {
            throw new BusinessException(502, "工具箱结果中未找到" + resultName);
        }
        return numericValue(value);
    }

    private JsonNode findResultNumericField(JsonNode node, List<String> candidateNames) {
        /*
         * 原平台不同算法的响应层级并不完全一致，有的值在 output，有的会再包一层 data，
         * 甚至某些节点本身是 JSON 字符串。因此这里递归寻找候选字段，而不依赖固定路径。
         * 搜索时跳过 input/request/parameter，避免把请求参数误当成计算结果。
         */
        JsonNode parsed = parseTextNode(node);
        if (parsed == null) {
            return null;
        }
        if (parsed.isObject()) {
            for (String resultContainer : List.of(
                    "result",
                    "output",
                    "outputs",
                    "resultData",
                    "data"
            )) {
                JsonNode child = parsed.get(resultContainer);
                if (child != null) {
                    JsonNode found = findResultNumericField(child, candidateNames);
                    if (found != null) {
                        return found;
                    }
                }
            }

            for (String candidateName : candidateNames) {
                JsonNode direct = parsed.get(candidateName);
                if (isNumericValue(direct)) {
                    return direct;
                }
            }

            for (Map.Entry<String, JsonNode> field : parsed.properties()) {
                if (matchesCandidateName(field.getKey(), candidateNames) &&
                        isNumericValue(field.getValue())) {
                    return field.getValue();
                }
            }

            for (Map.Entry<String, JsonNode> field : parsed.properties()) {
                String normalized = field.getKey().toLowerCase();
                if (normalized.contains("input") ||
                        normalized.contains("request") ||
                        normalized.contains("parameter")) {
                    continue;
                }
                JsonNode found = findResultNumericField(field.getValue(), candidateNames);
                if (found != null) {
                    return found;
                }
            }
        } else if (parsed.isArray()) {
            for (JsonNode child : parsed) {
                JsonNode found = findResultNumericField(child, candidateNames);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean matchesCandidateName(String fieldName, List<String> candidateNames) {
        String normalizedFieldName = normalizeFieldName(fieldName);
        boolean expectsPseudoPressure = false;
        for (String candidateName : candidateNames) {
            String normalizedCandidateName = normalizeFieldName(candidateName);
            if (normalizedCandidateName.contains("pseudopressure")) {
                expectsPseudoPressure = true;
            }
            if (normalizedFieldName.equals(normalizedCandidateName)) {
                return true;
            }
            if (normalizedCandidateName.length() >= 6 &&
                    (normalizedFieldName.contains(normalizedCandidateName) ||
                            normalizedCandidateName.contains(normalizedFieldName))) {
                return true;
            }
        }
        return expectsPseudoPressure &&
                normalizedFieldName.contains("pseudo") &&
                normalizedFieldName.contains("pressure") &&
                !normalizedFieldName.contains("method");
    }

    private String normalizeFieldName(String fieldName) {
        return fieldName == null
                ? ""
                : fieldName.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private boolean isNumericValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isNumber()) {
            return true;
        }
        if (!value.isTextual()) {
            return false;
        }
        try {
            Double.parseDouble(value.textValue().trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private double numericValue(JsonNode value) {
        return value.isNumber()
                ? value.doubleValue()
                : Double.parseDouble(value.textValue().trim());
    }

    private JsonNode parseTextNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            return node;
        }
        String text = node.textValue().trim();
        if (!(text.startsWith("{") || text.startsWith("["))) {
            return node;
        }
        try {
            return objectMapper.readTree(text);
        } catch (JacksonException ignored) {
            return node;
        }
    }

    private String writeJson(Map<String, Object> input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JacksonException exception) {
            throw new BusinessException(500, "天然气 PVT 参数序列化失败");
        }
    }
}
