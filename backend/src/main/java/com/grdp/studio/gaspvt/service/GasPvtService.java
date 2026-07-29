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

@Service
public class GasPvtService {

    private static final String ALGORITHM = "GasPVT_Viscosity";
    private static final String DEVIATION_FACTOR_ALGORITHM = "GasPVT_DeviationFactor";
    private static final String PSEUDO_PRESSURE_ALGORITHM = "GasPVT_PseudoPressure";
    private static final String DENSITY_ALGORITHM = "GasPVT_Density";
    private static final String VOLUME_FACTOR_ALGORITHM = "GasPVT_VolumeFactor";
    private static final String COMPRESSIBILITY_ALGORITHM = "GasPVT_Compressibility";
    private static final int MAX_POINT_COUNT = 500;
    private static final double ZERO_PRESSURE_CALCULATION_EPSILON_MPA = 1e-6;

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
        for (int index = 0; index < pointCount; index++) {
            double pressure = request.pressureStart() + index * request.pressureStep();
            double calculationPressure = Math.abs(pressure) < 1e-12
                    ? ZERO_PRESSURE_CALCULATION_EPSILON_MPA
                    : pressure;
            Map<String, Object> input = buildInput(request, calculationPressure);

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
            Map<String, Object> input = buildInput(request, calculationPressure);

            JsonNode deviationFactorResult = calculateAndGetResult(
                    deviationFactorToolboxId,
                    input,
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
                    input,
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
            Map<String, Object> input = buildInput(request, calculationPressure);

            JsonNode volumeFactorResult = calculateAndGetResult(
                    volumeFactorToolboxId,
                    input,
                    headers
            );
            double volumeFactor = extractCurveValue(
                    volumeFactorResult,
                    List.of("volumeFactor"),
                    "天然气体积系数"
            );

            JsonNode densityResult = calculateAndGetResult(
                    densityToolboxId,
                    input,
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
            Map<String, Object> input = buildInput(request, pressure);
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
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("token", token);
        headers.put(HttpHeaders.COOKIE, cookie);
        headers.put("Process-Env", processEnv == null || processEnv.isBlank() ? "prod" : processEnv);
        return headers;
    }

    private Map<String, Object> buildInput(
            GasViscosityCurveRequest request,
            double pressure
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("gasType", request.gasType());
        input.put("specificGravity", request.specificGravity());
        input.put("co2MoleFraction", request.co2MoleFraction());
        input.put("n2MoleFraction", request.n2MoleFraction());
        input.put("h2SMoleFraction", request.h2SMoleFraction());
        input.put("pressure", pressure);
        input.put("temperature", request.temperature());
        input.put("originalPressure", 40);
        input.put("pseudoPressure", 4e-8);
        input.put("regularizedPseudoPressure", 40);
        input.put("apparentPressure", 40);
        input.put("modificationMethod", request.modificationMethod());
        input.put("deviationFactorMethod", request.deviationFactorMethod());
        input.put("viscosityMethod", request.viscosityMethod());
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
