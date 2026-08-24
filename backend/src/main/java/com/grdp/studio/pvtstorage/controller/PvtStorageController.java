package com.grdp.studio.pvtstorage.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.pvtstorage.dto.PvtRecordDetail;
import com.grdp.studio.pvtstorage.dto.PvtRecordSummary;
import com.grdp.studio.pvtstorage.dto.PvtSaveRequest;
import com.grdp.studio.pvtstorage.dto.PvtSaveResponse;
import com.grdp.studio.pvtstorage.service.PvtStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 接收 PVT 页面“数据列表”和“结果分析图”的统一保存请求。
 * 具体表路由、校验和事务处理由 {@link PvtStorageService} 完成。
 */
@RestController
@RequestMapping("/pvt/records")
public class PvtStorageController {

    private final PvtStorageService pvtStorageService;

    public PvtStorageController(PvtStorageService pvtStorageService) {
        this.pvtStorageService = pvtStorageService;
    }

    /**
     * 左侧树只展示数据库中真实存在的 PVT 主记录。
     */
    @GetMapping
    public ApiResponse<List<PvtRecordSummary>> list(
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName
    ) {
        return ApiResponse.success(pvtStorageService.list(projectId, gasReservoirId, wellName));
    }

    @GetMapping("/{pvtId}")
    public ApiResponse<PvtRecordDetail> getDetail(
            @PathVariable long pvtId,
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName
    ) {
        return ApiResponse.success(
                pvtStorageService.getDetail(pvtId, projectId, gasReservoirId, wellName));
    }

    @PostMapping("/save")
    public ApiResponse<PvtSaveResponse> save(@Valid @RequestBody PvtSaveRequest request) {
        return ApiResponse.success(pvtStorageService.save(request));
    }
}
