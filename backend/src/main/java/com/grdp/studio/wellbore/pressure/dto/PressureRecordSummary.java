package com.grdp.studio.wellbore.pressure.dto;

import com.grdp.studio.wellbore.pressure.entity.WellPressureConversionEntity;

public record PressureRecordSummary(
        Long id,
        Integer pressureNo,
        String pressureName,
        Long temperatureId,
        Long pvtId,
        String status
) {
    public static PressureRecordSummary from(WellPressureConversionEntity entity) {
        return new PressureRecordSummary(
                entity.getId(),
                entity.getPressureNo(),
                entity.getPressureName(),
                entity.getTemperatureId(),
                entity.getPvtId(),
                entity.getStatus()
        );
    }
}
