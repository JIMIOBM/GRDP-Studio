package com.grdp.studio.storagemainfactor;

import com.grdp.studio.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.CalculateRequest;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.CalculateResult;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.Context;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SaveRequest;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SavedAnalysis;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SavedMainFactor;

/**
 * 库级主控因素分析接口。薄层：只做参数绑定与 {@link ApiResponse} 包装。
 *
 * <p>原平台调用需要当前用户的会话，因此把浏览器带来的 {@code Cookie} / {@code token} /
 * {@code Process-Env} 原样再转发给原平台，不使用任何全局凭据。
 */
@RestController
@RequestMapping("/storage-main-factor")
public class StorageMainFactorController {

    private final StorageMainFactorService service;

    public StorageMainFactorController(StorageMainFactorService service) {
        this.service = service;
    }

    @GetMapping("/context")
    public ApiResponse<Context> context(@RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam long storageId) {
        return ApiResponse.success(service.context(projectId, gasReservoirId, storageId));
    }

    @PostMapping("/calculate")
    public ApiResponse<CalculateResult> calculate(@RequestBody CalculateRequest request,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Process-Env", required = false) String processEnv) {
        Map<String, String> headers = MaterialBalanceEquationClient.forwardedHeaders(
                token, cookie, processEnv, request.projectId());
        return ApiResponse.success(service.calculate(request, headers));
    }

    /** 保存本库的一份分析：存在则更新，不产生第二条记录。 */
    @PostMapping("/save")
    public ApiResponse<Void> save(@RequestBody SaveRequest request) {
        service.save(request);
        return ApiResponse.success(null);
    }

    /** 读取本库已保存的一份；从未保存过返回 data 为 null 的成功响应。 */
    @GetMapping("/saved")
    public ApiResponse<SavedAnalysis> saved(@RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam long storageId) {
        return ApiResponse.success(service.loadSaved(projectId, gasReservoirId, storageId).orElse(null));
    }
}
