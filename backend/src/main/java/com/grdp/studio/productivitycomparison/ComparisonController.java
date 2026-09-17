package com.grdp.studio.productivitycomparison;

import com.grdp.studio.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import static com.grdp.studio.productivitycomparison.ComparisonModels.*;

@RestController
@RequestMapping("/productivity-comparison")
public class ComparisonController {
    private final ComparisonService service;
    public ComparisonController(ComparisonService service) { this.service = service; }
    @GetMapping("/records")
    public ApiResponse<List<RecordSummary>> records(@RequestParam long projectId, @RequestParam long gasReservoirId,
            @RequestParam String wellName, @RequestParam List<String> methods, @RequestParam String pressureMethod,
            @RequestParam(defaultValue = "production") List<String> operationTypes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(service.list(projectId, gasReservoirId, wellName, methods, pressureMethod, startDate, endDate, operationTypes));
    }
    @PostMapping("/calculate")
    public ApiResponse<CalculateResponse> calculate(@Valid @RequestBody CalculateRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv) {
        var headers = new LinkedHashMap<String, String>();
        if (token != null) headers.put("token", token);
        if (cookie != null) headers.put("Cookie", cookie);
        if (processEnv != null) headers.put("Process-Env", processEnv);
        return ApiResponse.success(service.calculate(request, headers));
    }
    @PostMapping("/injection-production/calculate")
    public ApiResponse<DirectionComparisonResponse> compareDirections(@Valid @RequestBody CalculateRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv) {
        var headers = new LinkedHashMap<String, String>();
        if (token != null) headers.put("token", token);
        if (cookie != null) headers.put("Cookie", cookie);
        if (processEnv != null) headers.put("Process-Env", processEnv);
        return ApiResponse.success(service.compareDirections(request, headers));
    }
}
