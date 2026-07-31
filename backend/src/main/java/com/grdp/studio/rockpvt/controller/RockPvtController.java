package com.grdp.studio.rockpvt.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.rockpvt.dto.RockCurveOneResponse;
import com.grdp.studio.rockpvt.dto.RockCurveRequest;
import com.grdp.studio.rockpvt.dto.RockCurveTwoResponse;
import com.grdp.studio.rockpvt.service.RockPvtService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pvt/rock")
public class RockPvtController {

    private final RockPvtService rockPvtService;

    public RockPvtController(RockPvtService rockPvtService) {
        this.rockPvtService = rockPvtService;
    }

    @PostMapping("/curve-one")
    public ApiResponse<RockCurveOneResponse> calculateCurveOne(
            @Valid @RequestBody RockCurveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv
    ) {
        return ApiResponse.success(rockPvtService.calculateCurveOne(request, token, cookie, processEnv));
    }

    @PostMapping("/curve-two")
    public ApiResponse<RockCurveTwoResponse> calculateCurveTwo(
            @Valid @RequestBody RockCurveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv
    ) {
        return ApiResponse.success(rockPvtService.calculateCurveTwo(request, token, cookie, processEnv));
    }
}