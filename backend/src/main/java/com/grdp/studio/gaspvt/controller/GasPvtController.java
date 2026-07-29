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

@RestController
@RequestMapping("/pvt/gas")
public class GasPvtController {

    private final GasPvtService gasPvtService;

    public GasPvtController(GasPvtService gasPvtService) {
        this.gasPvtService = gasPvtService;
    }

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
