package com.grdp.studio.diagnostic.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.diagnostic.dto.DiagnosticCurveModels;
import com.grdp.studio.diagnostic.service.DiagnosticCurveService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/diagnostic-curve")
public class DiagnosticCurveController {

    private final DiagnosticCurveService service;

    public DiagnosticCurveController(
            DiagnosticCurveService service
    ) {
        this.service = service;
    }

    @PostMapping("/calculate")
    public ApiResponse<DiagnosticCurveModels.CalculateResponse> calculate(
            @Valid
            @RequestBody
            DiagnosticCurveModels.CalculateRequest request,

            @RequestHeader(
                    value = "token",
                    required = false
            )
            String token,

            @RequestHeader(
                    value = "Cookie",
                    required = false
            )
            String cookie,

            @RequestHeader(
                    value = "Process-Env",
                    required = false
            )
            String processEnv
    ) {

        return ApiResponse.success(
                service.calculate(
                        request,
                        token,
                        cookie,
                        processEnv
                )
        );
    }
}
