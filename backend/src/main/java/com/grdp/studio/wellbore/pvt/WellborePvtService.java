package com.grdp.studio.wellbore.pvt;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.gaspvt.dto.GasViscosityCurveRequest;
import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.integration.OriginalPlatformClient;
import com.grdp.studio.pvtstorage.dto.PvtRecordDetail;
import com.grdp.studio.pvtstorage.dto.PvtRecordSummary;
import com.grdp.studio.pvtstorage.service.PvtStorageService;
import com.grdp.studio.waterpvt.dto.WaterPvtCurveRequest;
import com.grdp.studio.waterpvt.service.WaterPvtService;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** 井筒专用入口：按所选PVT输入和方法，以当地MPa、℃调用现有气水PVT服务。 */
@Service
public class WellborePvtService {
    public record Source(PvtRecordDetail detail, Map<String, Object> snapshot) {
        public long pvtId() { return detail.record().pvtId(); }
        public double specificGravity() { return positive(detail.gasInput().specificGravity(), "天然气相对密度"); }
    }
    public record Session(Source source,
                          BiFunction<Double, Double, GasPvtService.FlowProperties> gas,
                          BiFunction<Double, Double, WaterPvtService.FlowWater> water,
                          BiFunction<Double, Double, Double> z) {}

    private final PvtStorageService storage;
    private final RestClient platform;
    private final ObjectMapper json;

    public WellborePvtService(PvtStorageService storage, RestClient originalPlatformRestClient, ObjectMapper json) {
        this.storage = storage;
        this.platform = originalPlatformRestClient;
        this.json = json;
    }

    public Source first(Long projectId, Long reservoirId, String wellName) {
        validateScope(projectId, reservoirId, wellName);
        var first = storage.list(projectId, reservoirId, wellName).stream()
                .min(Comparator.comparingInt(PvtRecordSummary::pvtNo).thenComparingLong(PvtRecordSummary::pvtId))
                .orElseThrow(() -> new BusinessException(400, "当前井没有已保存的PVT性质，请先补齐第一条PVT"));
        return source(first, projectId, reservoirId, wellName);
    }

    /** 温度模型和压力折算使用用户在下拉列表中明确选择的PVT记录。 */
    public Source selected(Long pvtId, Long projectId, Long reservoirId, String wellName) {
        validateScope(projectId, reservoirId, wellName);
        if (pvtId == null || pvtId <= 0) {
            throw new BusinessException(400, "请选择当前井已保存的PVT性质");
        }
        var selected = storage.list(projectId, reservoirId, wellName).stream()
                .filter(item -> item.pvtId() == pvtId)
                .findFirst()
                .orElseThrow(() -> new BusinessException(400, "所选PVT性质不存在或不属于当前井"));
        return source(selected, projectId, reservoirId, wellName);
    }

    private void validateScope(Long projectId, Long reservoirId, String wellName) {
        if (projectId == null || projectId <= 0 || reservoirId == null || reservoirId <= 0
                || wellName == null || wellName.isBlank()) {
            throw new BusinessException(400, "井筒PVT需要当前项目、气藏和井名");
        }
    }

    private Source source(PvtRecordSummary record, Long projectId, Long reservoirId, String wellName) {
        var detail = storage.getDetail(record.pvtId(), projectId, reservoirId, wellName);
        if (detail == null || detail.gasInput() == null)
            throw new BusinessException(400, "所选PVT缺少天然气输入");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("projectId", projectId);
        snapshot.put("gasReservoirId", reservoirId);
        snapshot.put("wellName", wellName.trim());
        snapshot.put("pvtId", record.pvtId());
        snapshot.put("pvtNo", record.pvtNo());
        snapshot.put("pvtName", record.pvtName());
        snapshot.put("sourceType", record.sourceType());
        snapshot.put("gasInput", detail.gasInput());
        snapshot.put("waterInput", detail.waterInput());
        snapshot.put("settings", detail.settings());
        snapshot.put("pressureUnit", "MPa (absolute)");
        snapshot.put("temperatureUnit", "C");
        snapshot.put("compositionUnit", "mol%");
        snapshot.put("densityUnit", "kg/m3");
        snapshot.put("viscosityUnit", "mPa.s");
        snapshot.put("gasToolboxAuxiliaryInputs", Map.of("originalPressure", 40d, "pseudoPressure", 4e-8d,
                "regularizedPseudoPressure", 40d, "apparentPressure", 40d));
        var gasMethods = gasRequest(new Source(detail, snapshot), projectId, 1, 20);
        var waterSettings = settings(detail.settings(), "water");
        snapshot.put("resolvedMethods", Map.of("modificationMethod", gasMethods.modificationMethod(),
                "deviationFactorMethod", gasMethods.deviationFactorMethod(), "viscosityMethod", gasMethods.viscosityMethod(),
                "volumeFactorMethod", method(value(waterSettings, "volumeFactorMethod"), List.of("McCain方法", "Standing方法")),
                "compressibilityMethod", method(value(waterSettings, "compressibilityMethod"), List.of("Meehan方法", "Dodson-Standing方法"))));
        try {
            snapshot.put("inputHash", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(ordered(snapshot)).getBytes(StandardCharsets.UTF_8))));
        } catch (JacksonException | NoSuchAlgorithmException ex) {
            throw new BusinessException(500, "井筒PVT来源快照生成失败");
        }
        return new Source(detail, snapshot);
    }

    public Session open(Source source, Long projectId, String token, String cookie, String environment,
                        boolean needWaterViscosity) {
        // 独立客户端只为井筒补充工程请求头，不改变其他模块的PVT服务或客户端。
        var client = new OriginalPlatformClient(platform.mutate()
                .defaultHeader("x-project-id", String.valueOf(projectId)).build());
        var gas = new GasPvtService(client, json);
        var water = new WaterPvtService(client, json);
        var gasBase = gasRequest(source, projectId, 1, 20);
        var waterBase = waterRequest(source, projectId, 1, 20);
        String env = environment == null || environment.isBlank() ? "prod" : environment;
        var gasSession = gas.flowPropertiesSession(gasBase, token, cookie, env);
        var waterSession = water.flowSession(waterBase, needWaterViscosity, token, cookie, env);
        var zCache = new java.util.HashMap<String, Double>();
        return new Session(source, gasSession, waterSession, (p, t) -> zCache.computeIfAbsent(p + ":" + t,
                key -> gas.calculateCurveOne(gasRequest(source, projectId, p, t), token, cookie, env)
                        .items().getFirst().deviationFactor()));
    }

    public GasViscosityCurveRequest gasRequest(Source source, Long projectId, double p, double t) {
        state(p, t);
        var input = source.detail().gasInput();
        if (input.gasType() == null || input.gasType().isBlank())
            throw new BusinessException(400, "所选PVT缺少天然气类型");
        var settings = settings(source.detail().settings(), "gas");
        if (nonNegative(input.hydrogenSulfide(), "H2S") + nonNegative(input.carbonDioxide(), "CO2")
                + nonNegative(input.nitrogen(), "N2") > 100)
            throw new BusinessException(400, "所选PVT的非烃含量总和不能超过100%");
        return new GasViscosityCurveRequest(projectId,
                method(input.gasType(), List.of("干气", "湿气", "凝析气")), source.specificGravity(),
                nonNegative(input.hydrogenSulfide(), "H2S"), nonNegative(input.carbonDioxide(), "CO2"),
                nonNegative(input.nitrogen(), "N2"), t, p, p, 1d,
                method(value(settings, "gasCorrectionMethod"), List.of("Wichert-Aziz 修正方法", "Carr-Kobayashi-Burrous 修正方法")),
                method(value(settings, "deviationFactorMethod"), List.of("Dranchuk-Abu-Kassem 方法", "Dranchuk-Purvis-Robinson 方法", "Hall-Yarborough 方法")),
                method(value(settings, "viscosityMethod"), List.of("Lee-Gonzalez-Eakin 方法", "Carr-Kobayashi-Burrous 方法", "Sutton 方法")));
    }

    public WaterPvtCurveRequest waterRequest(Source source, Long projectId, double p, double t) {
        state(p, t);
        var input = source.detail().waterInput();
        if (input == null) throw new BusinessException(400, "所选PVT缺少地层水输入");
        var settings = settings(source.detail().settings(), "water");
        return new WaterPvtCurveRequest(projectId, nonNegative(input.salinity(), "地层水矿化度"),
                positive(input.formationPressure(), "原始地层压力"), t, p, p, 1d,
                method(value(settings, "volumeFactorMethod"), List.of("McCain方法", "Standing方法")),
                method(value(settings, "compressibilityMethod"), List.of("Meehan方法", "Dodson-Standing方法")));
    }

    private Map<String, Object> settings(Map<String, String> settings, String kind) {
        String raw = settings == null ? null : settings.get(kind);
        if (raw == null || raw.isBlank()) return Map.of();
        try { return json.readValue(raw, Map.class); }
        catch (JacksonException ex) { throw new BusinessException(400, "所选PVT计算设置格式无效"); }
    }
    private static String value(Map<String, Object> values, String key) {
        Object value = values.get(key); return value == null ? null : String.valueOf(value);
    }
    private static int method(String value, List<String> options) {
        // 与PVT性质页面的已有默认方法一致，缺省配置保留在来源快照中。
        if (value == null || value.isBlank()) return 0;
        int index = options.indexOf(value);
        if (index < 0) throw new BusinessException(400, "所选PVT计算方法无法识别：" + value);
        return index;
    }
    private static double positive(Double value, String label) {
        if (value == null || !Double.isFinite(value) || value <= 0)
            throw new BusinessException(400, "所选PVT的" + label + "必须为正数");
        return value;
    }
    private static double nonNegative(Double value, String label) {
        if (value == null || !Double.isFinite(value) || value < 0 || (label.matches("H2S|CO2|N2") && value > 100))
            throw new BusinessException(400, "所选PVT的" + label + "无效");
        return value;
    }
    public static void state(double p, double t) {
        if (!Double.isFinite(p) || p <= 0 || !Double.isFinite(t) || t <= -273.15)
            throw new BusinessException(400, "PVT评价需要有效正绝压和高于绝对零度的温度");
    }

    public void validateSnapshot(Source source, Long pvtId, Map<String, Object> snapshot) {
        if ((pvtId != null && pvtId != source.pvtId()) || (snapshot != null && snapshot.get("inputHash") != null
                && !source.snapshot().get("inputHash").equals(snapshot.get("inputHash")))) {
            throw new BusinessException(409, "所选PVT来源已变化，请重新读取并计算");
        }
    }

    private static Object ordered(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new java.util.TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), ordered(item)));
            return sorted;
        }
        return value;
    }
}
