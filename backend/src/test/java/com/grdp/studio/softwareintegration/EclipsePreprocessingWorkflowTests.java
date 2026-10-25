package com.grdp.studio.softwareintegration;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.dto.SoftwareIntegrationProjectRequest;
import com.grdp.studio.softwareintegration.preprocessing.*;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(properties = {"grdp.software-integration.dispatcher-enabled=false", "spring.datasource.url=jdbc:h2:mem:preprocessing_flow;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
@Import(SoftwareIntegrationRunFlowTests.FakeWorkerConfiguration.class)
class EclipsePreprocessingWorkflowTests {
    static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"), "grdp-preprocessing-" + UUID.randomUUID());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("grdp.software-integration.storage-root", ROOT::toString);
    }
    @Autowired EclipsePreprocessingCatalog catalog;
    @Autowired EclipsePreprocessingTables tables;
    @Autowired EclipsePreprocessingSources sources;
    @Autowired EclipsePreprocessingWorkflow workflow;
    @Autowired SoftwareIntegrationService projects;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SoftwareIntegrationRunFlowTests.FakeWorkerRunClient worker;

    long project() {
        var request = new SoftwareIntegrationProjectRequest("表格验证_" + UUID.randomUUID(), "独立前处理功能测试");
        return projects.createProject(request).id();
    }
    EclipsePreprocessingInput input(long project, UUID requestId) {
        var template = catalog.require("thermal"); var values = tables.examples(template, false);
        var source = sources.save(project, template, List.of(), mapper.writeValueAsBytes(values));
        return new EclipsePreprocessingInput("thermal", "1.0.0", "表格热采", source.id(), requestId, true, true, values);
    }

    @Test void RepeatedAndConcurrentClicksRegisterOnlyOneNewVersion() throws Exception {
        long project = project(); var input = input(project, UUID.randomUUID());
        EclipsePreprocessingWorkflow.Build first;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> workflow.build(project, input));
            var b = executor.submit(() -> workflow.build(project, input));
            first = a.get(); assertEquals(first.versionId(), b.get().versionId());
        }
        assertEquals(first.versionId(), workflow.build(project, input).versionId());
        var models = projects.getProject(project).models(); assertEquals(1, models.size()); assertEquals(1, models.getFirst().versions().size());
        assertNull(first.runId()); // Validation has not run; no solver invocation or synthetic result is created.
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_eclipse_preprocessing WHERE project_id=?", Integer.class, project));
        assertEquals(input.sourceId(), workflow.inputs(project, input.requestId()).sourceId());
        var zip = workflow.download(project, input.requestId()); assertTrue(zip.length > 1000);
        try (var archive = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            var names = new java.util.HashSet<String>();
            java.util.zip.ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) names.add(entry.getName());
            assertTrue(names.containsAll(List.of("THERM3.DATA", "preprocessing-provenance.json", "preprocessing-inputs.json")));
            assertTrue(names.stream().anyMatch(name -> name.startsWith("sources/")));
        }
        String storageKey = jdbc.queryForObject("SELECT storage_key FROM software_integration_model_version WHERE id=?", String.class, first.versionId());
        Path executableDirectory = ROOT.resolve(storageKey).getParent();
        try (var files = Files.walk(executableDirectory)) {
            assertEquals(List.of("THERM3.DATA"), files.filter(Files::isRegularFile)
                    .map(path -> executableDirectory.relativize(path).toString()).sorted().toList());
        }
        var changed = new EclipsePreprocessingInput(input.templateId(), input.templateVersion(), "另一个名字", input.sourceId(), input.requestId(), true, true, input.values());
        assertThrows(BusinessException.class, () -> workflow.build(project, changed));
    }

    @Test void InvalidInputsAndForeignSourcesCannotLeaveSuccessfulModelsOrRuns() {
        long project = project(), foreign = project(); var valid = input(project, UUID.randomUUID());
        var bad = new EclipsePreprocessingInput(valid.templateId(), valid.templateVersion(), valid.modelName(), valid.sourceId(), valid.requestId(), true, true, List.of());
        assertThrows(BusinessException.class, () -> workflow.build(project, bad));
        assertThrows(BusinessException.class, () -> workflow.build(foreign, valid));
        assertTrue(projects.getProject(project).models().isEmpty()); assertTrue(projects.getProject(foreign).models().isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_eclipse_preprocessing WHERE project_id IN (?,?)", Integer.class, project, foreign));
    }

    @Test void SourceMutationIsDetectedBeforeModelRegistration() throws Exception {
        long project = project(); var valid = input(project, UUID.randomUUID());
        var file = ROOT.resolve("preprocessing/sources/" + valid.sourceId() + "/teaching-input.json");
        Files.writeString(file, "changed");
        assertThrows(BusinessException.class, () -> workflow.build(project, valid));
        assertTrue(projects.getProject(project).models().isEmpty());
    }

    @Test void AFirstCalculationNeedsNoExistingSuccessAndConcurrentClicksQueueOnlyOnce() throws Exception {
        worker.reset(); worker.compositionalAvailable = true;
        long project = project(); var valid = input(project, UUID.randomUUID());
        var built = workflow.build(project, valid);
        assertThrows(BusinessException.class, () -> workflow.calculate(project, valid.requestId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_run WHERE project_id=?", Integer.class, project));
        String inspection = mapper.writeValueAsString(mapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-inspection.json")));
        jdbc.update("UPDATE software_integration_model_version SET status='READY',model_kind='eclipse_300',inspection_json=? WHERE id=?", inspection, built.versionId());
        jdbc.update("UPDATE software_integration_model SET simulator_type='ECLIPSE_300' WHERE id=?", built.modelId());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> workflow.calculate(project, valid.requestId()));
            var b = executor.submit(() -> workflow.calculate(project, valid.requestId()));
            var first = a.get(); var second = b.get(); assertNotNull(first.runId()); assertEquals(first.runId(), second.runId());
            assertEquals(first.runId(), workflow.calculate(project, valid.requestId()).runId());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_run WHERE project_id=?", Integer.class, project));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_run WHERE project_id=? AND status='QUEUED' AND parameters_json='null'", Integer.class, project));
    }
    @Test void DeletedCalculatedModelsRetainArchivesAndDoNotBreakHistory() throws Exception {
        worker.reset(); worker.compositionalAvailable = true;
        long project = project(); var valid = input(project, UUID.randomUUID());
        var built = workflow.build(project, valid);
        byte[] archive = workflow.download(project, valid.requestId());
        String inspection = mapper.writeValueAsString(mapper.readTree(getClass().getResourceAsStream("/eclipse-thermal-inspection.json")));
        jdbc.update("UPDATE software_integration_model_version SET status='READY',model_kind='eclipse_300',inspection_json=? WHERE id=?", inspection, built.versionId());
        jdbc.update("UPDATE software_integration_model SET simulator_type='ECLIPSE_300' WHERE id=?", built.modelId());
        var queued = workflow.calculate(project, valid.requestId());
        jdbc.update("UPDATE software_integration_run SET status='CANCELLED' WHERE id=?", queued.runId());
        projects.deleteModel(project, built.modelId());
        assertEquals("UNAVAILABLE", workflow.list(project).getFirst().status());
        assertEquals("UNAVAILABLE", workflow.calculate(project, valid.requestId()).status());
        assertArrayEquals(archive, workflow.download(project, valid.requestId()));
        assertEquals(valid, workflow.inputs(project, valid.requestId()));
        assertThrows(BusinessException.class, () -> workflow.download(project(), valid.requestId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM software_integration_run WHERE project_id=?", Integer.class, project));
        var next = new EclipsePreprocessingInput(valid.templateId(), valid.templateVersion(), valid.modelName(), valid.sourceId(), UUID.randomUUID(), true, true, valid.values());
        assertNotEquals(built.versionId(), workflow.build(project, next).versionId());
        assertEquals(2, workflow.list(project).size());
    }

    @Test void DeletedUncalculatedVersionsAreTerminalAndStillDownloadable() {
        long project = project(); var valid = input(project, UUID.randomUUID());
        var built = workflow.build(project, valid);
        projects.deleteModel(project, built.modelId());
        assertEquals("UNAVAILABLE", workflow.get(project, valid.requestId()).status());
        assertEquals("UNAVAILABLE", workflow.list(project).getFirst().status());
        assertTrue(workflow.download(project, valid.requestId()).length > 1000);
        assertThrows(BusinessException.class, () -> workflow.calculate(project, valid.requestId()));
    }
}
