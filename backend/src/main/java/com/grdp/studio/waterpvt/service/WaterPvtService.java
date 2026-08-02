package com.grdp.studio.waterpvt.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.integration.OriginalPlatformClient;
import com.grdp.studio.waterpvt.dto.WaterCurveOnePoint;
import com.grdp.studio.waterpvt.dto.WaterCurveOneResponse;
import com.grdp.studio.waterpvt.dto.WaterCurveThreePoint;
import com.grdp.studio.waterpvt.dto.WaterCurveThreeResponse;
import com.grdp.studio.waterpvt.dto.WaterCurveTwoPoint;
import com.grdp.studio.waterpvt.dto.WaterCurveTwoResponse;
import com.grdp.studio.waterpvt.dto.WaterPvtCurveRequest;
import com.grdp.studio.waterpvt.dto.WaterViscosityCurveResponse;
import com.grdp.studio.waterpvt.dto.WaterViscosityPoint;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WaterPvtService {

    private static final String GAS_SOLUBILITY_ALGORITHM = "WaterPVT_GasSolubilityInWater";
    private static final String VOLUME_FACTOR_ALGORITHM = "WaterPVT_VolumeFactor";
    private static final String DENSITY_ALGORITHM = "WaterPVT_Density";
    private static final String COMPRESSIBILITY_ALGORITHM =
            "WaterPVT_IsothermalCompressionCoefficient";
    private static final String VISCOSITY_ALGORITHM = "WaterPVT_Viscosity";
    private static final int MAX_POINT_COUNT = 500;
    private static final double ZERO_PRESSURE_CALCULATION_EPSILON_MPA = 1e-6;

    private final OriginalPlatformClient originalPlatformClient;
    private final ObjectMapper objectMapper;

    public WaterPvtService(
            OriginalPlatformClient originalPlatformClient,
            ObjectMapper objectMapper
    ) {
        this.originalPlatformClient = originalPlatformClient;
        this.objectMapper = objectMapper;
    }

    public WaterCurveOneResponse calculateCurveOne(
            WaterPvtCurveRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        validateRequest(request);
        Map<String, String> headers = forwardedHeaders(token, cookie, processEnv);
        long toolboxId = createToolbox(GAS_SOLUBILITY_ALGORITHM, request.projectId(), headers);
        int pointCount = calculatePointCount(request);
        List<WaterCurveOnePoint> points = new ArrayList<>(pointCount);

        for (int index = 0; index < pointCount; index++) {
            double pressure = pressureAt(request, index);
            JsonNode result = calculateAndGetResult(
                    toolboxId,
                    buildInput(request, calculationPressure(pressure)),
                    headers
            );
            double value = extractCurveValue(
                    result,
                    List.of("gasSolubilityInWater", "gasSolubility", "solubility"),
                    "天然气在水中的溶解度"
            );
            points.add(new WaterCurveOnePoint(
                    pressure,
                    request.temperature(),
                    request.salinity(),
                    value
            ));
        }
        return new WaterCurveOneResponse(toolboxId, List.copyOf(points));
    }

    public WaterCurveTwoResponse calculateCurveTwo(
            WaterPvtCurveRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        validateRequest(request);
        Map<String, String> headers = forwardedHeaders(token, cookie, processEnv);
        long volumeFactorToolboxId =
                createToolbox(VOLUME_FACTOR_ALGORITHM, request.projectId(), headers);
        long densityToolboxId = createToolbox(DENSITY_ALGORITHM, request.projectId(), headers);
        int pointCount = calculatePointCount(request);
        List<WaterCurveTwoPoint> points = new ArrayList<>(pointCount);

        for (int index = 0; index < pointCount; index++) {
            double pressure = pressureAt(request, index);
            Map<String, Object> input = buildInput(request, calculationPressure(pressure));
            double volumeFactor = extractCurveValue(
                    calculateAndGetResult(volumeFactorToolboxId, input, headers),
                    List.of("waterVolumeFactor", "volumeFactor"),
                    "地层水体积系数"
            );
            double density = extractCurveValue(
                    calculateAndGetResult(densityToolboxId, input, headers),
                    List.of("waterDensity", "density"),
                    "地层水密度"
            );
            points.add(new WaterCurveTwoPoint(
                    pressure,
                    request.temperature(),
                    request.salinity(),
                    volumeFactor,
                    density
            ));
        }
        return new WaterCurveTwoResponse(
                volumeFactorToolboxId,
                densityToolboxId,
                List.copyOf(points)
        );
    }

    public WaterCurveThreeResponse calculateCurveThree(
            WaterPvtCurveRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        validateRequest(request);
        Map<String, String> headers = forwardedHeaders(token, cookie, processEnv);
        long toolboxId = createToolbox(COMPRESSIBILITY_ALGORITHM, request.projectId(), headers);
        int pointCount = calculatePointCount(request);
        List<WaterCurveThreePoint> points = new ArrayList<>(pointCount);

        for (int index = 0; index < pointCount; index++) {
            double pressure = pressureAt(request, index);
            double value = extractCurveValue(
                    calculateAndGetResult(
                            toolboxId,
                            buildInput(request, calculationPressure(pressure)),
                            headers
                    ),
                    List.of(
                            "isothermalCompressionCoefficient",
                            "waterCompressibility",
                            "compressibility"
                    ),
                    "地层水等温压缩系数"
            );
            points.add(new WaterCurveThreePoint(
                    pressure,
                    request.temperature(),
                    request.salinity(),
                    value
            ));
        }
        return new WaterCurveThreeResponse(toolboxId, List.copyOf(points));
    }

    public WaterViscosityCurveResponse calculateViscosityCurve(
            WaterPvtCurveRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        validateRequest(request);
        Map<String, String> headers = forwardedHeaders(token, cookie, processEnv);
        long toolboxId = createToolbox(VISCOSITY_ALGORITHM, request.projectId(), headers);
        int pointCount = calculatePointCount(request);
        List<WaterViscosityPoint> points = new ArrayList<>(pointCount);

        for (int index = 0; index < pointCount; index++) {
            double pressure = pressureAt(request, index);
            double value = extractCurveValue(
                    calculateAndGetResult(
                            toolboxId,
                            buildInput(request, calculationPressure(pressure)),
                            headers
                    ),
                    List.of("waterViscosity", "viscosity"),
                    "地层水粘度"
            );
            points.add(new WaterViscosityPoint(
                    pressure,
                    request.temperature(),
                    request.salinity(),
                    value
            ));
        }
        return new WaterViscosityCurveResponse(toolboxId, List.copyOf(points));
    }

    private void validateRequest(WaterPvtCurveRequest request) {
        if (request.pressureEnd() < request.pressureStart()) {
            throw new BusinessException(400, "pressureEnd 不能小于 pressureStart");
        }
        if (request.volumeFactorMethod() < 0 || request.volumeFactorMethod() > 1) {
            throw new BusinessException(400, "volumeFactorMethod 只能是 0、1");
        }
        if (request.compressibilityMethod() < 0 || request.compressibilityMethod() > 1) {
            throw new BusinessException(400, "compressibilityMethod 只能是 0、1");
        }
        calculatePointCount(request);
    }

    private int calculatePointCount(WaterPvtCurveRequest request) {
        int pointCount = (int) Math.floor(
                (request.pressureEnd() - request.pressureStart()) / request.pressureStep() + 1e-9
        ) + 1;
        if (pointCount > MAX_POINT_COUNT) {
            throw new BusinessException(400, "压力点数量不能超过 " + MAX_POINT_COUNT);
        }
        return pointCount;
    }

    private double pressureAt(WaterPvtCurveRequest request, int index) {
        return request.pressureStart() + index * request.pressureStep();
    }

    private double calculationPressure(double pressure) {
        return Math.abs(pressure) < 1e-12
                ? ZERO_PRESSURE_CALCULATION_EPSILON_MPA
                : pressure;
    }

    private long createToolbox(
            String algorithm,
            long projectId,
            Map<String, String> headers
    ) {
        JsonNode created = originalPlatformClient.post(
                "/api/toolbox",
                Map.of("algorithm", algorithm, "projectId", projectId),
                JsonNode.class,
                headers
        );
        JsonNode id = findField(created, List.of("id"));
        if (!isNumericValue(id)) {
            throw new BusinessException(502, "创建地层水工具箱后未返回有效 id");
        }
        return (long) numericValue(id);
    }

    private JsonNode calculateAndGetResult(
            long toolboxId,
            Map<String, Object> input,
            Map<String, String> headers
    ) {
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

    private Map<String, Object> buildInput(WaterPvtCurveRequest request, double pressure) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("pressure", pressure);
        input.put("originalPressure", request.originalPressure());
        input.put("temperature", request.temperature());
        input.put("salinity", request.salinity());
        input.put("volumeFactorMethod", request.volumeFactorMethod());
        input.put("compressibilityMethod", request.compressibilityMethod());
        return input;
    }

    private double extractCurveValue(
            JsonNode source,
            List<String> candidateNames,
            String resultName
    ) {
        JsonNode value = findField(source, candidateNames);
        if (!isNumericValue(value)) {
            throw new BusinessException(502, "工具箱结果中未找到" + resultName);
        }
        return numericValue(value);
    }

    private JsonNode findField(JsonNode node, List<String> candidateNames) {
        JsonNode parsed = parseTextNode(node);
        if (parsed == null) {
            return null;
        }
        if (parsed.isObject()) {
            for (String container : List.of("result", "output", "outputs", "resultData", "data")) {
                JsonNode child = parsed.get(container);
                if (child != null) {
                    JsonNode found = findField(child, candidateNames);
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
                if (matchesCandidate(field.getKey(), candidateNames)
                        && isNumericValue(field.getValue())) {
                    return field.getValue();
                }
            }
            for (Map.Entry<String, JsonNode> field : parsed.properties()) {
                String normalized = normalizeFieldName(field.getKey());
                if (normalized.contains("input")
                        || normalized.contains("request")
                        || normalized.contains("parameter")
                        || normalized.contains("method")) {
                    continue;
                }
                JsonNode found = findField(field.getValue(), candidateNames);
                if (found != null) {
                    return found;
                }
            }
        } else if (parsed.isArray()) {
            for (JsonNode child : parsed) {
                JsonNode found = findField(child, candidateNames);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean matchesCandidate(String fieldName, List<String> candidateNames) {
        String normalizedFieldName = normalizeFieldName(fieldName);
        for (String candidateName : candidateNames) {
            String normalizedCandidate = normalizeFieldName(candidateName);
            if (normalizedFieldName.equals(normalizedCandidate)
                    || (normalizedCandidate.length() >= 6
                    && (normalizedFieldName.contains(normalizedCandidate)
                    || normalizedCandidate.contains(normalizedFieldName)))) {
                return true;
            }
        }
        return false;
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

    private Map<String, String> forwardedHeaders(
            String token,
            String cookie,
            String processEnv
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("token", token);
        headers.put(HttpHeaders.COOKIE, cookie);
        headers.put("Process-Env", processEnv == null || processEnv.isBlank() ? "prod" : processEnv);
        return headers;
    }

    private String writeJson(Map<String, Object> input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JacksonException exception) {
            throw new BusinessException(500, "地层水 PVT 参数序列化失败");
        }
    }
}
