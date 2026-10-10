package com.grdp.studio.storagepressuregradient;

import com.grdp.studio.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/storage-pressure-gradient/points")
public class StoragePressureGradientController {
    private final StoragePressureGradientService service;

    public StoragePressureGradientController(StoragePressureGradientService service) { this.service = service; }

    @GetMapping
    public ApiResponse<StoragePressureGradientService.Dataset> list(@RequestParam long projectId,
            @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(service.list(projectId, gasReservoirId, storageId));
    }

    @PutMapping
    public ApiResponse<StoragePressureGradientService.Dataset> save(@RequestParam long projectId,
            @RequestParam long gasReservoirId, @RequestParam long storageId,
            @RequestBody StoragePressureGradientService.SaveRequest request) {
        return ApiResponse.success(service.save(projectId, gasReservoirId, storageId, request));
    }
}
