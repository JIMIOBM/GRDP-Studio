package com.grdp.studio.wellbore.temperature.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.wellbore.temperature.dto.TemperatureCalculateRequest;
import com.grdp.studio.wellbore.temperature.dto.TemperatureCalculateResponse;
import com.grdp.studio.wellbore.temperature.dto.TemperatureRecordDetail;
import com.grdp.studio.wellbore.temperature.dto.TemperatureRecordSummary;
import com.grdp.studio.wellbore.temperature.dto.TemperatureSaveRequest;
import com.grdp.studio.wellbore.temperature.service.TemperatureService;
import com.grdp.studio.wellbore.temperature.service.TemperatureStorageService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/wellbore/temperature")
public class TemperatureController {
    private final TemperatureService service;
    private final TemperatureStorageService storage;

    public TemperatureController(
            TemperatureService service,
            TemperatureStorageService storage
    ) {
        this.service = service;
        this.storage = storage;
    }

    @PostMapping("/calculate")
    public ApiResponse<TemperatureCalculateResponse> calculate(
            @RequestBody TemperatureCalculateRequest request
    ) {
        return ApiResponse.success(TemperatureCalculateResponse.from(service.calculate(request)));
    }

    @PostMapping("/records/save")
    public ApiResponse<TemperatureRecordDetail> save(
            @RequestBody TemperatureSaveRequest request
    ) {
        return ApiResponse.success(storage.save(request));
    }

    @GetMapping("/records")
    public ApiResponse<List<TemperatureRecordSummary>> list(
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName
    ) {
        return ApiResponse.success(storage.list(projectId, gasReservoirId, wellName));
    }

    @GetMapping("/records/{id}")
    public ApiResponse<TemperatureRecordDetail> detail(
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
