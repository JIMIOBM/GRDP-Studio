package com.grdp.studio.reservoirloss.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/reservoir-loss/storages")
public class StorageCatalogController {
    private final StorageCatalogService catalog;
    public StorageCatalogController(StorageCatalogService catalog) { this.catalog = catalog; }
    // wellIds取原系统井主键，不以井名关联；两个范围字段不能替代独立storageId。
    public record CreateRequest(@Positive long projectId, @Positive long gasReservoirId,
                                @NotBlank @Size(max = 100) String name,
                                @NotNull @Size(min = 1, max = 2000) List<@NotNull @Positive Long> wellIds) {}
    @GetMapping
    public ApiResponse<List<StorageCatalogService.Storage>> list(@RequestParam long projectId, @RequestParam long gasReservoirId) {
        return ApiResponse.success(catalog.list(projectId, gasReservoirId));
    }
    @PostMapping
    public ApiResponse<StorageCatalogService.Storage> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.success(catalog.create(request.projectId(), request.gasReservoirId(), request.name(), request.wellIds()));
    }
    @GetMapping("/candidate-wells")
    public ApiResponse<List<StorageCatalogService.Well>> candidateWells(@RequestParam long projectId, @RequestParam long gasReservoirId) {
        return ApiResponse.success(catalog.candidateWells(projectId, gasReservoirId));
    }
    @GetMapping("/{id}/wells")
    public ApiResponse<List<StorageCatalogService.Well>> wells(@PathVariable long id,
            @RequestParam long projectId, @RequestParam long gasReservoirId) {
        return ApiResponse.success(catalog.wells(id, projectId, gasReservoirId));
    }
}
