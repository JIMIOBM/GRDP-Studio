package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.grdp.studio.pipeline.PipelineDtos.*;

/** The editable well model; calculated snapshots are saved exclusively by PipelineBatch. */
@Service
public class PipelineStorage {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PipelineWellContext wells;
    private final PipelineFlowTopology flowTopology;
    public PipelineStorage(JdbcTemplate jdbc,ObjectMapper json,PipelineWellContext wells,PipelineFlowTopology flowTopology) {
        this.jdbc=jdbc;this.json=json;this.wells=wells;this.flowTopology=flowTopology;
    }
    public Detail detail(long project,long reservoir,String well) {
        return find(wells.require(project,reservoir,well).id());
    }
    private Detail find(long wellId) {
        var rows=jdbc.query("SELECT id,revision,topology_revision,input_json FROM pipeline_model WHERE well_id=?",
            (rs,n)->new Detail(rs.getLong(1),rs.getInt(2),rs.getInt(3),json.readValue(rs.getString(4),Input.class)),wellId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Transactional
    public Detail saveSection(String scope,SectionSaveRequest request) {
        long wellId=wells.require(request.projectId(),request.gasReservoirId(),request.wellName()).id();
        Detail current=find(wellId);
        if((current==null && request.revision()!=0) || (current!=null && current.revision()!=request.revision()))
            throw new BusinessException(409,"该井模型已变化，请重新加载后再保存");
        if("boundary".equals(scope)) {
            if(request.input()==null)throw new BusinessException(400,"请填写边界条件");
            flowTopology.validateBoundary(request.projectId(),request.gasReservoirId(),request.wellName(),request.input().boundary());
        }
        Input merged=PipelineModelSections.merge(scope,current==null?PipelineModelSections.empty():current.input(),request.input());
        String input=json.writeValueAsString(merged);
        if(current==null) {
            try { jdbc.update("INSERT INTO pipeline_model(well_id,revision,topology_revision,input_json) VALUES(?,1,0,?)",wellId,input); }
            catch(DuplicateKeyException e) { throw new BusinessException(409,"该井模型已保存，请重新加载后再修改"); }
        } else if(jdbc.update("UPDATE pipeline_model SET input_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE well_id=? AND revision=?",
                input,wellId,request.revision())!=1)
            throw new BusinessException(409,"该井模型已变化，请重新加载后再保存");
        // A boundary draft does not replace the topology or the last batch input snapshot.
        return find(wellId);
    }
}
