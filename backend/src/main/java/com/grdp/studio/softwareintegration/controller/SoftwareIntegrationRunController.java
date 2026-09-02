package com.grdp.studio.softwareintegration.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationCreateRunRequest;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunDetailResponse;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunSummaryResponse;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationRunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/software-integration")
public class SoftwareIntegrationRunController {
    private final SoftwareIntegrationRunService service;

    public SoftwareIntegrationRunController(SoftwareIntegrationRunService service) { this.service = service; }

    @PostMapping("/model-versions/{versionId}/runs")
    public ResponseEntity<ApiResponse<SoftwareIntegrationRunSummaryResponse>> create(
            @PathVariable @Min(1) long versionId,
            @Valid @RequestBody SoftwareIntegrationCreateRunRequest request) {
        return response(HttpStatus.CREATED, service.create(versionId, request));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ApiResponse<SoftwareIntegrationRunDetailResponse>> get(@PathVariable @Min(1) long runId) {
        return response(HttpStatus.OK, service.get(runId));
    }

    @GetMapping("/model-versions/{versionId}/runs")
    public ResponseEntity<ApiResponse<List<SoftwareIntegrationRunSummaryResponse>>> list(
            @PathVariable @Min(1) long versionId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return response(HttpStatus.OK, service.list(versionId, limit));
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<ApiResponse<SoftwareIntegrationRunSummaryResponse>> cancel(@PathVariable @Min(1) long runId) {
        SoftwareIntegrationRunService.CancelResult result = service.cancel(runId);
        return response(result.httpStatus(), result.run());
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(HttpStatus status, T data) {
        return ResponseEntity.status(status).body(new ApiResponse<>(status.value(), "success", data));
    }
}
