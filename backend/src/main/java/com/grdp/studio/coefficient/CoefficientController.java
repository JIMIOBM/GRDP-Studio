package com.grdp.studio.coefficient;

import com.grdp.studio.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/productivity-coefficients/records")
public class CoefficientController {
    private final CoefficientStorage storage;
    public CoefficientController(CoefficientStorage storage) { this.storage = storage; }
    @GetMapping
    public ApiResponse<List<CoefficientStorage.Summary>> list(@RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam String wellName) {
        return ApiResponse.success(storage.list(projectId, gasReservoirId, wellName));
    }
    @GetMapping("/{id}")
    public ApiResponse<CoefficientStorage.Detail> detail(@PathVariable long id, @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam String wellName) {
        return ApiResponse.success(storage.detail(id, projectId, gasReservoirId, wellName));
    }
    @PostMapping("/save")
    public ApiResponse<CoefficientStorage.Detail> save(@RequestBody CoefficientStorage.Save request) {
        return ApiResponse.success(storage.save(request));
    }
}
