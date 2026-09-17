package com.grdp.studio.productivitycomparison;

import com.grdp.studio.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import static com.grdp.studio.productivitycomparison.StorageComparisonModels.*;

@RestController
@RequestMapping("/storage-productivity-comparison")
public class StorageComparisonController {
    private final StorageComparisonService service;
    public StorageComparisonController(StorageComparisonService service) { this.service = service; }

    @PostMapping("/injection-production/calculate")
    public ApiResponse<DirectionResponse> calculateDirections(@Valid @RequestBody DirectionRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv) {
        var headers = new LinkedHashMap<String, String>();
        if (token != null) headers.put("token", token);
        if (cookie != null) headers.put("Cookie", cookie);
        if (processEnv != null) headers.put("Process-Env", processEnv);
        return ApiResponse.success(service.calculateDirections(request, headers));
    }

    @PostMapping("/multi-method/calculate")
    public ApiResponse<MultiMethodResponse> calculateMethods(@Valid @RequestBody MultiMethodRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv) {
        var headers = new LinkedHashMap<String, String>();
        if (token != null) headers.put("token", token);
        if (cookie != null) headers.put("Cookie", cookie);
        if (processEnv != null) headers.put("Process-Env", processEnv);
        return ApiResponse.success(service.calculateMethods(request, headers));
    }

    @PostMapping("/multi-period/calculate")
    public ApiResponse<Response> calculate(@Valid @RequestBody Request request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv) {
        var headers = new LinkedHashMap<String, String>();
        if (token != null) headers.put("token", token);
        if (cookie != null) headers.put("Cookie", cookie);
        if (processEnv != null) headers.put("Process-Env", processEnv);
        return ApiResponse.success(service.calculate(request, headers));
    }
}
