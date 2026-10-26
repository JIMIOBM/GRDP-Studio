package com.grdp.studio.wellbore.pressure.service;

import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import com.grdp.studio.wellbore.pressure.method.PressureCalculator;
import org.springframework.stereotype.Service;

@Service
public class PressureConversionService {
    private final PvtPropertyProvider pvt;
    private final WellboreThermalProfileService thermalProfiles;
    public PressureConversionService(PvtPropertyProvider pvt, WellboreThermalProfileService thermalProfiles) {
        this.pvt = pvt;
        this.thermalProfiles = thermalProfiles;
    }
    public record Calculation(
            PressureCalculator.Result result,
            double gasSpecificGravity
    ) {}

    public PressureCalculator.Result calculate(
            PressureCalculateRequest request,
            String token,
            String cookie,
            String environment
    ) {
        return calculateDetailed(request, token, cookie, environment).result();
    }

    public Calculation calculateDetailed(
            PressureCalculateRequest request,
            String token,
            String cookie,
            String environment
    ) {
        // 只在计算开始时读取并校验用户所选PVT，同时回填气体比重及边界水物性。
        // 井段迭代沿用原始算法的本地DAK/LGE计算，不能在内层循环逐点调用原平台。
        var session = pvt.open(request, token, cookie, environment);
        if ("production".equals(request.operationMode) && "MODEL".equalsIgnoreCase(request.thermalSource)) {
            var profile = thermalProfiles.calculate(request);
            return new Calculation(
                    PressureCalculator.calculate(request, profile.depth(), profile.temperature(),
                            (pressure, temperature) -> com.grdp.studio.wellbore.pressure.method.PressureCorrelations.originalProperties(
                                    pressure, temperature + 273.15, request.gammaG, request.rhoL, request.muL)),
                    session.gasSpecificGravity()
            );
        }
        // 注气和旧版未声明温度来源的调用继续使用边界锚定线性温度，避免改变既有接口语义。
        return new Calculation(PressureCalculator.calculate(request), session.gasSpecificGravity());
    }
}
