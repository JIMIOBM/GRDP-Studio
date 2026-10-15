package com.grdp.studio.storagemainfactor;

import com.grdp.studio.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SaveRequest;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SavedAnalysis;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesDataRequest;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesPointInput;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesRequest;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesResult;

/**
 * 库级主控因素分析接口。薄层：只做参数绑定与 {@link ApiResponse} 包装。
 *
 * <p>时序数据由页面**导入**、存在本模块自己的表里；序列计算逐点调用原平台工具箱，
 * 因此把浏览器带来的 {@code Cookie} / {@code token} / {@code Process-Env} 原样再转发给原平台，
 * 不使用任何全局凭据。
 */
@RestController
@RequestMapping("/storage-main-factor")
public class StorageMainFactorController {

    private final StorageMainFactorService service;
    private final StorageMainFactorSeriesService seriesService;

    public StorageMainFactorController(StorageMainFactorService service,
            StorageMainFactorSeriesService seriesService) {
        this.service = service;
        this.seriesService = seriesService;
    }

    /** 本库已导入的时序数据，按日期升序；没导入过返回空表。 */
    @GetMapping("/series-data")
    public ApiResponse<List<SeriesPointInput>> seriesData(@RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam long storageId) {
        return ApiResponse.success(seriesService.points(projectId, gasReservoirId, storageId));
    }

    /** 导入时序数据（**整体替换**本库已有的一份），返回写入的点数。 */
    @PostMapping("/series-data")
    public ApiResponse<Integer> importSeriesData(@RequestBody SeriesDataRequest request) {
        return ApiResponse.success(seriesService.importPoints(request));
    }

    /** 逐点计算理论地层压力，返回整条序列。 */
    @PostMapping("/series")
    public ApiResponse<SeriesResult> series(@RequestBody SeriesRequest request,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Process-Env", required = false) String processEnv) {
        Map<String, String> headers = MaterialBalanceEquationClient.forwardedHeaders(
                token, cookie, processEnv, request.projectId());
        return ApiResponse.success(seriesService.calculate(request, headers));
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
