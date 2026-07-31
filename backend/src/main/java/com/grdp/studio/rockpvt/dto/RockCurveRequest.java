package com.grdp.studio.rockpvt.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;


public record RockCurveRequest(

        @NotNull
        @Positive
        Long projectId,


        @NotNull
        @DecimalMin("0")
        Double porosityStart,


        @NotNull
        @DecimalMin("0")
        Double porosityEnd,


        @NotNull
        @Positive
        Double porosityStep

) {
}