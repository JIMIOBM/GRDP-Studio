package com.grdp.studio.pvtstorage.dto;

import com.grdp.studio.pvtstorage.entity.WellPvtEntity;

/** 左侧项目树展示 PVT 节点所需的最小数据。 */
public record PvtRecordSummary(
        long pvtId,
        int pvtNo,
        String pvtName,
        String status,
        String sourceType,
        String lastCalculatedKind
) {
    public static PvtRecordSummary from(WellPvtEntity entity) {
        return new PvtRecordSummary(
                entity.getId(),
                entity.getPvtNo(),
                entity.getPvtName(),
                entity.getStatus(),
                entity.getSourceType(),
                entity.getLastCalculatedKind()
        );
    }
}
