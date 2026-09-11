package com.grdp.studio.theoreticalproductivity.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.CalculateRequest;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.CalculationResult;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.Detail;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.DefaultParameterDetail;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.DefaultParameterRequest;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.RenameRequest;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.SaveRequest;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.Summary;
import com.grdp.studio.dynamicproductivity.service.DynamicUnstableCalculationService;
import com.grdp.studio.theoreticalproductivity.service.TheoreticalUnstableStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 理论计算不稳定流：后端计算与独立五表存储接口。 */
@RestController
@RequestMapping("/theoretical-productivity/unstable")
public class TheoreticalUnstableController {
    private final DynamicUnstableCalculationService calculationService;
    private final TheoreticalUnstableStorageService storageService;

    public TheoreticalUnstableController(DynamicUnstableCalculationService calculationService,
                                     TheoreticalUnstableStorageService storageService) {
        this.calculationService = calculationService;
        this.storageService = storageService;
    }

    @PostMapping("/calculate")
    public ApiResponse<CalculationResult> calculate(@Valid @RequestBody CalculateRequest request,
                                                     HttpServletRequest servletRequest) {
        return ApiResponse.success(calculationService.calculate(request,
                servletRequest.getHeader("token"), servletRequest.getHeader("Cookie"),
                servletRequest.getHeader("Process-Env")));
    }

    @GetMapping
    public ApiResponse<List<Summary>> list(@RequestParam long projectId,
                                            @RequestParam long gasReservoirId,
                                            @RequestParam String wellName) {
        return ApiResponse.success(storageService.list(projectId, gasReservoirId, wellName));
    }

    /** 读取不稳定流专用默认参数，不创建历史记录。 */
    @GetMapping("/default-parameters")
    public ApiResponse<DefaultParameterDetail> defaultParameters(@RequestParam long projectId,
                                                                  @RequestParam long gasReservoirId,
                                                                  @RequestParam String wellName) {
        return ApiResponse.success(storageService.defaultParameters(projectId, gasReservoirId, wellName));
    }

    /** 保存不稳定流专用默认参数，不占用不稳定流编号。 */
    @PostMapping("/default-parameters")
    public ApiResponse<Void> saveDefaultParameters(@Valid @RequestBody DefaultParameterRequest request) {
        storageService.saveDefaultParameters(request);
        return ApiResponse.success();
    }

    @GetMapping("/{unstableId}")
    public ApiResponse<Detail> detail(@PathVariable long unstableId,
                                      @RequestParam long projectId,
                                      @RequestParam long gasReservoirId,
                                      @RequestParam String wellName) {
        return ApiResponse.success(storageService.detail(unstableId, projectId, gasReservoirId, wellName));
    }

    @PostMapping("/save")
    public ApiResponse<Summary> save(@Valid @RequestBody SaveRequest request) {
        return ApiResponse.success(storageService.save(request));
    }

    @PatchMapping("/{unstableId}/name")
    public ApiResponse<Summary> rename(@PathVariable long unstableId,
                                       @Valid @RequestBody RenameRequest request) {
        return ApiResponse.success(storageService.rename(unstableId, request));
    }

    @DeleteMapping("/{unstableId}")
    public ApiResponse<Void> delete(@PathVariable long unstableId,
                                    @RequestParam long projectId,
                                    @RequestParam long gasReservoirId,
                                    @RequestParam String wellName) {
        storageService.delete(unstableId, projectId, gasReservoirId, wellName);
        return ApiResponse.success();
    }
}
