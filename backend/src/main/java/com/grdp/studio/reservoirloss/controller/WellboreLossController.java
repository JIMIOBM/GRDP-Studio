package com.grdp.studio.reservoirloss.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.reservoirloss.dto.WellboreLossDtos.*;
import com.grdp.studio.reservoirloss.service.WellboreLossCalculationService;
import com.grdp.studio.reservoirloss.service.WellboreLossStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 储气库井筒损耗的计算和记录接口。 */
@RestController
@RequestMapping("/reservoir-loss/wellbore")
public class WellboreLossController {
    private final WellboreLossCalculationService calculation;
    private final WellboreLossStorageService storage;

    public WellboreLossController(WellboreLossCalculationService calculation,
                                  WellboreLossStorageService storage) {
        this.calculation = calculation;
        this.storage = storage;
    }

    @PostMapping("/calculate")
    public ApiResponse<Calculation> calculate(@Valid @RequestBody CalculateRequest request,
                                              HttpServletRequest servletRequest) {
        return ApiResponse.success(calculation.calculate(request,
                servletRequest.getHeader("token"), servletRequest.getHeader("Cookie"),
                servletRequest.getHeader("Process-Env")));
    }

    @GetMapping("/records")
    public ApiResponse<List<RecordSummary>> records(@RequestParam long projectId,
                                                    @RequestParam long gasReservoirId) {
        return ApiResponse.success(storage.list(projectId, gasReservoirId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@PathVariable long id, @RequestParam long projectId,
                                     @RequestParam long gasReservoirId) {
        return ApiResponse.success(storage.detail(id, projectId, gasReservoirId));
    }

    @PostMapping("/save")
    public ApiResponse<RecordSummary> save(@Valid @RequestBody SaveRequest request,
                                           HttpServletRequest servletRequest) {
        // 先检查记录归属，再按当前输入重新计算；不能信任客户端提交的 Z 值或损耗结果。
        if (request.recordId() != null) {
            storage.detail(request.recordId(), request.projectId(), request.gasReservoirId());
        }
        Calculation verified = calculation.calculate(new CalculateRequest(request.projectId(),
                request.gasReservoirId(), request.input()), servletRequest.getHeader("token"),
                servletRequest.getHeader("Cookie"), servletRequest.getHeader("Process-Env"));
        return ApiResponse.success(storage.save(new SaveRequest(request.recordId(), request.projectId(),
                request.gasReservoirId(), request.input(), verified)));
    }

    @PatchMapping("/{id}/name")
    public ApiResponse<RecordSummary> rename(@PathVariable long id,
                                             @RequestParam long projectId, @RequestParam long gasReservoirId,
                                             @Valid @RequestBody RenameRequest request) {
        return ApiResponse.success(storage.rename(id, projectId, gasReservoirId, request.name()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, @RequestParam long projectId,
                                    @RequestParam long gasReservoirId) {
        storage.delete(id, projectId, gasReservoirId);
        return ApiResponse.success();
    }
}
