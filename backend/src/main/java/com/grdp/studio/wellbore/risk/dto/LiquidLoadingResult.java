package com.grdp.studio.wellbore.risk.dto;

public record LiquidLoadingResult(
        String well,
        double qg,
        Double qw,
        double pressureMpa,
        double temperatureC,
        double gasSpecificGravity,
        double liquidDensityKgM3,
        double gasDensityKgM3,
        double zFactor,
        double surfaceTensionMnM,
        double tubingIdMm,
        double actualVelocityMs,
        double criticalVelocityMs,
        double criticalRate1e4M3d,
        double ratio,
        double criticalVelocityTurnerMs,
        double criticalVelocityTurner20Ms,
        double criticalVelocityLiMinMs,
        double ratioTurner,
        double ratioTurner20,
        double ratioLiMin,
        String level,
        String levelKey
) {}
