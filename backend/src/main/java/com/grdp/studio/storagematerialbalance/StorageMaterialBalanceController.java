package com.grdp.studio.storagematerialbalance;

import com.grdp.studio.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/storage-material-balance")
public class StorageMaterialBalanceController {
    private final StorageMaterialBalanceService service;
    private final StorageMaterialBalanceSourceService sources;
    public StorageMaterialBalanceController(StorageMaterialBalanceService service, StorageMaterialBalanceSourceService sources) {
        this.service = service; this.sources = sources;
    }

    @GetMapping("/source")
    public ApiResponse<StorageMaterialBalanceSourceService.Detail> source(@RequestParam long projectId,
            @RequestParam long gasReservoirId, @RequestParam long storageId, @RequestParam long wellId, @RequestParam long resultId) {
        return ApiResponse.success(sources.detail(projectId,gasReservoirId,storageId,wellId,resultId));
    }

    @GetMapping("/availability")
    public ApiResponse<java.util.List<StorageMaterialBalanceSourceService.Availability>> availability(
            @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(sources.availability(projectId,gasReservoirId,storageId));
    }

    @GetMapping("/aggregate")
    public ApiResponse<StorageMaterialBalanceCalculator.Result> aggregate(
            @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(service.aggregate(projectId, gasReservoirId, storageId));
    }
}
