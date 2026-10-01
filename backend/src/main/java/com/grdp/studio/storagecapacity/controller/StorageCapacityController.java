package com.grdp.studio.storagecapacity.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.storagecapacity.dto.StorageCapacityDtos.Design;
import com.grdp.studio.storagecapacity.dto.StorageCapacityDtos.SaveRequest;
import com.grdp.studio.storagecapacity.service.StorageCapacityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 储气库库容设计：运行压力与库容参数的读写接口。 */
@RestController
@RequestMapping("/storage-capacity")
public class StorageCapacityController {
    private final StorageCapacityService service;

    public StorageCapacityController(StorageCapacityService service) { this.service = service; }

    /** 读取该储气库的库容设计；尚未保存时 data 为空，前端据此显示空表单。 */
    @GetMapping
    public ApiResponse<Design> find(@RequestParam long projectId,
                                    @RequestParam long gasReservoirId,
                                    @RequestParam long storageId) {
        return ApiResponse.success(service.find(projectId, gasReservoirId, storageId));
    }

    /** 保存该储气库的库容设计：已存在则更新，不存在则新建。 */
    @PostMapping("/save")
    public ApiResponse<Design> save(@Valid @RequestBody SaveRequest request) {
        return ApiResponse.success(service.save(request));
    }

    /** 清空该储气库的库容设计。 */
    @DeleteMapping
    public ApiResponse<Void> remove(@RequestParam long projectId,
                                    @RequestParam long gasReservoirId,
                                    @RequestParam long storageId) {
        service.remove(projectId, gasReservoirId, storageId);
        return ApiResponse.success();
    }
}
