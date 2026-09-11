package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolve the existing data-management well; never create a pipeline-specific well identity. */
@Service
public class PipelineWellContext {
    private final JdbcTemplate jdbc;
    public PipelineWellContext(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Well(long id,String name) {}
    public Well require(long project,long reservoir,String name) {
        if(project<=0 || reservoir<=0 || name==null || name.isBlank()) throw new BusinessException(400,"请先选择当前井");
        var rows=jdbc.query("SELECT id,well_name FROM project_well_heads WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?",
            (rs,n)->new Well(rs.getLong(1),rs.getString(2)),project,reservoir,name.trim());
        if(rows.size()!=1) throw new BusinessException(400,"当前井不存在或井名不唯一，请检查数据管理中的井资料");
        return rows.getFirst();
    }
}
