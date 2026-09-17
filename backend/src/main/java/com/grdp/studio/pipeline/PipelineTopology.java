package com.grdp.studio.pipeline;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** A topology may be an incomplete draft; only structurally valid references are persisted. */
@Service
public class PipelineTopology {
    public record Node(@NotBlank @Size(max=64) String id, @NotBlank String type,
            @NotBlank @Size(max=100) String name, @NotNull Map<String,Object> parameters,
            double x, double y) {}
    public record Edge(@NotBlank @Size(max=64) String id, @NotBlank @Size(max=64) String source,
            @NotBlank @Size(max=64) String target, @NotBlank @Size(max=100) String name,
            @NotNull Map<String,Object> parameters) {}
    public record Graph(@NotNull @Size(max=200) List<@NotNull @Valid Node> nodes,
            @NotNull @Size(max=400) List<@NotNull @Valid Edge> edges,
            @NotNull Map<String,Object> settings, @NotNull Map<String,Object> layout) {}
    public record Save(@Positive long projectId, @Positive long gasReservoirId,
            @NotBlank @Size(max=100) String wellName,
            @PositiveOrZero int revision, @NotNull @Valid Graph graph) {}
    public record Detail(int revision, Graph graph) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PipelineWellContext wells;
    public PipelineTopology(JdbcTemplate jdbc, ObjectMapper json,PipelineWellContext wells) { this.jdbc=jdbc; this.json=json; this.wells=wells; }
    public Detail detail(long project,long reservoir,String well) {
        long wellId=wells.require(project,reservoir,well).id();
        return find(wellId);
    }
    private Detail find(long wellId) {
        var rows=jdbc.query("SELECT revision,graph_json FROM pipeline_topology WHERE well_id=?",
            (rs,n)->new Detail(rs.getInt(1),json.readValue(rs.getString(2),Graph.class)),wellId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    void validate(Graph graph,String wellName) {
        var wellNodes=graph.nodes().stream().filter(n->n.type().equals("well")).toList();
        if(wellNodes.size()!=1 || !wellNodes.getFirst().name().equals(wellName)) throw new BusinessException(400,"拓扑必须且只能包含当前井的固定井口");
        if(graph.edges().stream().anyMatch(e->e.target().equals(wellNodes.getFirst().id()))) throw new BusinessException(400,"当前井井口只能作为入口");
        Set<String> ids=new HashSet<>(), edgeIds=new HashSet<>(), pairs=new HashSet<>();
        for(Node node:graph.nodes()) {
            if(!ids.add(node.id())) throw new BusinessException(400,"节点ID重复");
            if(!Set.of("well","junction","split","valve","compressor","station").contains(node.type())) throw new BusinessException(400,"未知节点类型");
            if(!Double.isFinite(node.x()) || !Double.isFinite(node.y())) throw new BusinessException(400,"节点坐标无效");
        }
        for(Edge edge:graph.edges()) {
            if(!edgeIds.add(edge.id()) || !pairs.add(edge.source()+"/"+edge.target())) throw new BusinessException(400,"管段ID或连接重复");
            if(!ids.contains(edge.source()) || !ids.contains(edge.target()) || edge.source().equals(edge.target())) throw new BusinessException(400,"管段端点不存在或连接自身");
        }
        if(json.writeValueAsString(graph).length()>2_000_000) throw new BusinessException(400,"拓扑数据过大");
    }
    @Transactional
    public Detail save(Save request) {
        var well=wells.require(request.projectId(),request.gasReservoirId(),request.wellName());
        validate(request.graph(),well.name());
        String graph=json.writeValueAsString(request.graph());
        if(request.revision()==0) {
            try {jdbc.update("INSERT INTO pipeline_topology(well_id,revision,graph_json,layout_json) VALUES(?,1,?,?)",well.id(),graph,json.writeValueAsString(request.graph().layout()));}
            catch(DuplicateKeyException e) {throw new BusinessException(409,"该井拓扑已保存，请重新加载后再修改");}
        } else if(jdbc.update("UPDATE pipeline_topology SET graph_json=?,layout_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE well_id=? AND revision=?",
                graph,json.writeValueAsString(request.graph().layout()),well.id(),request.revision())!=1)
            throw new BusinessException(409,"该井拓扑已变化，请重新加载后再保存");
        long id=jdbc.queryForObject("SELECT id FROM pipeline_topology WHERE well_id=?",Long.class,well.id());
        jdbc.update("DELETE FROM pipeline_topology_edge WHERE topology_id=?",id);
        jdbc.update("DELETE FROM pipeline_topology_node WHERE topology_id=?",id);
        for(Node node:request.graph().nodes()) jdbc.update("INSERT INTO pipeline_topology_node(topology_id,node_key,node_type,name,parameters_json) VALUES(?,?,?,?,?)",
            id,node.id(),node.type(),node.name(),json.writeValueAsString(node.parameters()));
        for(Edge edge:request.graph().edges()) jdbc.update("INSERT INTO pipeline_topology_edge(topology_id,edge_key,source_key,target_key,name,parameters_json) VALUES(?,?,?,?,?,?)",
            id,edge.id(),edge.source(),edge.target(),edge.name(),json.writeValueAsString(edge.parameters()));
        return find(well.id());
    }
}

@RestController
@RequestMapping("/pipeline-capacity/topology")
class PipelineTopologyController {
    private final PipelineTopology storage;
    PipelineTopologyController(PipelineTopology storage) { this.storage=storage; }
    @GetMapping
    public ApiResponse<PipelineTopology.Detail> detail(@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName) {
        return ApiResponse.success(storage.detail(projectId,gasReservoirId,wellName));
    }
    @PutMapping
    public ApiResponse<PipelineTopology.Detail> save(@Valid @RequestBody PipelineTopology.Save request) { return ApiResponse.success(storage.save(request)); }
}
