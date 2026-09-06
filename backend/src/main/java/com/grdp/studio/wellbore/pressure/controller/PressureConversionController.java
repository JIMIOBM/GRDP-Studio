package com.grdp.studio.wellbore.pressure.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import com.grdp.studio.wellbore.pressure.dto.PressureCalculateResponse;
import com.grdp.studio.wellbore.pressure.dto.PressureRecordDetail;
import com.grdp.studio.wellbore.pressure.dto.PressureRecordSummary;
import com.grdp.studio.wellbore.pressure.dto.PressureSaveRequest;
import com.grdp.studio.wellbore.pressure.service.PressureConversionService;
import com.grdp.studio.wellbore.pressure.service.PressureStorageService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/wellbore/pressure")
public class PressureConversionController {
    private final PressureConversionService service;
    private final PressureStorageService storage;

    public PressureConversionController(
            PressureConversionService service,
            PressureStorageService storage
    ) {
        this.service = service;
        this.storage = storage;
    }

    @PostMapping("/calculate")
    public ApiResponse<PressureCalculateResponse> calculate(
            @RequestBody PressureCalculateRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String environment
    ) {
        return ApiResponse.success(PressureCalculateResponse.from(
                service.calculate(request, token, cookie, environment)
        ));
    }

    @PostMapping("/records/save")
    public ApiResponse<PressureRecordDetail> save(
            @RequestBody PressureSaveRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String environment
    ) {
        return ApiResponse.success(storage.save(request, token, cookie, environment));
    }

    @GetMapping("/records")
    public ApiResponse<List<PressureRecordSummary>> list(
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName
    ) {
        return ApiResponse.success(storage.list(projectId, gasReservoirId, wellName));
    }

    @GetMapping("/records/{id}")
    public ApiResponse<PressureRecordDetail> detail(
            @PathVariable long id,
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName
    ) {
        return ApiResponse.success(storage.detail(id, projectId, gasReservoirId, wellName));
    }

    @DeleteMapping("/records/{id}")
    public ApiResponse<Void> delete(
            @PathVariable long id,
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName
    ) {
        storage.delete(id, projectId, gasReservoirId, wellName);
        return ApiResponse.success();
    }
}
