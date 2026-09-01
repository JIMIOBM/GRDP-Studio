package com.grdp.studio.theoreticalproductivity.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.Detail;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.DefaultParameterDetail;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.DefaultParameterRequest;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.SaveRequest;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.RenameRequest;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.Summary;
import com.grdp.studio.theoreticalproductivity.service.TheoreticalStableStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 理论计算稳定流的新库读写接口；计算本身仍复用原平台算法接口。 */
@RestController
@RequestMapping("/theoretical-productivity/stable")
public class TheoreticalStableController {
    private final TheoreticalStableStorageService storageService;

    public TheoreticalStableController(TheoreticalStableStorageService storageService) {
        this.storageService = storageService;
    }

    /** 返回某口井已经显式保存的稳定流摘要，供左侧目录使用。 */
    @GetMapping
    public ApiResponse<List<Summary>> list(@RequestParam long projectId,
                                            @RequestParam long gasReservoirId,
                                            @RequestParam String wellName) {
        return ApiResponse.success(storageService.list(projectId, gasReservoirId, wellName));
    }

    /** 读取顶部首次计算保存的井级默认参数；它不属于任何“稳定流N”。 */
    @GetMapping("/default-parameters")
    public ApiResponse<DefaultParameterDetail> defaultParameters(@RequestParam long projectId,
                                                                  @RequestParam long gasReservoirId,
                                                                  @RequestParam String wellName) {
        return ApiResponse.success(storageService.defaultParameters(projectId, gasReservoirId, wellName));
    }

    /** 保存井级默认参数，不创建稳定流记录，也不占用稳定流编号。 */
    @PostMapping("/default-parameters")
    public ApiResponse<Void> saveDefaultParameters(@Valid @RequestBody DefaultParameterRequest request) {
        storageService.saveDefaultParameters(request);
        return ApiResponse.success();
    }

    /** 一次性恢复某次稳定流的基本信息、注采输入、三种输出和 IPR 曲线。 */
    @GetMapping("/{stableId}")
    public ApiResponse<Detail> detail(@PathVariable long stableId,
                                      @RequestParam long projectId,
                                      @RequestParam long gasReservoirId,
                                      @RequestParam String wellName) {
        return ApiResponse.success(storageService.detail(stableId, projectId, gasReservoirId, wellName));
    }

    /** 新建稳定流，或覆盖已有稳定流的当前注采方向。 */
    @PostMapping("/save")
    public ApiResponse<Summary> save(@Valid @RequestBody SaveRequest request) {
        return ApiResponse.success(storageService.save(request));
    }

    /** 只修改左侧显示名称，不重新计算也不覆盖结果。 */
    @PatchMapping("/{stableId}/name")
    public ApiResponse<Summary> rename(@PathVariable long stableId,
                                       @Valid @RequestBody RenameRequest request) {
        return ApiResponse.success(storageService.rename(stableId, request));
    }

    /** 删除整次稳定流；输入、输出和 IPR 依靠外键级联删除。 */
    @DeleteMapping("/{stableId}")
    public ApiResponse<Void> delete(@PathVariable long stableId,
                                    @RequestParam long projectId,
                                    @RequestParam long gasReservoirId,
                                    @RequestParam String wellName) {
        storageService.delete(stableId, projectId, gasReservoirId, wellName);
        return ApiResponse.success();
    }
}


