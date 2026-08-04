package com.grdp.studio.rockpvt.service;

import com.grdp.studio.rockpvt.dto.RockCurveRequest;
import jakarta.validation.Valid;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.rockpvt.dto.RockCurveOnePoint;
import com.grdp.studio.rockpvt.dto.RockCurveOneResponse;
import com.grdp.studio.rockpvt.dto.RockCurveTwoPoint;
import com.grdp.studio.rockpvt.dto.RockCurveTwoResponse;
import com.grdp.studio.integration.OriginalPlatformClient;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RockPvtService {

    private static final String CEMENTED_SANDSTONE_ALGORITHM = "RockPVT_CementedSandstoneCompressibilityFactor";
    private static final String CARBONATE_ALGORITHM = "RockPVT_CarbonateRockCompressibilityFactor";
    private static final int MAX_POINT_COUNT = 500;

    private final OriginalPlatformClient originalPlatformClient;
    private final ObjectMapper objectMapper;

    public RockPvtService(
            OriginalPlatformClient originalPlatformClient,
            ObjectMapper objectMapper
    ) {
        this.originalPlatformClient = originalPlatformClient;
        this.objectMapper = objectMapper;
    }

    public RockCurveOneResponse calculateCurveOne(
            @Valid RockCurveRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        validateRange(request);
        Map<String, String> headers = forwardedHeaders(
                token,
                cookie,
                processEnv,
                request.projectId()
        );

        long toolboxId = createToolbox(CEMENTED_SANDSTONE_ALGORITHM, request.projectId(), headers);

        int pointCount = calculatePointCount(request);
        List<RockCurveOnePoint> points = new ArrayList<>(pointCount);
        for (int index = 0; index < pointCount; index++) {

            double currentPorosity =
                    request.porosityStart()
                            + index * request.porosityStep();


            Map<String, Object> input =
                    Map.of(
                            "rockType",
                            0,
                            "porosity",
                            currentPorosity
                    );


            JsonNode result =
                    calculateAndGetResult(
                            toolboxId,
                            input,
                            headers
                    );


            double compressibilityFactor =
                    extractCompressibility(result);


            points.add(
                    new RockCurveOnePoint(
                            currentPorosity,
                            compressibilityFactor
                    )
            );
        }

        return new RockCurveOneResponse(toolboxId, List.copyOf(points));
    }

    public RockCurveTwoResponse calculateCurveTwo(
            RockCurveRequest request,
            String token,
            String cookie,
            String processEnv
    ) {
        validateRange(request);
        Map<String, String> headers = forwardedHeaders(
                token,
                cookie,
                processEnv,
                request.projectId()
        );

        long toolboxId = createToolbox(CARBONATE_ALGORITHM, request.projectId(), headers);

        int pointCount = calculatePointCount(request);
        List<RockCurveTwoPoint> points = new ArrayList<>(pointCount);
        for (int index = 0; index < pointCount; index++) {
            double currentPorosity = request.porosityStart() + index * request.porosityStep();

            Map<String, Object> input = Map.of("porosity", currentPorosity);
            JsonNode result = calculateAndGetResult(toolboxId, input, headers);
            double compressibilityFactor = extractCompressibility(result);

            points.add(new RockCurveTwoPoint(currentPorosity, compressibilityFactor));
        }

        return new RockCurveTwoResponse(toolboxId, List.copyOf(points));
    }

    // ========== 三步法核心 ==========


    private long createToolbox(String algorithm, long projectId, Map<String, String> headers) {
        JsonNode created = originalPlatformClient.post(
                "/api/toolbox",
                Map.of("algorithm", algorithm, "projectId", projectId),
                JsonNode.class,
                headers
        );
        return extractToolboxId(created);
    }

    private JsonNode calculateAndGetResult(long toolboxId, Map<String, Object> input, Map<String, String> headers) {
        originalPlatformClient.post(
                "/api/toolbox/calc",
                Map.of("id", toolboxId, "input", writeJson(input)),
                JsonNode.class,
                headers
        );
        return originalPlatformClient.get("/api/toolbox/" + toolboxId, JsonNode.class, headers);
    }

    // ========== 辅助方法 ==========

    private void validateRange(RockCurveRequest request) {

        if (request.porosityStart() < 0
                || request.porosityEnd() > 100) {

            throw new BusinessException(
                    400,
                    "孔隙度范围必须在0-100之间"
            );
        }


        if (request.porosityEnd()
                < request.porosityStart()) {

            throw new BusinessException(
                    400,
                    "porosityEnd不能小于porosityStart"
            );
        }


        if (request.porosityStep() <= 0) {

            throw new BusinessException(
                    400,
                    "porosityStep必须大于0"
            );
        }
    }

    private int calculatePointCount(RockCurveRequest request) {
        int count = (int) Math.floor((request.porosityEnd() - request.porosityStart()) / request.porosityStep() + 1e-9) + 1;
        if (count > MAX_POINT_COUNT) throw new BusinessException(400, "孔隙度点数不能超过" + MAX_POINT_COUNT);
        return count;
    }

    private double extractCompressibility(JsonNode source) {
        for (String name : List.of("compressibilityFactor", "compressibility")) {
            JsonNode value = findField(source, name);
            if (value != null && value.isNumber()) return value.doubleValue();
        }
        throw new BusinessException(502, "工具箱结果中未找到压缩系数");
    }

    private long extractToolboxId(JsonNode source) {
        JsonNode id = findField(source, "id");
        if (id == null || !id.canConvertToLong()) throw new BusinessException(502, "创建工具箱后未返回有效 id");
        return id.longValue();
    }

    private JsonNode findField(JsonNode node, String targetName) {
        JsonNode parsed = parseTextNode(node);
        if (parsed == null) return null;
        if (parsed.isObject()) {
            JsonNode direct = parsed.get(targetName);
            if (direct != null && !direct.isNull()) return direct;
            for (JsonNode child : parsed) { JsonNode found = findField(child, targetName); if (found != null) return found; }
        } else if (parsed.isArray()) {
            for (JsonNode child : parsed) { JsonNode found = findField(child, targetName); if (found != null) return found; }
        }
        return null;
    }

    private JsonNode parseTextNode(JsonNode node) {
        if (node.isTextual()) try { return objectMapper.readTree(node.textValue()); } catch (JacksonException e) { return null; }
        return node;
    }

    private String writeJson(Object obj) { try { return objectMapper.writeValueAsString(obj); } catch (JacksonException e) { throw new BusinessException(500, e.getMessage()); } }

    private Map<String, String> forwardedHeaders(
            String token,
            String cookie,
            String processEnv,
            Long projectId
    ) {
        Map<String, String> headers = new LinkedHashMap<>();

        if (token != null && !token.isBlank()) {
            headers.put("token", token);
        }

        if (cookie != null && !cookie.isBlank()) {
            headers.put(HttpHeaders.COOKIE, cookie);
        }

        headers.put(
                "Process-Env",
                processEnv == null || processEnv.isBlank()
                        ? "prod"
                        : processEnv
        );

        if (projectId != null) {
            headers.put(
                    "x-project-id",
                    String.valueOf(projectId)
            );
        }

        return headers;
    }
    }