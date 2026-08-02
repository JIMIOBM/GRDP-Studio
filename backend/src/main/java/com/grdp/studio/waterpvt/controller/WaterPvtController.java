package com.grdp.studio.waterpvt.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.waterpvt.dto.WaterCurveOneResponse;
import com.grdp.studio.waterpvt.dto.WaterCurveThreeResponse;
import com.grdp.studio.waterpvt.dto.WaterCurveTwoResponse;
import com.grdp.studio.waterpvt.dto.WaterPvtCurveRequest;
import com.grdp.studio.waterpvt.dto.WaterViscosityCurveResponse;
import com.grdp.studio.waterpvt.service.WaterPvtService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pvt/water")
public class WaterPvtController {

    private final WaterPvtService waterPvtService;

    public WaterPvtController(WaterPvtService waterPvtService) {
        this.waterPvtService = waterPvtService;
    }

    @PostMapping("/curve-one")
    public ApiResponse<WaterCurveOneResponse> calculateCurveOne(
            @Valid @RequestBody WaterPvtCurveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv
    ) {
        return ApiResponse.success(waterPvtService.calculateCurveOne(request, token, cookie, processEnv));
    }

    @PostMapping("/curve-two")
    public ApiResponse<WaterCurveTwoResponse> calculateCurveTwo(
            @Valid @RequestBody WaterPvtCurveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv
    ) {
        return ApiResponse.success(waterPvtService.calculateCurveTwo(request, token, cookie, processEnv));
    }

    @PostMapping("/curve-three")
    public ApiResponse<WaterCurveThreeResponse> calculateCurveThree(
            @Valid @RequestBody WaterPvtCurveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv
    ) {
        return ApiResponse.success(waterPvtService.calculateCurveThree(request, token, cookie, processEnv));
    }

    @PostMapping("/viscosity-curve")
    public ApiResponse<WaterViscosityCurveResponse> calculateViscosityCurve(
            @Valid @RequestBody WaterPvtCurveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv
    ) {
        return ApiResponse.success(
                waterPvtService.calculateViscosityCurve(request, token, cookie, processEnv)
        );
    }
}
