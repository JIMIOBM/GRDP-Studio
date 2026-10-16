package com.grdp.studio.predictionresult.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.predictionresult.dto.PredictionResultDtos.ResultSummary;
import com.grdp.studio.predictionresult.dto.PredictionResultDtos.SaveRequest;
import com.grdp.studio.predictionresult.dto.PredictionResultDtos.SavedSummary;
import com.grdp.studio.predictionresult.service.PredictionResultStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 井组动态预测结果留存接口：预测完成后保存，供本项目数据库侧查询。 */
@RestController
@RequestMapping("/prediction-results")
public class PredictionResultController {
    private final PredictionResultStorageService storage;

    public PredictionResultController(PredictionResultStorageService storage) {
        this.storage = storage;
    }

    @PostMapping
    public ApiResponse<SavedSummary> save(@Valid @RequestBody SaveRequest request) {
        return ApiResponse.success(storage.save(request));
    }

    @GetMapping
    public ApiResponse<List<ResultSummary>> list(@RequestParam long projectId,
                                                 @RequestParam long gasReservoirId,
                                                 @RequestParam(required = false) Long storageId) {
        return ApiResponse.success(storage.list(projectId, gasReservoirId, storageId));
    }

    /** 删除留存记录（幂等）；返回实际删除的行数（0 = 记录本不存在）。 */
    @DeleteMapping
    public ApiResponse<Integer> delete(@RequestParam long projectId,
                                       @RequestParam long gasReservoirId,
                                       @RequestParam(required = false) Long storageId,
                                       @RequestParam long predictionId) {
        return ApiResponse.success(storage.delete(projectId, gasReservoirId, storageId, predictionId));
    }
}
