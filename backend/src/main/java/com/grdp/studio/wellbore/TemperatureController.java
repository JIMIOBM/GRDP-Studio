package com.grdp.studio.wellbore;

import com.grdp.studio.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wellbore/temperature")
public class TemperatureController {
    private final TemperatureService service;
    public TemperatureController(TemperatureService service) { this.service = service; }
    @PostMapping("/calculate")
    public ApiResponse<TemperatureCalculator.Result> calculate(@RequestBody TemperatureRequest request,
            @RequestHeader(value = "token", required = false) String token,
            @RequestHeader(value = "Cookie", required = false) String cookie,
            @RequestHeader(value = "Process-Env", required = false) String processEnv) {
        return ApiResponse.success(service.calculate(request, token, cookie, processEnv));
    }
}
