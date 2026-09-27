package com.grdp.studio.wellbore.erosion.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.wellbore.erosion.dto.*;
import com.grdp.studio.wellbore.erosion.service.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/wellbore/erosion")
public class ErosionController {
    private final ErosionCalculationService calculation;
    private final ErosionStorageService storage;
    public ErosionController(ErosionCalculationService calculation, ErosionStorageService storage) { this.calculation = calculation; this.storage = storage; }

    @PostMapping("/properties")
    public ApiResponse<ErosionCalculationService.Properties> properties(@RequestBody ErosionCalculationService.PropertiesRequest input,
            @RequestHeader(value="token", required=false) String token, @RequestHeader(value="Cookie", required=false) String cookie,
            @RequestHeader(value="Process-Env", required=false) String environment) {
        return ApiResponse.success(calculation.properties(input, token, cookie, environment));
    }
    @PostMapping("/calculate")
    public ApiResponse<ErosionCalculationService.Calculation> calculate(@RequestBody ErosionRequest input,
            @RequestHeader(value="token", required=false) String token, @RequestHeader(value="Cookie", required=false) String cookie,
            @RequestHeader(value="Process-Env", required=false) String environment) {
        return ApiResponse.success(calculation.calculate(input, token, cookie, environment));
    }
    @PostMapping("/records/save")
    public ApiResponse<Map<String, Object>> save(@Valid @RequestBody ErosionSaveRequest input,
            @RequestHeader(value="token", required=false) String token, @RequestHeader(value="Cookie", required=false) String cookie,
            @RequestHeader(value="Process-Env", required=false) String environment) {
        return ApiResponse.success(storage.save(input, token, cookie, environment));
    }
    @GetMapping("/records")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam String wellName) {
        return ApiResponse.success(storage.list(projectId, gasReservoirId, wellName));
    }
    @GetMapping("/records/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam String wellName) {
        return ApiResponse.success(storage.detail(id, projectId, gasReservoirId, wellName));
    }
    @DeleteMapping("/records/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, @RequestParam long projectId, @RequestParam long gasReservoirId, @RequestParam String wellName) {
        storage.delete(id, projectId, gasReservoirId, wellName); return ApiResponse.success();
    }
}
