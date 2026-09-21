package com.grdp.studio.storagenetwork;

import com.grdp.studio.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/storage-network")
public class StorageNetworkController {
    private final StorageNetworkTopologyService topology;
    private final StorageNetworkCorrelationService correlation;

    public StorageNetworkController(StorageNetworkTopologyService topology, StorageNetworkCorrelationService correlation) {
        this.topology = topology;
        this.correlation = correlation;
    }

    @GetMapping("/topology")
    public ApiResponse<StorageNetworkTopologyService.Detail> topology(@RequestParam long projectId,
            @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(topology.detail(projectId, gasReservoirId, storageId));
    }

    @PutMapping("/topology")
    public ApiResponse<StorageNetworkTopologyService.Detail> save(@Valid @RequestBody StorageNetworkTopologyService.Save request) {
        return ApiResponse.success(topology.save(request));
    }

    @GetMapping("/correlation/source")
    public ApiResponse<StorageNetworkCorrelationService.Source> correlation(@RequestParam long projectId,
            @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(correlation.source(projectId, gasReservoirId, storageId));
    }
}
