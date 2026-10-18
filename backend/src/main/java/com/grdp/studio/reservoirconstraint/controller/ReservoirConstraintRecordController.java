package com.grdp.studio.reservoirconstraint.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.reservoirconstraint.dto.ReservoirConstraintRecordSaveRequest;
import com.grdp.studio.reservoirconstraint.service.ReservoirConstraintRecordStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 库级多周期预测约束条件记录 CRUD。 */
@RestController
@RequestMapping("/solution-generation/reservoir-constraint-records")
public class ReservoirConstraintRecordController {
    private final ReservoirConstraintRecordStorageService storage;

    public ReservoirConstraintRecordController(ReservoirConstraintRecordStorageService storage) {
        this.storage = storage;
    }

    @PostMapping("/save")
    public ApiResponse<Map<String, Object>> save(@Valid @RequestBody ReservoirConstraintRecordSaveRequest request) {
        return ApiResponse.success(storage.save(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable long id, @Valid @RequestBody ReservoirConstraintRecordSaveRequest request) {
        return ApiResponse.success(storage.update(id, request));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam long storageId) {
        return ApiResponse.success(storage.list(projectId, gasReservoirId, storageId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.success(storage.detail(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable long id,
            @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam long storageId) {
        storage.delete(id, projectId, gasReservoirId, storageId);
        return ApiResponse.success();
    }
}