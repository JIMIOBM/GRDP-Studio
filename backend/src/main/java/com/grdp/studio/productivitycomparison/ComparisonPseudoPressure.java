package com.grdp.studio.productivitycomparison;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.integration.OriginalPlatformClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import static com.grdp.studio.productivitycomparison.ComparisonRepository.PvtSnapshot;

@Service
public class ComparisonPseudoPressure {
    private final OriginalPlatformClient client;
    private final ObjectMapper mapper;
    public ComparisonPseudoPressure(OriginalPlatformClient client, ObjectMapper mapper) {
        this.client = client; this.mapper = mapper;
    }
    public record Pair(double reservoir, double flowing) {}

    // Saved snapshots may contain the numeric enum or the original display label.
    static int index(String value, String... names) {
        if (value == null || value.isBlank()) throw new BusinessException(400, "记录缺少PVT计算方法");
        String text = value.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < names.length; i++) {
            if (text.equals(String.valueOf(i)) || text.contains(names[i].toLowerCase(Locale.ROOT))) return i;
        }
        throw new BusinessException(400, "无法识别记录的PVT计算方法：" + value);
    }
    static int gasType(String value) {
        if (value == null) throw new BusinessException(400, "记录缺少天然气类型");
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.equals("0") || text.contains("干") || text.equals("dry")) return 0;
        if (text.equals("1") || text.contains("湿") || text.equals("wet")) return 1;
        if (text.equals("2") || text.contains("凝析") || text.equals("condensate")) return 2;
        throw new BusinessException(400, "记录的天然气类型无法识别");
    }
    static void validate(PvtSnapshot pvt) {
        if (pvt == null) throw new BusinessException(400, "记录缺少PVT输入快照");
        gasType(pvt.gasType());
        range(pvt.specificGravity(), 0.5, 1, "天然气比重");
        range(pvt.temperature(), -50, 200, "温度");
        range(pvt.originalPressure(), 0, 200, "原始地层压力");
        range(pvt.hydrogenSulfide(), 0, 100, "H₂S摩尔百分含量");
        range(pvt.carbonDioxide(), 0, 100, "CO₂摩尔百分含量");
        range(pvt.nitrogen(), 0, 100, "N₂摩尔百分含量");
        if (pvt.hydrogenSulfide() + pvt.carbonDioxide() + pvt.nitrogen() > 100)
            throw new BusinessException(400, "记录中非烃气体摩尔百分含量之和超过100%");
        index(requiredMethod(pvt.modificationMethod(), "非烃气体修正方法"), "Wichert", "Carr");
        index(requiredMethod(pvt.deviationFactorMethod(), "偏差系数计算方法"), "Kassem", "Purvis", "Hall");
        index(requiredMethod(pvt.viscosityMethod(), "天然气黏度计算方法"), "Lee", "Carr", "Sutton");
    }
    private static String requiredMethod(String value, String name) {
        if (value == null || value.isBlank()) throw new BusinessException(400, "记录缺少PVT计算方法：" + name);
        return value;
    }
    private static void range(Double value, double min, double max, String name) {
        if (value == null || !Double.isFinite(value) || value < min || value > max)
            throw new BusinessException(400, "记录的" + name + "缺失或超出拟压力接口范围");
    }

    public Pair calculate(long projectId, PvtSnapshot pvt, double pressure, Map<String, String> headers) {
        range(pressure, 0.1, 200, "计算地层压力");
        var calculator = calculator(projectId, pvt, headers);
        return new Pair(calculator.applyAsDouble(pressure), calculator.applyAsDouble(0.1));
    }

    /** Request-local, sequential toolbox session; never shared across wells or simultaneous requests. */
    public java.util.function.DoubleUnaryOperator calculator(long projectId, PvtSnapshot pvt, Map<String, String> headers) {
        validate(pvt);
        Map<String, String> scopedHeaders = new LinkedHashMap<>(headers);
        scopedHeaders.put("x-project-id", String.valueOf(projectId));
        JsonNode created = unwrap(client.post("/api/toolbox", Map.of("algorithm", "GasPVT_PseudoPressure",
                "projectId", projectId), JsonNode.class, scopedHeaders));
        if (created == null || !created.path("id").canConvertToLong() || created.path("id").asLong() <= 0)
            throw new BusinessException(502, "创建天然气拟压力工具箱失败");
        long id = created.path("id").asLong();
        // A toolbox is stateful: finish calc + result retrieval before submitting its next pressure.
        return pressure -> {
            range(pressure, 0.1, 200, "计算压力");
            return point(id, pvt, pressure, scopedHeaders);
        };
    }

    private double point(long id, PvtSnapshot pvt, double pressure, Map<String, String> headers) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("gasType", gasType(pvt.gasType())); input.put("specificGravity", pvt.specificGravity());
        input.put("h2SMoleFraction", pvt.hydrogenSulfide()); input.put("co2MoleFraction", pvt.carbonDioxide());
        input.put("n2MoleFraction", pvt.nitrogen()); input.put("temperature", pvt.temperature());
        // Legacy single-point toolbox contract: MPa, Celsius, percentage; not Pa/K/fractions.
        input.put("pressure", pressure); input.put("originalPressure", pvt.originalPressure());
        input.put("pseudoPressure", 4e-8); input.put("regularizedPseudoPressure", pvt.originalPressure());
        input.put("apparentPressure", pvt.originalPressure());
        input.put("modificationMethod", index(pvt.modificationMethod(), "Wichert", "Carr"));
        input.put("deviationFactorMethod", index(pvt.deviationFactorMethod(), "Kassem", "Purvis", "Hall"));
        input.put("viscosityMethod", index(pvt.viscosityMethod(), "Lee", "Carr", "Sutton"));
        client.post("/api/toolbox/calc", Map.of("id", id, "input", mapper.writeValueAsString(input)), JsonNode.class, headers);
        for (int attempt = 0; attempt < 100; attempt++) {
            JsonNode result = unwrap(client.get("/api/toolbox/" + id, JsonNode.class, headers));
            if (result != null) {
                JsonNode actual = result.path("input");
                if (actual.isTextual()) actual = mapper.readTree(actual.asText());
                // Reject stale output belonging to a previous pressure/temperature.
                if (actual.path("pressure").isNumber() && actual.path("temperature").isNumber()
                        && Math.abs(actual.path("pressure").asDouble() - pressure) < 1e-8
                        && Math.abs(actual.path("temperature").asDouble() - pvt.temperature()) < 1e-8) {
                    JsonNode output = result.path("output");
                    if (output.isTextual()) output = mapper.readTree(output.asText());
                    for (String field : new String[]{"outPressure", "pseudoPressure", "gasPseudoPressure"}) {
                        JsonNode value = output.path(field);
                        if (value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() > 0)
                            return value.asDouble();
                    }
                }
            }
            try { Thread.sleep(50); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new BusinessException(502, "拟压力计算已中断");
            }
        }
        throw new BusinessException(502, "拟压力结果未更新或结果无效，请重试");
    }
    private JsonNode unwrap(JsonNode node) {
        if (node != null && node.has("code") && node.path("code").asInt() != 0 && node.path("code").asInt() != 200)
            throw new BusinessException(502, "拟压力接口返回失败：" + node.path("msg").asText());
        return node != null && node.has("data") ? node.get("data") : node;
    }
}
