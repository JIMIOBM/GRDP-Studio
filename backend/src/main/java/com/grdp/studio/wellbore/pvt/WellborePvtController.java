package com.grdp.studio.wellbore.pvt;

import com.grdp.studio.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/wellbore/pvt")
public class WellborePvtController {
    public record Request(Long projectId, Long gasReservoirId, String wellName, double pressureMpa, double temperatureC) {}
    private final WellborePvtService pvt;
    public WellborePvtController(WellborePvtService pvt) { this.pvt = pvt; }

    @GetMapping("/source")
    public ApiResponse<Map<String, Object>> source(@RequestParam Long projectId,
            @RequestParam Long gasReservoirId, @RequestParam String wellName) {
        var source = pvt.first(projectId, gasReservoirId, wellName);
        return ApiResponse.success(Map.of("pvtId", source.pvtId(), "pvtSnapshot", source.snapshot(),
                "gasInput", source.detail().gasInput()));
    }

    @PostMapping("/water-properties")
    public ApiResponse<Map<String, Object>> water(@RequestBody Request request,
            @RequestHeader(value="token", required=false) String token,
            @RequestHeader(value="Cookie", required=false) String cookie,
            @RequestHeader(value="Process-Env", required=false) String environment) {
        WellborePvtService.state(request.pressureMpa(), request.temperatureC());
        var source = pvt.first(request.projectId(), request.gasReservoirId(), request.wellName());
        var session = pvt.open(source, request.projectId(), token, cookie, environment, true);
        var water = session.water().apply(request.pressureMpa(), request.temperatureC());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pvtId", source.pvtId());
        result.put("pvtSnapshot", source.snapshot());
        result.put("gammaG", source.specificGravity());
        result.put("rhoL", water.density());
        result.put("muL", water.viscosity());
        return ApiResponse.success(result);
    }
}
