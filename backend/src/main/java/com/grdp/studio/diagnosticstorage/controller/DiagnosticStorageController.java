package com.grdp.studio.diagnosticstorage.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.diagnosticstorage.dto.DiagnosticRecordDetail;
import com.grdp.studio.diagnosticstorage.dto.DiagnosticRecordSummary;
import com.grdp.studio.diagnosticstorage.dto.DiagnosticSaveRequest;
import com.grdp.studio.diagnosticstorage.dto.DiagnosticSaveResponse;
import com.grdp.studio.diagnosticstorage.service.DiagnosticStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 井控库存诊断曲线保存、列表、详情、删除接口。
 *
 * <p>计算接口仍由原有 /diagnostic-curve/calculate 提供；
 * 本控制器只负责把计算现场和计算结果持久化到三张诊断表。</p>
 */
@RestController
@RequestMapping("/diagnostic-curve/records")
public class DiagnosticStorageController {

    private final DiagnosticStorageService diagnosticStorageService;

    public DiagnosticStorageController(DiagnosticStorageService diagnosticStorageService) {
        this.diagnosticStorageService = diagnosticStorageService;
    }

    /**
     * 查询当前项目/气藏/井下已经保存的诊断曲线。
     */
    @GetMapping
    public ApiResponse<List<DiagnosticRecordSummary>> list(
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName
    ) {
        return ApiResponse.success(
                diagnosticStorageService.list(projectId, gasReservoirId, wellName)
        );
    }

    /**
     * 打开一个已经保存的诊断方案。
     * 直接返回输入、PVT快照和已保存结果，不自动重新计算。
     */
    @GetMapping("/{diagnosticId}")
    public ApiResponse<DiagnosticRecordDetail> getDetail(
            @PathVariable long diagnosticId,
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName
    ) {
        return ApiResponse.success(
                diagnosticStorageService.getDetail(
                        diagnosticId,
                        projectId,
                        gasReservoirId,
                        wellName
                )
        );
    }

    /**
     * 删除一个诊断方案。
     * 服务层会显式删除输入和结果，再删除主表记录。
     */
    @DeleteMapping("/{diagnosticId}")
    public ApiResponse<Void> delete(
            @PathVariable long diagnosticId,
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName
    ) {
        diagnosticStorageService.delete(
                diagnosticId,
                projectId,
                gasReservoirId,
                wellName
        );
        return ApiResponse.success();
    }

    /**
     * 保存或更新。
     *
     * <p>diagnosticId 为空：新建/另存为；
     * diagnosticId 有值：修改原方案。</p>
     */
    @PostMapping("/save")
    public ApiResponse<DiagnosticSaveResponse> save(
            @Valid @RequestBody DiagnosticSaveRequest request
    ) {
        return ApiResponse.success(
                diagnosticStorageService.save(request)
        );
    }
}
