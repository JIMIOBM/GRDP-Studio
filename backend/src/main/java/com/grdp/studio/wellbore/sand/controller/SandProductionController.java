package com.grdp.studio.wellbore.sand.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.wellbore.sand.dto.SandProductionRequest;
import com.grdp.studio.wellbore.sand.dto.SandProductionResult;
import com.grdp.studio.wellbore.sand.dto.SandProductionSaveRequest;
import com.grdp.studio.wellbore.sand.service.SandProductionCalculator;
import com.grdp.studio.wellbore.sand.service.SandProductionStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/wellbore")
public class SandProductionController {
    private final SandProductionCalculator calculator;
    private final SandProductionStorageService storage;

    public SandProductionController(SandProductionCalculator calculator, SandProductionStorageService storage) {
        this.calculator = calculator; this.storage = storage;
    }

    @PostMapping("/sand-production/calculate")
    public ApiResponse<SandProductionResult> calculate(@Valid @RequestBody SandProductionRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String environment) {
        return ApiResponse.success(calculator.calculate(request));
    }

    @PostMapping("/sand-production/records/save")
    public ApiResponse<Map<String, Object>> save(@Valid @RequestBody SandProductionSaveRequest request) {
        return ApiResponse.success(storage.save(request));
    }

    @GetMapping("/sand-production/records")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam String wellName) {
        return ApiResponse.success(storage.list(projectId, gasReservoirId, wellName));
    }

    @GetMapping("/sand-production/records/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam String wellName) {
        return ApiResponse.success(storage.detail(id, projectId, gasReservoirId, wellName));
    }

    @DeleteMapping("/sand-production/records/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam String wellName) {
        storage.delete(id, projectId, gasReservoirId, wellName);
        return ApiResponse.success();
    }
}
