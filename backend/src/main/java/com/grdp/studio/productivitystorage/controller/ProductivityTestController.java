package com.grdp.studio.productivitystorage.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.Detail;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.SaveRequest;
import com.grdp.studio.productivitystorage.dto.IsochronalTestDtos.Summary;
import com.grdp.studio.productivitystorage.service.ProductivityTestStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("isochronalProductivityTestController")
@RequestMapping("/isochronal-productivity-tests")
public class ProductivityTestController {
    private final ProductivityTestStorageService storageService;

    public ProductivityTestController(ProductivityTestStorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping
    public ApiResponse<List<Summary>> list(@RequestParam long projectId,
                                            @RequestParam long gasReservoirId,
                                            @RequestParam(required = false) String wellName) {
        return ApiResponse.success(storageService.listIsochronal(projectId, gasReservoirId, wellName));
    }

    @GetMapping("/{testId}")
    public ApiResponse<Detail> detail(@PathVariable long testId,
                                      @RequestParam long projectId,
                                      @RequestParam long gasReservoirId) {
        return ApiResponse.success(storageService.getIsochronal(testId, projectId, gasReservoirId));
    }

    @PostMapping("/save")
    public ApiResponse<Summary> save(@Valid @RequestBody SaveRequest request) {
        return ApiResponse.success(storageService.saveIsochronal(request));
    }
}
