package com.grdp.studio.storagenetwork;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** 库级地面管网拓扑。与单井管束拓扑分开保存，避免把井口支线误当成库群总管网。 */
@Service
public class StorageNetworkTopologyService {
    public record Node(@NotBlank @Size(max = 64) String id,
                       @NotBlank String type,
                       @NotBlank @Size(max = 100) String name,
                       Long wellId,
                       @NotNull Map<String, Object> parameters,
                       double x,
                       double y) {}
    public record Edge(@NotBlank @Size(max = 64) String id,
                       @NotBlank @Size(max = 64) String source,
                       @NotBlank @Size(max = 64) String target,
                       @NotBlank @Size(max = 100) String name,
                       @NotNull Map<String, Object> parameters) {}
    public record Graph(@NotNull @Size(max = 200) List<@NotNull @Valid Node> nodes,
                        @NotNull @Size(max = 400) List<@NotNull @Valid Edge> edges,
                        @NotNull Map<String, Object> layout) {}
    public record Save(@Positive long projectId,
                       @Positive long gasReservoirId,
                       @Positive long storageId,
                       @PositiveOrZero int revision,
                       @NotNull @Valid Graph graph) {}
    public record Detail(int revision, Graph graph) {}

    private static final Set<String> NODE_TYPES = Set.of(
            "well", "junction", "gathering", "compressor", "valve", "metering", "external");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final StorageCatalogService catalog;

    public StorageNetworkTopologyService(JdbcTemplate jdbc, ObjectMapper json, StorageCatalogService catalog) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
    }

    public Detail detail(long projectId, long gasReservoirId, long storageId) {
        catalog.requireScope(projectId, gasReservoirId, storageId);
        List<Detail> rows = jdbc.query("SELECT revision,graph_json FROM project_storage_network_topology WHERE storage_id=?",
                (rs, n) -> new Detail(rs.getInt(1), json.readValue(rs.getString(2), Graph.class)), storageId);
        return rows.isEmpty() ? new Detail(0, seed(storageId, projectId, gasReservoirId)) : rows.getFirst();
    }

    Graph seed(long storageId, long projectId, long gasReservoirId) {
        List<StorageCatalogService.Well> wells = catalog.wells(storageId, projectId, gasReservoirId);
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        double centerY = Math.max(220, 90 + Math.max(0, wells.size() - 1) * 42);
        Node station = new Node("gathering-1", "gathering", "集气站", null,
                new LinkedHashMap<>(Map.of("elevationM", 0d, "capacity10k", 1000d, "maxPressureMpa", 30d)),
                520, centerY);
        Node external = new Node("external-1", "external", "外输管网", null,
                new LinkedHashMap<>(Map.of("elevationM", 0d, "capacity10k", 1000d, "maxPressureMpa", 30d)),
                790, centerY);
        nodes.add(station);
        nodes.add(external);
        int index = 0;
        for (StorageCatalogService.Well well : wells) {
            double y = 90 + index * 84;
            String nodeId = "well-" + well.id();
            nodes.add(new Node(nodeId, "well", well.wellName(), well.id(),
                    new LinkedHashMap<>(Map.of("elevationM", 0d, "capacity10k", 100d, "maxPressureMpa", 30d)),
                    150, y));
            edges.add(new Edge("edge-well-" + well.id(), nodeId, station.id(), well.wellName() + "集气支线",
                    defaultPipe(1000d, 150d, 100d)));
            index++;
        }
        edges.add(new Edge("edge-export-1", station.id(), external.id(), "外输干线",
                defaultPipe(3000d, 400d, 1000d)));
        return new Graph(nodes, edges, new LinkedHashMap<>(Map.of("x", 0d, "y", 0d, "zoom", 1d)));
    }

    private static Map<String, Object> defaultPipe(double lengthM, double diameterMm, double maxFlow10k) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("lengthM", lengthM);
        values.put("diameterMm", diameterMm);
        values.put("roughnessMm", .03d);
        values.put("maxFlow10k", maxFlow10k);
        return values;
    }

    @Transactional
    public Detail save(Save request) {
        catalog.requireScope(request.projectId(), request.gasReservoirId(), request.storageId());
        validate(request.graph(), catalog.wells(request.storageId(), request.projectId(), request.gasReservoirId()));
        String graph = json.writeValueAsString(request.graph());
        if (graph.length() > 2_000_000) throw new BusinessException(400, "库级管网拓扑数据过大");
        if (request.revision() == 0) {
            try {
                jdbc.update("INSERT INTO project_storage_network_topology(storage_id,revision,graph_json) VALUES(?,1,?)",
                        request.storageId(), graph);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(409, "该储气库拓扑已保存，请重新加载后再修改");
            }
        } else if (jdbc.update("""
                UPDATE project_storage_network_topology
                SET revision=revision+1,graph_json=?,updated_at=CURRENT_TIMESTAMP
                WHERE storage_id=? AND revision=?
                """, graph, request.storageId(), request.revision()) != 1) {
            throw new BusinessException(409, "该储气库拓扑已变化，请重新加载后再保存");
        }
        return detail(request.projectId(), request.gasReservoirId(), request.storageId());
    }

    static void validate(Graph graph, List<StorageCatalogService.Well> wells) {
        if (graph == null || graph.nodes() == null || graph.edges() == null)
            throw new BusinessException(400, "请填写库级管网拓扑");
        Map<Long, String> expectedWells = new LinkedHashMap<>();
        for (StorageCatalogService.Well well : wells) expectedWells.put(well.id(), well.wellName());
        Set<String> nodeIds = new HashSet<>();
        Set<Long> actualWells = new HashSet<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        int externalCount = 0;
        for (Node node : graph.nodes()) {
            if (!nodeIds.add(node.id())) throw new BusinessException(400, "拓扑节点标识重复");
            if (!NODE_TYPES.contains(node.type())) throw new BusinessException(400, "存在未知的地面管网节点类型");
            if (!Double.isFinite(node.x()) || !Double.isFinite(node.y()))
                throw new BusinessException(400, node.name() + "：节点坐标无效");
            if (node.type().equals("well")) {
                if (node.wellId() == null || !expectedWells.containsKey(node.wellId()))
                    throw new BusinessException(400, node.name() + "：井节点不属于当前储气库");
                if (!actualWells.add(node.wellId())) throw new BusinessException(400, "同一口井不能重复出现在拓扑中");
                if (!Objects.equals(expectedWells.get(node.wellId()), node.name()))
                    throw new BusinessException(400, "井节点名称必须与储气库井目录一致");
            } else if (node.wellId() != null) throw new BusinessException(400, node.name() + "：非井节点不能关联井ID");
            if (node.type().equals("external")) externalCount++;
            requireNumber(node.parameters(), "elevationM", node.name() + "：请填写有效高程");
            if (Set.of("gathering", "compressor", "metering", "external").contains(node.type())) {
                requireFinite(node.parameters(), "capacity10k", true, node.name() + "：请填写正数处理能力");
                requireFinite(node.parameters(), "maxPressureMpa", true, node.name() + "：请填写正数压力上限");
            }
            outgoing.put(node.id(), new ArrayList<>());
            incoming.put(node.id(), 0);
        }
        if (!actualWells.equals(expectedWells.keySet()))
            throw new BusinessException(400, "拓扑必须包含当前储气库的全部井，且每口井只能出现一次");
        if (externalCount != 1) throw new BusinessException(400, "拓扑必须且只能包含一个外输管网节点");
        Set<String> edgeIds = new HashSet<>(), pairs = new HashSet<>();
        for (Edge edge : graph.edges()) {
            if (!edgeIds.add(edge.id()) || !pairs.add(edge.source() + "/" + edge.target()))
                throw new BusinessException(400, "管段标识或连接关系重复");
            if (!nodeIds.contains(edge.source()) || !nodeIds.contains(edge.target()) || edge.source().equals(edge.target()))
                throw new BusinessException(400, edge.name() + "：管段端点不存在或连接自身");
            requireFinite(edge.parameters(), "lengthM", true, edge.name() + "：请填写正数管长");
            requireFinite(edge.parameters(), "diameterMm", true, edge.name() + "：请填写正数内径");
            requireFinite(edge.parameters(), "roughnessMm", false, edge.name() + "：请填写非负粗糙度");
            requireFinite(edge.parameters(), "maxFlow10k", true, edge.name() + "：请填写正数输量上限");
            outgoing.get(edge.source()).add(edge.target());
            incoming.merge(edge.target(), 1, Integer::sum);
        }
        for (Node node : graph.nodes()) {
            if (node.type().equals("well") && incoming.get(node.id()) > 0)
                throw new BusinessException(400, node.name() + "：井节点只能作为供气入口");
        }
        Node external = graph.nodes().stream().filter(n -> n.type().equals("external")).findFirst().orElseThrow();
        if (!outgoing.get(external.id()).isEmpty()) throw new BusinessException(400, "外输管网节点必须是最终出口");
        assertAcyclic(graph.nodes(), outgoing);
        for (Node node : graph.nodes()) if (node.type().equals("well") && !reaches(node.id(), external.id(), outgoing, new HashSet<>()))
            throw new BusinessException(400, node.name() + "：必须通过管段连接到外输管网");
    }

    private static void assertAcyclic(List<Node> nodes, Map<String, List<String>> outgoing) {
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (Node node : nodes) if (cycle(node.id(), outgoing, visiting, visited))
            throw new BusinessException(400, "管网连接不能形成循环");
    }

    private static boolean cycle(String id, Map<String, List<String>> outgoing, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return false;
        if (!visiting.add(id)) return true;
        for (String next : outgoing.getOrDefault(id, List.of())) if (cycle(next, outgoing, visiting, visited)) return true;
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    private static boolean reaches(String current, String target, Map<String, List<String>> outgoing, Set<String> visited) {
        if (current.equals(target)) return true;
        if (!visited.add(current)) return false;
        for (String next : outgoing.getOrDefault(current, List.of())) if (reaches(next, target, outgoing, visited)) return true;
        return false;
    }

    private static void requireFinite(Map<String, Object> values, String key, boolean positive, String message) {
        Object value = values == null ? null : values.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || positive && number.doubleValue() <= 0 || !positive && number.doubleValue() < 0)
            throw new BusinessException(400, message);
    }

    private static void requireNumber(Map<String, Object> values, String key, String message) {
        Object value = values == null ? null : values.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new BusinessException(400, message);
    }
}
