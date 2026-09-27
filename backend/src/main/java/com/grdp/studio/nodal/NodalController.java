package com.grdp.studio.nodal;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.coefficient.CoefficientStorage;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.grdp.studio.nodal.NodalModels.*;

@RestController
@RequestMapping("/allocation/nodal")
public class NodalController {
    private final NodalService calculation;
    private final NodalStorage storage;
    public NodalController(NodalService calculation, NodalStorage storage) { this.calculation = calculation; this.storage = storage; }
    @GetMapping("/sources")
    public ApiResponse<List<CoefficientStorage.Detail>> sources(@RequestParam long projectId, @RequestParam long gasReservoirId,
            @RequestParam String wellName, @RequestParam String operationMode) {
        return ApiResponse.success(calculation.sources(projectId, gasReservoirId, wellName, operationMode));
    }
    @PostMapping("/calculate")
    public ApiResponse<Result> calculate(@RequestBody Input input,
            @RequestHeader(value="token", required=false) String token, @RequestHeader(value="Cookie", required=false) String cookie,
            @RequestHeader(value="Process-Env", required=false) String env) { return ApiResponse.success(calculation.calculate(input, token, cookie, env)); }
    @PostMapping("/records/save")
    public ApiResponse<Map<String, Object>> save(@RequestBody Save input,
            @RequestHeader(value="token", required=false) String token, @RequestHeader(value="Cookie", required=false) String cookie,
            @RequestHeader(value="Process-Env", required=false) String env) { return ApiResponse.success(storage.save(input, token, cookie, env)); }
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
