package com.grdp.studio.reservoirdiagnostic.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.reservoirdiagnostic.dto.GasReservoirDiagnosticModels;
import com.grdp.studio.reservoirdiagnostic.service.GasReservoirDiagnosticService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 储气库级诊断曲线接口。
 *
 * <p>库级页面不再上传 productionData。后端会自动读取当前储气库内
 * 所选井的最新 CALCULATED 单井诊断方案，将 input 按时间汇总后，
 * 复用现有 DiagnosticCurveService 完成库存量、P/Z、理论线和周期曲线计算。</p>
 */
@RestController
@RequestMapping("/gas-reservoir-diagnostic")
public class GasReservoirDiagnosticController {

    private final GasReservoirDiagnosticService service;

    public GasReservoirDiagnosticController(
            GasReservoirDiagnosticService service
    ) {
        this.service = service;
    }

    /**
     * 页面初始化：
     * 1. 库内井列表及是否具备可用诊断输入；
     * 2. 可作为库级代表 PVT 的快照选项。
     */
    @GetMapping("/context")
    public ApiResponse<GasReservoirDiagnosticModels.ContextResponse> getContext(
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam long storageId
    ) {
        return ApiResponse.success(
                service.context(projectId, gasReservoirId, storageId)
        );
    }

    /**
     * 计算库级诊断曲线。
     *
     * <p>请求包含项目、气藏、所选井、代表 PVT 和压力上下限；
     * 注采明细由后端从所选井诊断 input 自动读取并按时间求和。</p>
     */
    @PostMapping("/calculate")
    public ApiResponse<GasReservoirDiagnosticModels.CalculateResponse> calculate(
            @Valid
            @RequestBody
            GasReservoirDiagnosticModels.CalculateRequest request,

            @RequestHeader(
                    value = "token",
                    required = false
            )
            String token,

            @RequestHeader(
                    value = "Cookie",
                    required = false
            )
            String cookie,

            @RequestHeader(
                    value = "Process-Env",
                    required = false
            )
            String processEnv
    ) {
        return ApiResponse.success(
                service.calculate(
                        request,
                        token,
                        cookie,
                        processEnv
                )
        );
    }
}
