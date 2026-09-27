package com.grdp.studio.wellbore.erosion.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.erosion.dto.*;
import com.grdp.studio.wellbore.erosion.model.ErosionCalculator;
import com.grdp.studio.wellbore.pvt.WellborePvtService;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class ErosionCalculationService {
    public record Calculation(ErosionRequest input, ErosionResult result) {}
    public record PropertiesRequest(Long projectId, Long gasReservoirId, String wellName, Long pvtId,
                                    Double pressureMpa, Double temperatureC, Map<String, Object> pvtSnapshot) {}
    public record Properties(Long pvtId, Map<String, Object> pvtSnapshot, double gasDensityKgM3,
                             double liquidDensityKgM3, double gasVolumeFactor) {}
    private final WellborePvtService pvt;
    private final ErosionCalculator calculator = new ErosionCalculator();
    public ErosionCalculationService(WellborePvtService pvt) { this.pvt = pvt; }

    public Properties properties(PropertiesRequest r, String token, String cookie, String environment) {
        if (r.pressureMpa() == null || r.temperatureC() == null) throw new BusinessException(400, "请填写当前压力、温度");
        WellborePvtService.state(r.pressureMpa(), r.temperatureC());
        var source = pvt.selected(r.pvtId(), r.projectId(), r.gasReservoirId(), r.wellName());
        pvt.validateSnapshot(source, r.pvtId(), r.pvtSnapshot());
        var session = pvt.open(source, r.projectId(), token, cookie, environment, false);
        var gas = session.gas().apply(r.pressureMpa(), r.temperatureC());
        var water = session.water().apply(r.pressureMpa(), r.temperatureC());
        return new Properties(source.pvtId(), source.snapshot(), gas.density(), water.density(), gas.volumeFactor());
    }

    public Calculation calculate(ErosionRequest input, String token, String cookie, String environment) {
        // Validate submitted parameters before any remote work; never accept caller-supplied result fields.
        try { calculator.validateInput(input); }
        catch (IllegalArgumentException ex) { return new Calculation(input, calculator.calculate(input)); }
        var p = properties(new PropertiesRequest(input.projectId(), input.gasReservoirId(), input.wellName(),
                input.pvtId(), input.pressureMpa(), input.temperatureC(), input.pvtSnapshot()), token, cookie, environment);
        var resolved = input.withProperties(p.pvtId(), p.pvtSnapshot(), p.gasDensityKgM3(), p.liquidDensityKgM3(), p.gasVolumeFactor());
        return new Calculation(resolved, calculator.calculate(resolved));
    }
}
