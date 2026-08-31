package com.grdp.studio.productivity;

import com.grdp.studio.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/productivity-tests")
public class ProductivityTestController {
    private final ProductivityTestService service;

    public ProductivityTestController(ProductivityTestService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ProductivityTestModels.Summary>> list(
            @RequestParam long projectId, @RequestParam long gasReservoirId,
            @RequestParam String wellName,
            @RequestParam(defaultValue = "modified-isochronal") String testMethod) {
        return ApiResponse.success(service.list(projectId, gasReservoirId, wellName, testMethod));
    }

    @GetMapping("/{testId}")
    public ApiResponse<ProductivityTestModels.Detail> detail(
            @PathVariable long testId,
            @RequestParam long projectId,
            @RequestParam long gasReservoirId,
            @RequestParam String wellName) {
        return ApiResponse.success(service.detail(testId, projectId, gasReservoirId, wellName));
    }

    @PostMapping("/import")
    public ApiResponse<ProductivityTestModels.ImportedRows> importRows(@RequestPart("file") MultipartFile file) {
        return ApiResponse.success(new ProductivityTestModels.ImportedRows(service.importRows(file)));
    }

    @PostMapping("/save")
    public ApiResponse<ProductivityTestModels.SaveResponse> save(
            @Valid @RequestBody ProductivityTestModels.SaveRequest request) {
        return ApiResponse.success(service.save(request));
    }
}
