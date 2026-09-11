package com.grdp.studio.wellbore.pressure.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.gaspvt.dto.GasViscosityCurveRequest;
import com.grdp.studio.gaspvt.service.GasPvtService;
import com.grdp.studio.pvtstorage.dto.PvtRecordDetail;
import com.grdp.studio.pvtstorage.service.PvtStorageService;
import com.grdp.studio.waterpvt.dto.WaterPvtCurveRequest;
import com.grdp.studio.waterpvt.service.WaterPvtService;
import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import com.grdp.studio.wellbore.pressure.method.PressureCalculator;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** 将已保存PVT方案适配为井筒HB/MB需要的局部(P,T)物性，不复制任何PVT经验公式。 */
@Service
public class PvtPropertyProvider {
    public record Session(double gasSpecificGravity, BiFunction<Double, Double, PressureCalculator.Properties> properties) {}

    private final PvtStorageService storage;
    private final GasPvtService gas;
    private final WaterPvtService water;
    private final ObjectMapper objectMapper;

    public PvtPropertyProvider(PvtStorageService storage, GasPvtService gas, WaterPvtService water, ObjectMapper objectMapper) {
        this.storage = storage;
        this.gas = gas;
        this.water = water;
        this.objectMapper = objectMapper;
    }

    public Session open(PressureCalculateRequest request, String token, String cookie, String processEnv) {
        if (request.pvtId == null
                || request.projectId == null
                || request.gasReservoirId == null
                || request.wellName == null
                || request.wellName.isBlank()) {
            throw new BusinessException(400, "压力折算需要当前井已保存的PVT方案");
        }
        PvtRecordDetail detail = storage.getDetail(request.pvtId, request.projectId, request.gasReservoirId, request.wellName);
        var gasInput = detail.gasInput();
        var waterInput = detail.waterInput();
        if (gasInput == null
                || waterInput == null
                || gasInput.specificGravity() == null
                || waterInput.salinity() == null
                || waterInput.formationPressure() == null) {
            throw new BusinessException(400, "所选PVT缺少天然气或地层水输入");
        }
        requirePositive(gasInput.specificGravity(), "天然气比重");
        requireNonNegative(waterInput.salinity(), "地层水矿化度");
        requirePositive(waterInput.formationPressure(), "地层水原始压力");
        Map<String, Object> gasSettings = settings(detail.settings(), "gas");
        Map<String, Object> waterSettings = settings(detail.settings(), "water");
        var gasBase = new GasViscosityCurveRequest(request.projectId,
                method(gasInput.gasType(), List.of("干气", "湿气", "凝析气")), gasInput.specificGravity(),
                required(gasInput.hydrogenSulfide(), "H2S"), required(gasInput.carbonDioxide(), "CO2"), required(gasInput.nitrogen(), "N2"),
                20d, 0d, 0d, 1d,
                method(string(gasSettings, "gasCorrectionMethod"), List.of("Wichert-Aziz 修正方法", "Carr-Kobayashi-Burrous 修正方法")),
                method(string(gasSettings, "deviationFactorMethod"), List.of(
                        "Dranchuk-Abu-Kassem 方法",
                        "Dranchuk-Purvis-Robinson 方法",
                        "Hall-Yarborough 方法"
                )),
                method(string(gasSettings, "viscosityMethod"), List.of("Lee-Gonzalez-Eakin 方法", "Carr-Kobayashi-Burrous 方法", "Sutton 方法")));
        var waterBase = new WaterPvtCurveRequest(request.projectId, waterInput.salinity(), waterInput.formationPressure(), 20d, 0d, 0d, 1d,
                method(string(waterSettings, "volumeFactorMethod"), List.of("McCain方法", "Standing方法")),
                method(string(waterSettings, "compressibilityMethod"), List.of("Meehan方法", "Dodson-Standing方法")));
        var gasSession = gas.flowPropertiesSession(gasBase, token, cookie, processEnv);
        var waterSession = water.flowSession(waterBase, true, token, cookie, processEnv);
        return new Session(gasInput.specificGravity(), (pressure, temperature) -> {
            var gp = gasSession.apply(pressure, temperature);
            var wp = waterSession.apply(pressure, temperature);
            return new PressureCalculator.Properties(gp.volumeFactor(), gp.density(), gp.viscosity(), wp.density(), wp.viscosity());
        });
    }

    private Map<String, Object> settings(Map<String, String> source, String kind) {
        String json = source == null ? null : source.get(kind);
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (JacksonException ex) { throw new BusinessException(400, "PVT" + kind + "计算设置格式无效"); }
    }
    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }
    private static int method(String value, List<String> options) {
        if (value == null || value.isBlank()) return 0;
        int index = options.indexOf(value); if (index < 0) throw new BusinessException(400, "无法识别PVT计算方法：" + value); return index;
    }
    private static Double required(Double value, String label) {
        if (value == null || !Double.isFinite(value) || value < 0) {
            throw new BusinessException(400, label + "必须为非负有效数值");
        }
        return value;
    }

    private static void requirePositive(Double value, String label) {
        if (value == null || !Double.isFinite(value) || value <= 0) {
            throw new BusinessException(400, label + "必须为正数");
        }
    }

    private static void requireNonNegative(Double value, String label) {
        if (value == null || !Double.isFinite(value) || value < 0) {
            throw new BusinessException(400, label + "必须为非负数");
        }
    }
}
