package com.grdp.studio.wellbore.sand.dto;

public record SandProductionResult(
        String well,
        String proppantType,
        String proppantTypeLabel,
        double proppantVolumeM3,
        double fractureAreaM2,
        double criticalVelocityMS,
        boolean velocityOverridden,
        double criticalRate1e4M3d,
        Double actualRate1e4M3d,
        Double ratioPercent,
        String riskLevel,
        String levelKey,
        String riskDescription,
        String interpolationNote
) {}
