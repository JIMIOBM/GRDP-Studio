package com.grdp.studio.wellbore.pressure.service;

import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import com.grdp.studio.wellbore.pressure.method.PressureCalculator;
import org.springframework.stereotype.Service;

@Service
public class PressureConversionService {
    private final PvtPropertyProvider pvt;
    public PressureConversionService(PvtPropertyProvider pvt) { this.pvt = pvt; }
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
        // 只在计算开始时读取并校验当前井首条PVT，同时回填气体比重及边界水物性。
        // 井段迭代沿用原始算法的本地DAK/LGE计算，不能在内层循环逐点调用原平台。
        var session = pvt.open(request, token, cookie, environment);
        return new Calculation(PressureCalculator.calculate(request), session.gasSpecificGravity());
    }
}
