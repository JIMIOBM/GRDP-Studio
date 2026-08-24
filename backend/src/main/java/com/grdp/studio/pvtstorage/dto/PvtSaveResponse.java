package com.grdp.studio.pvtstorage.dto;

public record PvtSaveResponse(
        long pvtId,
        String propertyKind,
        String section,
        int savedRows,
        String status
) {
}
