package com.grdp.studio.softwareintegration.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.preprocessing.*;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/software-integration")
public class EclipsePreprocessingController {
    public record Imported(UUID sourceId, String templateId, String templateVersion,
                           List<EclipsePreprocessingInput.Value> values) { }
    private final EclipsePreprocessingCatalog catalog;
    private final EclipsePreprocessingTables tables;
    private final EclipsePreprocessingValidator validator;
    private final EclipsePreprocessingSources sources;
    private final EclipsePreprocessingWorkflow workflow;
    private final SoftwareIntegrationService projects;
    private final ObjectMapper mapper;

    public EclipsePreprocessingController(EclipsePreprocessingCatalog catalog, EclipsePreprocessingTables tables,
            EclipsePreprocessingValidator validator, EclipsePreprocessingSources sources, EclipsePreprocessingWorkflow workflow,
            SoftwareIntegrationService projects, ObjectMapper mapper) {
        this.catalog = catalog; this.tables = tables; this.validator = validator; this.sources = sources;
        this.workflow = workflow; this.projects = projects; this.mapper = mapper;
    }

    @GetMapping("/eclipse-preprocessing/templates")
    public ApiResponse<List<JsonNode>> templates() { return ApiResponse.success(catalog.summaries()); }

    @GetMapping("/eclipse-preprocessing/templates/{id}")
    public ApiResponse<JsonNode> template(@PathVariable String id) {
        var template = catalog.require(id).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) template).remove("source");
        return ApiResponse.success(template);
    }

    @GetMapping("/eclipse-preprocessing/templates/{id}/download")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable String id, @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(defaultValue = "false") boolean blank) {
        var template = catalog.require(id);
        if (!List.of("xlsx", "csv").contains(format)) throw new BusinessException(400, "请选择xlsx或csv格式");
        byte[] bytes = format.equals("xlsx") ? tables.workbook(template, blank) : tables.csv(template, blank);
        return download(bytes, template.path("caseName").asText() + (blank ? "_空白模板." : "_填写示例.") + format,
                format.equals("xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "text/csv;charset=UTF-8");
    }

    @PostMapping("/projects/{projectId}/eclipse-preprocessing/examples/{id}")
    public ApiResponse<Imported> example(@PathVariable long projectId, @PathVariable String id) {
        projects.getProject(projectId);
        var template = catalog.require(id); var values = tables.examples(template, false);
        var source = sources.save(projectId, template, List.of(), mapper.writeValueAsBytes(values));
        return ApiResponse.success(new Imported(source.id(), id, template.path("version").asText(), values));
    }

    @PostMapping(path = "/projects/{projectId}/eclipse-preprocessing/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Imported> upload(@PathVariable long projectId, @RequestParam String templateId,
            @RequestPart("files") List<MultipartFile> files, @RequestParam(required = false) String mapping) {
        projects.getProject(projectId);
        var template = catalog.require(templateId);
        Map<String, String> columns = Map.of();
        if (mapping != null && !mapping.isBlank()) {
            try { columns = mapper.readValue(mapping, new tools.jackson.core.type.TypeReference<Map<String, String>>() { }); }
            catch (RuntimeException exception) { throw new BusinessException(400, "列名对应必须是编号、数值、单位的JSON对象"); }
        }
        var values = tables.read(files, columns);
        var source = sources.save(projectId, template, files, null);
        return ApiResponse.success(new Imported(source.id(), templateId, template.path("version").asText(), values));
    }

    @PostMapping("/projects/{projectId}/eclipse-preprocessing/validate")
    public ApiResponse<EclipsePreprocessingValidator.Validation> validate(@PathVariable long projectId, @RequestBody EclipsePreprocessingInput input) {
        projects.getProject(projectId);
        sources.require(input.sourceId(), projectId, input.templateId(), input.templateVersion());
        return ApiResponse.success(validator.validate(input));
    }

    @PostMapping("/projects/{projectId}/eclipse-preprocessing/builds")
    public ApiResponse<?> build(@PathVariable long projectId, @RequestBody EclipsePreprocessingInput input) {
        projects.getProject(projectId);
        var validation = validator.validate(input);
        if (!validation.valid()) return new ApiResponse<>(400, "请先修正表格中的错误", validation);
        return ApiResponse.success(workflow.build(projectId, input));
    }

    @GetMapping("/projects/{projectId}/eclipse-preprocessing/builds")
    public ApiResponse<List<EclipsePreprocessingWorkflow.Build>> list(@PathVariable long projectId) { return ApiResponse.success(workflow.list(projectId)); }

    @GetMapping("/projects/{projectId}/eclipse-preprocessing/builds/{id}")
    public ApiResponse<EclipsePreprocessingWorkflow.Build> get(@PathVariable long projectId, @PathVariable UUID id) { return ApiResponse.success(workflow.get(projectId, id)); }

    @PostMapping("/projects/{projectId}/eclipse-preprocessing/builds/{id}/calculate")
    public ApiResponse<EclipsePreprocessingWorkflow.Build> calculate(@PathVariable long projectId, @PathVariable UUID id) { return ApiResponse.success(workflow.calculate(projectId, id)); }

    @GetMapping("/projects/{projectId}/eclipse-preprocessing/builds/{id}/inputs")
    public ApiResponse<EclipsePreprocessingInput> inputs(@PathVariable long projectId, @PathVariable UUID id) { return ApiResponse.success(workflow.inputs(projectId, id)); }

    @GetMapping("/projects/{projectId}/eclipse-preprocessing/builds/{id}/download")
    public ResponseEntity<byte[]> downloadBuild(@PathVariable long projectId, @PathVariable UUID id) {
        return download(workflow.download(projectId, id), "ECLIPSE_" + id + ".zip", "application/zip");
    }

    private static ResponseEntity<byte[]> download(byte[] bytes, String name, String type) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(type)).body(bytes);
    }
}
