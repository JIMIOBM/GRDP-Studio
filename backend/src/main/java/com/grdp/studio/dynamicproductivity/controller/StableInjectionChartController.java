package com.grdp.studio.dynamicproductivity.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.productivitycomparison.ComparisonPseudoPressure;
import com.grdp.studio.productivitycomparison.ComparisonRepository;
import com.grdp.studio.productivitycomparison.ComparisonRepository.PvtSnapshot;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Chart-only pressure potentials. Does not change A/B, AOF, or saved calculation records. */
@RestController
@RequestMapping("/stable-injection-chart")
public class StableInjectionChartController {
    private final ComparisonPseudoPressure pseudo;
    private final ComparisonRepository repository;
    public StableInjectionChartController(ComparisonPseudoPressure pseudo, ComparisonRepository repository) {
        this.pseudo = pseudo; this.repository = repository;
    }
    public record Request(long projectId, long gasReservoirId, String wellName, PvtSnapshot pvt) {}
    public record Potential(double pressure, double value) {}

    @PostMapping("/potentials")
    public ApiResponse<List<Potential>> potentials(@RequestBody Request request,
            @RequestHeader(value="token", required=false) String token,
            @RequestHeader(value="Cookie", required=false) String cookie,
            @RequestHeader(value="Process-Env", required=false) String processEnv) {
        repository.requireWell(request.projectId(), request.gasReservoirId(), request.wellName());
        Double maximum = request.pvt() == null ? null : request.pvt().originalPressure();
        if (maximum == null || !Double.isFinite(maximum) || maximum < 1 || maximum > 200)
            throw new BusinessException(400, "原始地层压力需在1～200 MPa内，保证十条曲线处于拟压力接口范围");
        var headers = new LinkedHashMap<String, String>();
        if (token != null) headers.put("token", token);
        if (cookie != null) headers.put("Cookie", cookie);
        if (processEnv != null) headers.put("Process-Env", processEnv);
        var calculator = pseudo.calculator(request.projectId(), request.pvt(), headers);
        var points = new ArrayList<Potential>();
        // One shared pressure grid contains all ten reservoir pressures; no interpolation of m(p).
        for (int i = 4; i <= 40; i++) {
            double pressure = maximum * (i / 40.0);
            double value = calculator.applyAsDouble(pressure);
            if (!points.isEmpty() && value <= points.getLast().value())
                throw new BusinessException(502, "拟压力结果未随压力递增，无法生成注气曲线，请重试");
            points.add(new Potential(pressure, value));
        }
        return ApiResponse.success(points);
    }
}
