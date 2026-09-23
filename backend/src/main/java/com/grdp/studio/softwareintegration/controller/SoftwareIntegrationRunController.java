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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
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
import java.nio.charset.StandardCharsets;

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

    @GetMapping("/runs/{runId}/artifacts/{artifactId}/download")
    public ResponseEntity<Resource> downloadArtifact(@PathVariable @Min(1) long runId,
                                                     @PathVariable @Min(1) long artifactId) {
        var artifact = service.downloadArtifact(runId, artifactId);
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(artifact.contentType());
        } catch (IllegalArgumentException exception) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(artifact.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(artifact.name(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(artifact.path()));
    }

    @GetMapping("/runs/{runId}/artifacts/{artifactId}/range")
    public ResponseEntity<ResourceRegion> downloadArtifactRange(@PathVariable @Min(1) long runId,
                                                                 @PathVariable @Min(1) long artifactId,
                                                                 @RequestParam long offset,
                                                                 @RequestParam @Min(1) @Max(4 * 1024 * 1024) int length) {
        var artifact = service.downloadArtifactRange(runId, artifactId, offset, length);
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(artifact.contentType());
        } catch (IllegalArgumentException exception) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        var resource = new FileSystemResource(artifact.path());
        var region = new ResourceRegion(resource, artifact.offset(), artifact.length());
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(contentType)
                .contentLength(artifact.length())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes %d-%d/%d".formatted(artifact.offset(), artifact.offset() + artifact.length() - 1, artifact.sizeBytes()))
                .body(region);
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

    @PostMapping("/runs/{runId}/retry")
    public ResponseEntity<ApiResponse<SoftwareIntegrationRunSummaryResponse>> retry(@PathVariable @Min(1) long runId) {
        return response(HttpStatus.CREATED, service.retry(runId));
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(HttpStatus status, T data) {
        return ResponseEntity.status(status).body(new ApiResponse<>(status.value(), "success", data));
    }
}
