package com.grdp.studio.storagepressuretest;

import com.grdp.studio.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/storage-pressure-test/points")
public class StoragePressureTestMethodController {
    private final StoragePressureTestMethodService service;

    public StoragePressureTestMethodController(StoragePressureTestMethodService service) { this.service = service; }

    @GetMapping
    public ApiResponse<StoragePressureTestMethodService.Dataset> list(@RequestParam long projectId,
            @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(service.list(projectId, gasReservoirId, storageId));
    }

    @PutMapping
    public ApiResponse<StoragePressureTestMethodService.Dataset> save(@RequestParam long projectId,
            @RequestParam long gasReservoirId, @RequestParam long storageId,
            @RequestBody StoragePressureTestMethodService.SaveRequest request) {
        return ApiResponse.success(service.save(projectId, gasReservoirId, storageId, request));
    }
}
