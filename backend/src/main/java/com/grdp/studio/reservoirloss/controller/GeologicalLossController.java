package com.grdp.studio.reservoirloss.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.reservoirloss.dto.GeologicalLossDtos.*;
import com.grdp.studio.reservoirloss.service.GeologicalLossCalculationService;
import com.grdp.studio.reservoirloss.service.GeologicalLossStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 储气库地质损耗的计算与记录接口。 */
@RestController
@RequestMapping("/reservoir-loss")
public class GeologicalLossController {
    private final com.grdp.studio.reservoirloss.service.StorageCatalogService catalog;
    private final GeologicalLossCalculationService calculation;
    private final GeologicalLossStorageService storage;
    public GeologicalLossController(GeologicalLossCalculationService calculation, GeologicalLossStorageService storage, com.grdp.studio.reservoirloss.service.StorageCatalogService catalog) {
        this.calculation = calculation; this.storage = storage;
        this.catalog = catalog;
    }

    @GetMapping("/records")
    public ApiResponse<RecordLists> records(@RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(storage.listRecords(projectId, gasReservoirId, storageId));
    }

    @PostMapping("/microscopic/calculate")
    public ApiResponse<MicroscopicCalculation> calculateMicroscopic(
            @Valid @RequestBody MicroscopicCalculateRequest request, HttpServletRequest servletRequest) {
        catalog.requireScope(request.projectId(), request.gasReservoirId(), request.storageId());
        return ApiResponse.success(calculation.calculateMicroscopic(request,
                servletRequest.getHeader("token"), servletRequest.getHeader("Cookie"),
                servletRequest.getHeader("Process-Env")));
    }
    @GetMapping("/microscopic")
    public ApiResponse<List<RecordSummary>> microscopic(@RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(storage.listMicroscopic(projectId, gasReservoirId, storageId));
    }
    @GetMapping("/microscopic/{id}")
    public ApiResponse<MicroscopicDetail> microscopicDetail(@PathVariable long id, @RequestParam long projectId,
                                                            @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(storage.microscopicDetail(id, projectId, gasReservoirId, storageId));
    }
    @PostMapping("/microscopic/save")
    public ApiResponse<RecordSummary> saveMicroscopic(@Valid @RequestBody MicroscopicSaveRequest request) {
        catalog.requireScope(request.projectId(), request.gasReservoirId(), request.storageId());
        return ApiResponse.success(storage.saveMicroscopic(request));
    }

    @PostMapping("/escape/calculate")
    public ApiResponse<EscapeCalculation> calculateEscape(@Valid @RequestBody EscapeCalculateRequest request) {
        catalog.requireScope(request.projectId(), request.gasReservoirId(), request.storageId());
        return ApiResponse.success(calculation.calculateEscape(request));
    }
    @GetMapping("/escape")
    public ApiResponse<List<RecordSummary>> escape(@RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(storage.listEscape(projectId, gasReservoirId, storageId));
    }
    @GetMapping("/escape/{id}")
    public ApiResponse<EscapeDetail> escapeDetail(@PathVariable long id, @RequestParam long projectId,
                                                  @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(storage.escapeDetail(id, projectId, gasReservoirId, storageId));
    }
    @PostMapping("/escape/save")
    public ApiResponse<RecordSummary> saveEscape(@Valid @RequestBody EscapeSaveRequest request) {
        catalog.requireScope(request.projectId(), request.gasReservoirId(), request.storageId());
        return ApiResponse.success(storage.saveEscape(request));
    }

    @PatchMapping("/{type}/{id}/name")
    public ApiResponse<RecordSummary> rename(@PathVariable String type, @PathVariable long id,
                                             @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam long storageId,
                                             @Valid @RequestBody RenameRequest request) {
        return ApiResponse.success(storage.renameRecord(type, id, projectId, gasReservoirId, storageId, request));
    }
    @DeleteMapping("/{type}/{id}")
    public ApiResponse<Void> delete(@PathVariable String type, @PathVariable long id,
                                    @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam long storageId) {
        storage.deleteRecord(type, id, projectId, gasReservoirId, storageId); return ApiResponse.success();
    }
}
