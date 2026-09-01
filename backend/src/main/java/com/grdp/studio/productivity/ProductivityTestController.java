package com.grdp.studio.productivity;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.pvtstorage.dto.PvtRecordDetail;
import com.grdp.studio.pvtstorage.service.PvtStorageService;
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
    private final ModifiedIsochronalExponentialCalculator exponentialCalculator;
    private final PvtStorageService pvtStorageService;

    public ProductivityTestController(ProductivityTestService service,
                                      ModifiedIsochronalExponentialCalculator exponentialCalculator,
                                      PvtStorageService pvtStorageService) {
        this.service = service;
        this.exponentialCalculator = exponentialCalculator;
        this.pvtStorageService = pvtStorageService;
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
            @RequestParam(required = false) String resultType,
            @RequestParam(required = false) String pressureMethod) {
        return ApiResponse.success(service.detail(testId, resultType, pressureMethod));
    }

    @PostMapping("/modified-isochronal/exponential/calculate")
    public ApiResponse<ModifiedIsochronalExponentialModels.CalculateResponse> calculateExponential(
            @Valid @RequestBody ModifiedIsochronalExponentialModels.CalculateRequest request) {
        PvtRecordDetail pvt = pvtStorageService.getDetail(request.pvtId(), request.projectId(),
                request.gasReservoirId(), request.wellName());
        var pseudoPressurePoints = pvt.gasResults().stream()
                .filter(point -> point.pressure() != null && point.pseudoPressure() != null)
                .map(point -> new ModifiedIsochronalExponentialModels.PseudoPressurePoint(
                        point.pressure(), point.pseudoPressure()))
                .toList();
        var trustedRequest = new ModifiedIsochronalExponentialModels.CalculateRequest(
                request.projectId(), request.gasReservoirId(), request.wellName(), request.pvtId(),
                request.operationType(), request.pressureMethod(), request.maximumFormationPressure(),
                request.inputItems(), pseudoPressurePoints, request.pressureFunctionDifferences(),
                request.pressureFunctionCurves());
        return ApiResponse.success(exponentialCalculator.calculate(trustedRequest));
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
