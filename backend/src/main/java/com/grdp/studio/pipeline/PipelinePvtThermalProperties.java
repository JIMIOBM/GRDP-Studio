package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Thermal properties of the well's compositional PVT, evaluated at the requested state. */
@Service
public class PipelinePvtThermalProperties {
    public record Detail(Long pvtId, String pvtName, String sourceRevision, Double pressureMpa,
                         Double temperatureC, Double densityKgM3, Double viscosityMpaS,
                         Double cpJkgK, Double gasConductivityWmK, Map<String, String> sources,
                         String issue, PipelineStandingViscosity.Result viscosityCalculation) {
        public Detail { sources = sources == null ? Map.of() : Map.copyOf(sources); }
        public Detail(Long pvtId, String pvtName, String sourceRevision, Double pressureMpa,
                      Double temperatureC, Double densityKgM3, Double viscosityMpaS, Double cpJkgK,
                      Double gasConductivityWmK, Map<String, String> sources, String issue) {
            this(pvtId,pvtName,sourceRevision,pressureMpa,temperatureC,densityKgM3,viscosityMpaS,
                    cpJkgK,gasConductivityWmK,sources,issue,null);
        }
    }
    private final PipelinePvtComposition compositions;
    private final PipelineGasProperties gas;
    public PipelinePvtThermalProperties(PipelinePvtComposition compositions, PipelineGasProperties gas) {
        this.compositions = compositions;
        this.gas = gas;
    }
    @Transactional(readOnly = true)
    public Detail detail(long projectId, long gasReservoirId, String wellName, Long pvtId,
                         Double pressureMpa, Double temperatureC) {
        if (pvtId == null || pvtId <= 0) {
            // Validate well scope even if the caller has not loaded the PVT model yet.
            compositions.current(projectId, gasReservoirId, wellName);
            return new Detail(pvtId, null, null, pressureMpa, temperatureC, null, null, null,
                    null, Map.of(), "请先在 PVT模型中保存当前井的完整气体组成，并加载该模型");
        }
        var source = compositions.detail(projectId, gasReservoirId, wellName, pvtId);
        List<String> issues = new ArrayList<>();
        Map<String, String> sources = new LinkedHashMap<>();
        String origin = "组分 PVT 的 " + source.method() + " 模型，按当前温压计算";
        sources.put("densityKgM3", origin);
        sources.put("cpJkgK", origin);
        sources.put("viscosityMpaS", PipelineStandingViscosity.SOURCE + "，按当前组成和温压计算");
        sources.put("gasConductivityWmK", "每管段手动填写或本地导入，单位 W/(m·K)");
        boolean validPressure = positive(pressureMpa);
        boolean validTemperature = temperatureC != null && Double.isFinite(temperatureC) && temperatureC > -273.15;
        if (!validPressure) issues.add("请填写有效的物性压力（绝对压力，MPa，须大于 0）");
        if (!validTemperature) issues.add("请填写有效的物性温度（℃，须高于绝对零度）");
        Double density = null, cp = null;
        PipelineStandingViscosity.Result viscosity = null;
        try {
            compositions.requireComplete(source);
            if (validPressure && validTemperature) {
                var prepared = gas.prepare(source.method(), source.composition());
                var state = prepared.at(pressureMpa, temperatureC);
                if (!positive(state.densityKgM3()) || !positive(state.cpJkgK()))
                    throw new BusinessException(400, "状态方程未返回有效的密度或定压比热容");
                density = state.densityKgM3();
                cp = state.cpJkgK();
                viscosity = prepared.standingViscosity(pressureMpa, temperatureC);
            }
        } catch (BusinessException exception) {
            issues.add("组分 PVT 物性不可用：" + exception.getMessage());
        }
        return new Detail(source.pvtId(), source.pvtName(), "pvt-thermal:v3:" + PipelineStandingViscosity.VERSION + ":" + source.revision(), pressureMpa,
                temperatureC, density, viscosity == null ? null : viscosity.viscosityMpaS(), cp, null,
                sources, String.join("；", issues), viscosity);
    }
    public void requireComplete(Detail detail) {
        if (detail == null) throw new BusinessException(400, "请读取当前井 PVT 模型的气体物性");
        if (detail.issue() != null && !detail.issue().isBlank()) throw new BusinessException(400, detail.issue());
        if (!positive(detail.densityKgM3()) || !positive(detail.viscosityMpaS())
                || !positive(detail.cpJkgK())) {
            throw new BusinessException(400, "组分 PVT 缺少有效的气体密度、动力黏度或定压比热容");
        }
    }
    private static boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }
}
