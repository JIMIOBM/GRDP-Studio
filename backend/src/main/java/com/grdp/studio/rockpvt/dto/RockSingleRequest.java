package com.grdp.studio.rockpvt.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RockSingleRequest(

        @NotNull
        @Positive
        Long projectId,


        @NotNull
        @DecimalMin("0")
        Double porosity,

        /**
         * 0: 胶结砂岩
         * 1: 碳酸盐岩
         */
        @NotNull
        Integer rockType

) {
}