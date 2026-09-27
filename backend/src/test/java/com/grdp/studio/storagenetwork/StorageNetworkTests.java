package com.grdp.studio.storagenetwork;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.pipeline.PipelineBatch;
import com.grdp.studio.pipeline.PipelineNetworkCalculator;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StorageNetworkTests {
    private static PipelineNetworkCalculator.PipeResult pipe(String id, double inlet, double outlet,
            double rate, double outletC) {
        return new PipelineNetworkCalculator.PipeResult(id, id, "a", "b", inlet, outlet,
                35, outletC, rate, 15, 20);
    }

    @Test void correlationSourceUsesSuccessfulSavedCasesWithoutInventingMissingValues() {
        var device = new PipelineNetworkCalculator.DeviceResult("d", "n", 0, "压缩机", "compressor", "p1",
                10, 11, 30, 32, 20, 125, 20, 200d, 9, 75d, "ok");
        var good = new PipelineBatch.CaseResult("c1", "2026-01-02T08:30", "success", null,
                List.of(pipe("p1", 12, 11, 20, 28), pipe("p2", 11, 9, 20, 25)),
                List.of(device), List.of(), List.of());
        var failed = new PipelineBatch.CaseResult("c2", "2026-01-02T09:30", "error", "失败",
                List.of(), List.of(), List.of(), List.of());
        var result = new PipelineBatch.Result("test", null, List.of(good, failed), 1, 1);
        var rows = StorageNetworkCorrelationService.samplesFrom(7, "A-1", result);
        assertEquals(1, rows.size());
        var row = rows.getFirst();
        assertEquals(12, row.inletPressureMpa());
        assertEquals(9, row.outletPressureMpa());
        assertEquals(3, row.pressureDropMpa());
        assertEquals(20, row.flowRate10k());
        assertEquals(25, row.outletTemperatureC());
        assertEquals(125, row.compressionPowerKw());
        assertEquals(2, row.pipeCount());
    }

    @Test void invalidAndIncompleteCasesNeverBecomeZeroSamples() {
        var invalid = new PipelineBatch.CaseResult("c", "2026-01-01T00:00", "success", null,
                List.of(pipe("p", Double.NaN, 9, 20, 25)), List.of(), List.of(), List.of());
        assertTrue(StorageNetworkCorrelationService.samplesFrom(1, "A", null).isEmpty());
        assertTrue(StorageNetworkCorrelationService.samplesFrom(1, "A",
                new PipelineBatch.Result("test", null, List.of(invalid), 1, 0)).isEmpty());
    }

    @Test void topologyRequiresEveryStorageWellExactlyOnceAndAPathToExternalNetwork() {
        var wells = List.of(new StorageCatalogService.Well(1, "A-1"), new StorageCatalogService.Well(2, "A-2"));
        var graph = validGraph();
        assertDoesNotThrow(() -> StorageNetworkTopologyService.validate(graph, wells));
        var missing = new StorageNetworkTopologyService.Graph(graph.nodes().subList(0, 3), graph.edges(), graph.layout());
        assertThrows(BusinessException.class, () -> StorageNetworkTopologyService.validate(missing, wells));
        var disconnected = new StorageNetworkTopologyService.Graph(graph.nodes(), graph.edges().subList(0, 1), graph.layout());
        assertThrows(BusinessException.class, () -> StorageNetworkTopologyService.validate(disconnected, wells));
    }

    @Test void topologyRejectsCyclesAndNonPositiveEngineeringLimits() {
        var graph = validGraph();
        var cycleEdges = new java.util.ArrayList<>(graph.edges());
        cycleEdges.add(edge("cycle", "out", "station"));
        assertThrows(BusinessException.class, () -> StorageNetworkTopologyService.validate(
                new StorageNetworkTopologyService.Graph(graph.nodes(), cycleEdges, graph.layout()),
                List.of(new StorageCatalogService.Well(1, "A-1"), new StorageCatalogService.Well(2, "A-2"))));
        var bad = new LinkedHashMap<>(graph.edges().getFirst().parameters());
        bad.put("maxFlow10k", 0d);
        var badEdges = new java.util.ArrayList<>(graph.edges());
        badEdges.set(0, new StorageNetworkTopologyService.Edge("e1", "w1", "station", "支线1", bad));
        var badGraph = new StorageNetworkTopologyService.Graph(graph.nodes(), badEdges, graph.layout());
        assertThrows(BusinessException.class, () -> StorageNetworkTopologyService.validate(badGraph,
                List.of(new StorageCatalogService.Well(1, "A-1"), new StorageCatalogService.Well(2, "A-2"))));
    }

    private static StorageNetworkTopologyService.Graph validGraph() {
        Map<String, Object> well = new LinkedHashMap<>(Map.of("elevationM", 0d));
        Map<String, Object> station = new LinkedHashMap<>(Map.of("elevationM", 0d, "capacity10k", 500d, "maxPressureMpa", 30d));
        var nodes = List.of(
                new StorageNetworkTopologyService.Node("w1", "well", "A-1", 1L, well, 0, 0),
                new StorageNetworkTopologyService.Node("w2", "well", "A-2", 2L, well, 0, 100),
                new StorageNetworkTopologyService.Node("station", "gathering", "集气站", null, station, 200, 50),
                new StorageNetworkTopologyService.Node("out", "external", "外输管网", null, station, 400, 50));
        return new StorageNetworkTopologyService.Graph(nodes,
                List.of(edge("e1", "w1", "station"), edge("e2", "w2", "station"), edge("e3", "station", "out")),
                new LinkedHashMap<>(Map.of("x", 0d, "y", 0d, "zoom", 1d)));
    }

    private static StorageNetworkTopologyService.Edge edge(String id, String source, String target) {
        return new StorageNetworkTopologyService.Edge(id, source, target, id,
                new LinkedHashMap<>(Map.of("lengthM", 1000d, "diameterMm", 200d,
                        "roughnessMm", .03d, "maxFlow10k", 500d)));
    }
}
