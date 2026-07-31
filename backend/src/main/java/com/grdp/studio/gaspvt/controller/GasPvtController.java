package com.grdp.studio.gaspvt.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.gaspvt.dto.GasCurveOneResponse;
import com.grdp.studio.gaspvt.dto.GasCurveTwoResponse;
import com.grdp.studio.gaspvt.dto.GasCurveThreeResponse;
import com.grdp.studio.gaspvt.dto.GasViscosityCurveRequest;
import com.grdp.studio.gaspvt.dto.GasViscosityCurveResponse;
import com.grdp.studio.gaspvt.service.GasPvtService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 天然气 PVT 曲线接口。
 *
 * <p>Controller 只负责接收前端参数、转交登录态请求头，并把 Service 的结果包装成统一响应。
 * 实际的压力循环、原平台 toolbox 调用及结果字段解析都在 {@link GasPvtService} 中完成。</p>
 *
 * <p>曲线对应关系：
 * curve-one = 偏差系数 + 拟压力；
 * curve-two = 体积系数 + 密度；
 * curve-three = 压缩系数；
 * viscosity-curve = 黏度。</p>
 */
@RestController
@RequestMapping("/pvt/gas")
public class GasPvtController {

    private final GasPvtService gasPvtService;

    public GasPvtController(GasPvtService gasPvtService) {
        this.gasPvtService = gasPvtService;
    }

    /** 计算曲线 4：天然气黏度随压力变化的数据。 */
    @PostMapping("/viscosity-curve")
    public ApiResponse<GasViscosityCurveResponse> calculateViscosityCurve(
            @Valid @RequestBody GasViscosityCurveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv
    ) {
        return ApiResponse.success(
                gasPvtService.calculateViscosityCurve(request, token, cookie, processEnv)
        );
    }

    /** 计算曲线 1：一次请求返回偏差系数和拟压力两组指标。 */
    @PostMapping("/curve-one")
    public ApiResponse<GasCurveOneResponse> calculateCurveOne(
            @Valid @RequestBody GasViscosityCurveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv
    ) {
        return ApiResponse.success(
                gasPvtService.calculateCurveOne(request, token, cookie, processEnv)
        );
    }

    /** 计算曲线 2：一次请求返回体积系数和密度两组指标。 */
    @PostMapping("/curve-two")
    public ApiResponse<GasCurveTwoResponse> calculateCurveTwo(
            @Valid @RequestBody GasViscosityCurveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv
    ) {
        return ApiResponse.success(
                gasPvtService.calculateCurveTwo(request, token, cookie, processEnv)
        );
    }

    /** 计算曲线 3：天然气压缩系数随压力变化的数据。 */
    @PostMapping("/curve-three")
    public ApiResponse<GasCurveThreeResponse> calculateCurveThree(
            @Valid @RequestBody GasViscosityCurveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv
    ) {
        return ApiResponse.success(
                gasPvtService.calculateCurveThree(request, token, cookie, processEnv)
        );
    }
}
