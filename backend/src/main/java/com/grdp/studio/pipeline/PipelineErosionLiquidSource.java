package com.grdp.studio.pipeline;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.integration.OriginalPlatformClient;
import com.grdp.studio.pvtstorage.dto.PvtRecordDetail;
import com.grdp.studio.pvtstorage.service.PvtStorageService;
import com.grdp.studio.waterpvt.dto.WaterPvtCurveRequest;
import com.grdp.studio.waterpvt.service.WaterPvtService;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BiFunction;

/** Water-only adapter: pipeline gas properties continue to use the saved composition EOS. */
@Service
public class PipelineErosionLiquidSource {
    public record Snapshot(Long pvtId, String pvtName, Long projectId, Long gasReservoirId, String wellName,
                           Double salinityMgL, Double originalPressureMpa,
                           Integer volumeFactorMethod, Integer compressibilityMethod,
                           String inputHash, String issue) {}

    private final PvtStorageService storage;
    private final RestClient platform;
    private final ObjectMapper json;

    public PipelineErosionLiquidSource(PvtStorageService storage, RestClient originalPlatformRestClient, ObjectMapper json) {
        this.storage = storage;
        this.platform = originalPlatformRestClient;
        this.json = json;
    }

    public List<Snapshot> list(long project, long reservoir, String well) {
        return storage.list(project, reservoir, well).stream()
                .map(item -> snapshot(project, reservoir, well, item.pvtId())).toList();
    }

    /** A missing or incomplete liquid source leaves erosion unevaluated, not the gas-flow calculation. */
    public Snapshot snapshot(long project, long reservoir, String well, Long pvtId) {
        String name = null, issue = null;
        Double salinity = null, originalPressure = null;
        Integer volumeMethod = null, compressionMethod = null;
        Object waterSettings = null;
        String normalizedWell = well == null ? "" : well.trim();
        if (pvtId == null || pvtId <= 0) issue = "请选择当前井的地层水 PVT 来源";
        else {
            try {
                // Match the existing selected-PVT contract, including its historical well mapping rules.
                require(storage.list(project, reservoir, normalizedWell).stream().anyMatch(item -> item.pvtId() == pvtId),
                        "所选地层水 PVT 不存在或不属于当前井");
                // No gas input is needed, including a water-only PVT record.
                PvtRecordDetail detail = storage.getDetail(pvtId, project, reservoir, normalizedWell);
                name = detail.record().pvtName();
                var input = detail.waterInput();
                if (input == null) throw new BusinessException(400, "所选 PVT 尚未保存地层水输入");
                salinity = input.salinity();
                originalPressure = input.formationPressure();
                require(salinity != null && Double.isFinite(salinity) && salinity >= 0, "所选 PVT 的地层水矿化度无效");
                require(originalPressure != null && Double.isFinite(originalPressure) && originalPressure > 0,
                        "所选 PVT 的原始地层压力必须为正数");
                String raw = detail.settings() == null ? null : detail.settings().get("water");
                Map<String, Object> settings = Map.of();
                if (raw != null && !raw.isBlank()) {
                    waterSettings = raw;
                    try { settings = json.readValue(raw, Map.class); }
                    catch (RuntimeException ex) { throw new BusinessException(400, "所选 PVT 的地层水计算方法格式无效"); }
                    require(settings != null, "所选 PVT 的地层水计算方法格式无效");
                }
                waterSettings = settings;
                volumeMethod = method(settings.get("volumeFactorMethod"), List.of("McCain方法", "Standing方法"));
                compressionMethod = method(settings.get("compressibilityMethod"), List.of("Meehan方法", "Dodson-Standing方法"));
            } catch (BusinessException ex) {
                issue = ex.getMessage();
            } catch (RuntimeException ex) {
                // Missing legacy PVT tables must not prevent unrelated gas-flow results from being saved.
                issue = "读取液相 PVT 来源失败，请检查数据管理中的地层水数据";
            }
        }
        Map<String, Object> state = new TreeMap<>();
        state.put("version", "pipeline-erosion-water-source-v1");
        state.put("projectId", project); state.put("gasReservoirId", reservoir); state.put("wellName", normalizedWell);
        state.put("pvtId", pvtId); state.put("salinityMgL", salinity); state.put("originalPressureMpa", originalPressure);
        state.put("volumeFactorMethod", volumeMethod); state.put("compressibilityMethod", compressionMethod);
        // Store invalid settings in the hash too, so fixing a previously unusable source invalidates its result.
        if (issue != null) state.put("invalidWaterSettings", ordered(waterSettings));
        state.put("issue", issue);
        return new Snapshot(pvtId, name, project, reservoir, normalizedWell, salinity, originalPressure,
                volumeMethod, compressionMethod, hash(state), issue);
    }

    public boolean verifySnapshot(Snapshot source) {
        if (source == null || source.projectId() == null || source.gasReservoirId() == null || source.inputHash() == null) return false;
        try {
            var current = snapshot(source.projectId(), source.gasReservoirId(), source.wellName(), source.pvtId());
            return Objects.equals(current.inputHash(), source.inputHash());
        } catch (RuntimeException ex) { return false; }
    }

    /** Open once per batch. Each exact (MPa absolute, degrees C) state is evaluated and cached once. */
    public BiFunction<Double, Double, Double> open(Snapshot source, String token, String cookie, String environment) {
        if (source == null || source.issue() != null) {
            String issue = source == null ? "请选择当前井的地层水 PVT 来源" : source.issue();
            return (p, t) -> { throw new BusinessException(400, issue); };
        }
        var client = new OriginalPlatformClient(platform.mutate()
                .defaultHeader("x-project-id", String.valueOf(source.projectId())).build());
        var water = new WaterPvtService(client, json);
        var base = new WaterPvtCurveRequest(source.projectId(), source.salinityMgL(), source.originalPressureMpa(),
                20d, 1d, 1d, 1d, source.volumeFactorMethod(), source.compressibilityMethod());
        var session = water.flowSession(base, false, token, cookie, environment);
        BusinessException[] unavailable = {null};
        return (pressure, temperature) -> {
            require(pressure != null && Double.isFinite(pressure) && pressure > 0
                    && temperature != null && Double.isFinite(temperature) && temperature > -273.15,
                    "地层水评价需要有效正绝压和高于绝对零度的温度");
            synchronized (session) {
                if (unavailable[0] != null) throw unavailable[0];
                try { return session.apply(pressure, temperature).density(); }
                catch (ResourceAccessException ex) {
                    unavailable[0] = new BusinessException(502, "地层水 PVT 服务连接失败，本批冲蚀暂未评价");
                    throw unavailable[0];
                } catch (BusinessException ex) {
                    // Authentication/server outages affect this session; a state-domain failure affects only that point.
                    String message = Objects.toString(ex.getMessage(), "");
                    if (message.matches(".*原平台接口调用失败，HTTP (401|403|5[0-9]{2}).*")) unavailable[0] = ex;
                    throw ex;
                }
            }
        };
    }

    private static int method(Object raw, List<String> methods) {
        if (raw == null || String.valueOf(raw).isBlank()) return 0;
        int value = methods.indexOf(String.valueOf(raw));
        require(value >= 0, "所选 PVT 的地层水计算方法无法识别：" + raw);
        return value;
    }

    private String hash(Map<String, Object> state) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(state).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static Object ordered(Object value) {
        if (!(value instanceof Map<?, ?> map)) return value;
        var sorted = new TreeMap<String, Object>();
        map.forEach((key, item) -> sorted.put(String.valueOf(key), ordered(item)));
        return sorted;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new BusinessException(400, message);
    }
}

@RestController
@RequestMapping("/pipeline-capacity/erosion")
class PipelineErosionLiquidSourceController {
    private final PipelineErosionLiquidSource sources;
    PipelineErosionLiquidSourceController(PipelineErosionLiquidSource sources) { this.sources = sources; }

    @GetMapping("/liquid-sources")
    public ApiResponse<List<PipelineErosionLiquidSource.Snapshot>> list(@RequestParam long projectId,
            @RequestParam long gasReservoirId, @RequestParam String wellName) {
        return ApiResponse.success(sources.list(projectId, gasReservoirId, wellName));
    }

    @GetMapping("/liquid-source")
    public ApiResponse<PipelineErosionLiquidSource.Snapshot> source(@RequestParam long projectId,
            @RequestParam long gasReservoirId, @RequestParam String wellName, @RequestParam(required = false) Long pvtId) {
        return ApiResponse.success(sources.snapshot(projectId, gasReservoirId, wellName, pvtId));
    }
}
