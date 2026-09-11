package com.grdp.studio.wellbore.pressure.dto;

import com.grdp.studio.wellbore.pressure.entity.PressureMethodResultEntity;
import com.grdp.studio.wellbore.pressure.entity.PressureProfileEntity;
import com.grdp.studio.wellbore.pressure.entity.WellPressureConversionEntity;

import java.util.List;
import java.util.Map;

public record PressureRecordDetail(
        WellPressureConversionEntity record,
        List<PressureMethodResultEntity> methods,
        Map<Long, List<PressureProfileEntity>> profiles
) {}
