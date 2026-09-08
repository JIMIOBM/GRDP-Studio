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
    private final GeologicalLossCalculationService calculation;
    private final GeologicalLossStorageService storage;
    public GeologicalLossController(GeologicalLossCalculationService calculation, GeologicalLossStorageService storage) {
        this.calculation = calculation; this.storage = storage;
    }

    @GetMapping("/records")
    public ApiResponse<RecordLists> records(@RequestParam long projectId, @RequestParam long gasReservoirId) {
        return ApiResponse.success(storage.listRecords(projectId, gasReservoirId));
    }

    @PostMapping("/microscopic/calculate")
    public ApiResponse<MicroscopicCalculation> calculateMicroscopic(
            @Valid @RequestBody MicroscopicCalculateRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(calculation.calculateMicroscopic(request,
                servletRequest.getHeader("token"), servletRequest.getHeader("Cookie"),
                servletRequest.getHeader("Process-Env")));
    }
    @GetMapping("/microscopic")
    public ApiResponse<List<RecordSummary>> microscopic(@RequestParam long projectId, @RequestParam long gasReservoirId) {
        return ApiResponse.success(storage.listMicroscopic(projectId, gasReservoirId));
    }
    @GetMapping("/microscopic/{id}")
    public ApiResponse<MicroscopicDetail> microscopicDetail(@PathVariable long id, @RequestParam long projectId,
                                                            @RequestParam long gasReservoirId) {
        return ApiResponse.success(storage.microscopicDetail(id, projectId, gasReservoirId));
    }
    @PostMapping("/microscopic/save")
    public ApiResponse<RecordSummary> saveMicroscopic(@Valid @RequestBody MicroscopicSaveRequest request) {
        return ApiResponse.success(storage.saveMicroscopic(request));
    }

    @PostMapping("/escape/calculate")
    public ApiResponse<EscapeCalculation> calculateEscape(@Valid @RequestBody EscapeCalculateRequest request) {
        return ApiResponse.success(calculation.calculateEscape(request));
    }
    @GetMapping("/escape")
    public ApiResponse<List<RecordSummary>> escape(@RequestParam long projectId, @RequestParam long gasReservoirId) {
        return ApiResponse.success(storage.listEscape(projectId, gasReservoirId));
    }
    @GetMapping("/escape/{id}")
    public ApiResponse<EscapeDetail> escapeDetail(@PathVariable long id, @RequestParam long projectId,
                                                  @RequestParam long gasReservoirId) {
        return ApiResponse.success(storage.escapeDetail(id, projectId, gasReservoirId));
    }
    @PostMapping("/escape/save")
    public ApiResponse<RecordSummary> saveEscape(@Valid @RequestBody EscapeSaveRequest request) {
        return ApiResponse.success(storage.saveEscape(request));
    }

    @PatchMapping("/{type}/{id}/name")
    public ApiResponse<RecordSummary> rename(@PathVariable String type, @PathVariable long id,
                                             @Valid @RequestBody RenameRequest request) {
        return ApiResponse.success(storage.renameRecord(type, id, request));
    }
    @DeleteMapping("/{type}/{id}")
    public ApiResponse<Void> delete(@PathVariable String type, @PathVariable long id,
                                    @RequestParam long projectId, @RequestParam long gasReservoirId) {
        storage.deleteRecord(type, id, projectId, gasReservoirId); return ApiResponse.success();
    }
}
