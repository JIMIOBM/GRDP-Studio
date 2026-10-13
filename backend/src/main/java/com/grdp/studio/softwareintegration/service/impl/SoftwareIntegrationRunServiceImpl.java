package com.grdp.studio.softwareintegration.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.grdp.studio.softwareintegration.client.HttpWorkerRunClient.WorkerClientException;
import com.grdp.studio.softwareintegration.client.WorkerRunClient;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationArtifactResponse;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationCreateRunRequest;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunDetailResponse;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunEventResponse;
import com.grdp.studio.softwareintegration.dto.run.SoftwareIntegrationRunSummaryResponse;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationProjectEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationArtifactEntity;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStore;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationModelVersionMapper;
import com.grdp.studio.softwareintegration.mapper.SoftwareIntegrationProjectMapper;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationRunService;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationCapabilityService;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import com.grdp.studio.softwareintegration.support.EclipseCompletionParameters;
import com.grdp.studio.softwareintegration.support.EclipseCompletionFactorParameters;
import com.grdp.studio.softwareintegration.support.EclipseCompletionGeometryParameters;
import com.grdp.studio.softwareintegration.support.EclipseNewWellParameters;
import com.grdp.studio.softwareintegration.support.EclipseNewCompletionParameters;
import com.grdp.studio.softwareintegration.support.EclipsePvtwParameters;
import com.grdp.studio.softwareintegration.support.EclipseOilPvtParameters;
import com.grdp.studio.softwareintegration.support.EclipseGasPvtParameters;
import com.grdp.studio.softwareintegration.support.EclipseRelpermParameters;
import com.grdp.studio.softwareintegration.support.EclipseScheduleParameters;
import com.grdp.studio.softwareintegration.support.EclipseProductionTargetParameters;
import com.grdp.studio.softwareintegration.support.EclipsePressureControlParameters;
import com.grdp.studio.softwareintegration.support.EclipseProductionControlParameters;
import com.grdp.studio.softwareintegration.support.EclipseThpControlParameters;
import com.grdp.studio.softwareintegration.support.EclipseNewDatesParameters;
import com.grdp.studio.softwareintegration.support.EclipseTstepParameters;
import com.grdp.studio.softwareintegration.support.EclipseGroupProductionParameters;
import com.grdp.studio.softwareintegration.support.EclipseGroupRateParameters;
import com.grdp.studio.softwareintegration.support.EclipseGroupWaterProductionParameters;
import com.grdp.studio.softwareintegration.support.EclipseAdvancedInjectionParameters;
import com.grdp.studio.softwareintegration.support.EclipseControlPlanParameters;
import com.grdp.studio.softwareintegration.support.EclipseGroupStructureParameters;
import com.grdp.studio.softwareintegration.support.EclipseGroupInjectionParameters;
import com.grdp.studio.softwareintegration.support.EclipseGroupGasInjectionParameters;
import com.grdp.studio.softwareintegration.support.EclipseHistoryForecastParameters;
import com.grdp.studio.softwareintegration.support.EclipseRestartForecastParameters;
import com.grdp.studio.softwareintegration.support.EclipseGridFieldParameters;
import com.grdp.studio.softwareintegration.support.EclipseGridRegionParameters;
import com.grdp.studio.softwareintegration.support.NetworkChokeBeanSizeParameters;
import com.grdp.studio.softwareintegration.support.NetworkFlowlineDiameterParameters;
import com.grdp.studio.softwareintegration.support.WellTubingDiameterParameters;
import com.grdp.studio.softwareintegration.support.NetworkConnectionParameters;
import com.grdp.studio.softwareintegration.support.PipesimNetworkInspectionValidator;
import com.grdp.studio.softwareintegration.support.PipesimWellInspectionValidator;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationDiagnosticSanitizer;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationRunExceptionHandler.RunException;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationStorageKeyNormalizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Service
public class SoftwareIntegrationRunServiceImpl implements SoftwareIntegrationRunService {
    private static final int ECLIPSE_TIMEOUT_SECONDS = 1800;
    private static final Set<String> WELL_RUN_TYPES = Set.of("nodal", "profile", "combined", "sensitivity", "gas-lift-performance", "gas-lift-diagnostics", "vfp-tables", "esp-curves", "esp-parameter", "trajectory");
    private static final Set<String> RUN_TYPES = Set.of("nodal", "profile", "combined", "sensitivity", "gas-lift-performance", "gas-lift-diagnostics", "vfp-tables", "esp-curves", "esp-parameter", "trajectory", "network", "system-analysis", "network-optimizer", "network-well-performance-curves", "eclipse");
    private final SoftwareIntegrationRunStore runStore;
    private final SoftwareIntegrationModelVersionMapper versionMapper;
    private final SoftwareIntegrationModelMapper modelMapper;
    private final SoftwareIntegrationProjectMapper projectMapper;
    private final SoftwareIntegrationStorageKeyNormalizer normalizer;
    private final SoftwareIntegrationProperties properties;
    private final WorkerRunClient workerClient;
    private final SoftwareIntegrationCapabilityService capabilityService;
    private final ObjectMapper objectMapper;

    public SoftwareIntegrationRunServiceImpl(SoftwareIntegrationRunStore runStore,
                                             SoftwareIntegrationModelVersionMapper versionMapper,
                                             SoftwareIntegrationModelMapper modelMapper,
                                             SoftwareIntegrationProjectMapper projectMapper,
                                             SoftwareIntegrationStorageKeyNormalizer normalizer,
                                             SoftwareIntegrationProperties properties,
                                             WorkerRunClient workerClient,
                                             SoftwareIntegrationCapabilityService capabilityService,
                                             ObjectMapper objectMapper) {
        this.runStore = runStore;
        this.versionMapper = versionMapper;
        this.modelMapper = modelMapper;
        this.projectMapper = projectMapper;
        this.normalizer = normalizer;
        this.properties = properties;
        this.workerClient = workerClient;
        this.capabilityService = capabilityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public SoftwareIntegrationRunSummaryResponse create(long versionId, SoftwareIntegrationCreateRunRequest request) {
        if (!request.isParametersProvided()) {
            throw new RunException(HttpStatus.BAD_REQUEST, "必须显式提供 parameters");
        }
        String runType = request.getRunType();
        if (!RUN_TYPES.contains(runType)) {
            throw new RunException(HttpStatus.BAD_REQUEST, "runType 必须为 nodal、profile、combined、sensitivity、gas-lift-performance、gas-lift-diagnostics、vfp-tables、esp-curves、esp-parameter、trajectory、network、system-analysis、network-optimizer 或 eclipse");
        }
        SoftwareIntegrationModelVersionEntity version = requireVersion(versionId);
        if (simulatorType(version.getModelKind()) == null) {
            throw new RunException(HttpStatus.CONFLICT, "模型版本缺少已验证类型，请重新验证");
        }
        JsonNode scenario = objectMapper.valueToTree(request.getParameters());
        // The application omits null Map values when serializing. This contract distinguishes
        // an explicit null reference (RATE/RESV) from a missing required reference field.
        if (request.getParameters() instanceof java.util.Map<?, ?> input
                && EclipseAdvancedInjectionParameters.SCHEMA.equals(input.get("schemaVersion"))
                && input.containsKey("referenceGroup") && input.get("referenceGroup") == null
                && scenario instanceof tools.jackson.databind.node.ObjectNode object) {
            object.putNull("referenceGroup");
        }
        boolean eclipseModelCandidate = "ECLIPSE_100".equals(simulatorType(version.getModelKind())) || "ECLIPSE_300".equals(simulatorType(version.getModelKind()));
        JsonNode persistedWellInspection = PipesimWellInspectionValidator.supports(version.getModelKind())
                ? PipesimWellInspectionValidator.parsePersisted(version.getInspectionJson()) : null;
        JsonNode persistedScenarioInspection = "network".equals(version.getModelKind())
                ? PipesimNetworkInspectionValidator.parsePersisted(version.getInspectionJson()) : persistedWellInspection;
        if (!eclipseModelCandidate && !com.grdp.studio.softwareintegration.support.WellScenarioParameters.valid(
                scenario, version.getModelKind(), runType, persistedScenarioInspection)) {
            throw new RunException(HttpStatus.BAD_REQUEST, "参数方案不符合当前模拟器和运行类型的严格合同；气举性能仅支持黑油井，边界与注气扫描值必须为有限数值且不得含未知字段");
        }
        if (!"READY".equals(version.getStatus())) throw new RunException(HttpStatus.CONFLICT, "只有 READY 模型版本可以创建运行");
        SoftwareIntegrationModelEntity model = modelMapper.selectById(version.getModelId());
        if (model == null || model.getDeletedAt() != null) throw new RunException(HttpStatus.NOT_FOUND, "模型不存在");
        String versionSimulatorType = simulatorType(version.getModelKind());
        if (versionSimulatorType == null) {
            throw new RunException(HttpStatus.CONFLICT, "模型版本缺少已验证类型，请重新验证");
        }
        boolean compositional = "ECLIPSE_300".equals(versionSimulatorType);
        boolean eclipse = "ECLIPSE_100".equals(versionSimulatorType) || compositional;
        String lowerOriginalName = version.getOriginalName() == null
                ? "" : version.getOriginalName().toLowerCase(java.util.Locale.ROOT);
        JsonNode persistedEclipseInspection = eclipse
                ? EclipseDataInspectionValidator.parsePersisted(version.getInspectionJson()) : null;
        if (compositional && (persistedEclipseInspection == null || !Set.of("eclipse-data-inspection/14", "eclipse-data-inspection/15").contains(persistedEclipseInspection.path("schemaVersion").asText())))
            throw new RunException(HttpStatus.CONFLICT, "E300模型缺少已验证的组分或热采检查，请重新验证");
        if (compositional && !scenario.isNull()) throw new RunException(HttpStatus.BAD_REQUEST, "E300组分工程暂不支持套用E100编辑或重启方案，请直接计算完整工程");
        JsonNode persistedNetworkInspection = !eclipse && "network".equals(version.getModelKind())
                ? PipesimNetworkInspectionValidator.parsePersisted(version.getInspectionJson())
                : PipesimWellInspectionValidator.networkInspection(persistedWellInspection);
        if (eclipse && !scenario.isNull()) {
            if (EclipseRelpermParameters.valid(scenario)) {
                if(!EclipseRelpermParameters.matchesInspection(scenario,persistedEclipseInspection))
                    throw new RunException(HttpStatus.BAD_REQUEST,"相渗方案须绑定唯一明确三相SAT区、源SHA及完整原表；油相两列必须联动");
                var baseline=runStore.find(scenario.path("baselineRunId").asLong());
                if(baseline==null||baseline.getModelVersionId()!=versionId||!"eclipse".equals(baseline.getRunType())||!"SUCCEEDED".equals(baseline.getStatus())
                    ||!"VALID_FULL".equals(baseline.getResultContract())||!parse(baseline.getParametersJson()).isNull())
                    throw new RunException(HttpStatus.CONFLICT,"相渗方案必须绑定同版本完整无参数成功基线");
            } else if (EclipseGasPvtParameters.valid(scenario)) {
                if(!EclipseGasPvtParameters.matchesInspection(scenario,persistedEclipseInspection))
                    throw new RunException(HttpStatus.BAD_REQUEST,"气物性方案须绑定单DATA干气PVDG、源SHA、完整原表与cP单位");
                var baseline=runStore.find(scenario.path("baselineRunId").asLong());
                if(baseline==null||baseline.getModelVersionId()!=versionId||!"eclipse".equals(baseline.getRunType())||!"SUCCEEDED".equals(baseline.getStatus())
                    ||!"VALID_FULL".equals(baseline.getResultContract())||!parse(baseline.getParametersJson()).isNull())
                    throw new RunException(HttpStatus.CONFLICT,"气物性方案必须绑定同版本完整无参数成功基线");
            } else if (EclipseOilPvtParameters.valid(scenario)) {
                if(!EclipseOilPvtParameters.matchesInspection(scenario,persistedEclipseInspection))
                    throw new RunException(HttpStatus.BAD_REQUEST,"油物性方案须绑定单DATA死油PVDO、源SHA、完整原表与cP单位");
                var baseline=runStore.find(scenario.path("baselineRunId").asLong());
                if(baseline==null||baseline.getModelVersionId()!=versionId||!"eclipse".equals(baseline.getRunType())||!"SUCCEEDED".equals(baseline.getStatus())
                    ||!"VALID_FULL".equals(baseline.getResultContract())||!parse(baseline.getParametersJson()).isNull())
                    throw new RunException(HttpStatus.CONFLICT,"油物性方案必须绑定同版本完整无参数成功基线");
            } else if (EclipsePvtwParameters.valid(scenario)) {
                if(!EclipsePvtwParameters.matchesInspection(scenario,persistedEclipseInspection))
                    throw new RunException(HttpStatus.BAD_REQUEST,"水物性方案须绑定单DATA纯水工程、源SHA、完整PVTW原记录与对应物性原单位");
                var baseline=runStore.find(scenario.path("baselineRunId").asLong());
                if(baseline==null||baseline.getModelVersionId()!=versionId||!"eclipse".equals(baseline.getRunType())||!"SUCCEEDED".equals(baseline.getStatus())
                    ||!"VALID_FULL".equals(baseline.getResultContract())||!parse(baseline.getParametersJson()).isNull())
                    throw new RunException(HttpStatus.CONFLICT,"水物性方案必须绑定同版本完整无参数成功基线");
            } else if (EclipseNewCompletionParameters.valid(scenario)) {
                if (!EclipseNewCompletionParameters.matchesInspection(scenario,persistedEclipseInspection))
                    throw new RunException(HttpStatus.BAD_REQUEST,"新增完井须引用已有开井WRAT产水井、单DATA初始源SHA和明确连接；重复、占用、越界或容量不足不可计算");
                var baseline=runStore.find(scenario.path("baselineRunId").asLong());
                if(baseline==null||baseline.getModelVersionId()!=versionId||!"eclipse".equals(baseline.getRunType())||!"SUCCEEDED".equals(baseline.getStatus())
                        ||!"VALID_FULL".equals(baseline.getResultContract())||!parse(baseline.getParametersJson()).isNull()
                        ||!EclipseNewCompletionParameters.matchesBaseline(scenario,parse(baseline.getResultJson()),runStore.artifacts(baseline.getId())))
                    throw new RunException(HttpStatus.CONFLICT,"新增完井必须绑定同版本完整无参数成功基线和实际EGRID/ACTNUM");
            } else if (EclipseNewWellParameters.valid(scenario)) {
                if (!EclipseNewWellParameters.matchesInspection(scenario,persistedEclipseInspection))
                    throw new RunException(HttpStatus.BAD_REQUEST,"新井须引用单DATA纯水工程、初始明确插入位置和源SHA；名称、已有直接组、连接及容量必须有效");
                var baseline=runStore.find(scenario.path("baselineRunId").asLong());
                if(baseline==null||baseline.getModelVersionId()!=versionId||!"eclipse".equals(baseline.getRunType())||!"SUCCEEDED".equals(baseline.getStatus())
                        ||!"VALID_FULL".equals(baseline.getResultContract())||!parse(baseline.getParametersJson()).isNull()
                        ||!EclipseNewWellParameters.matchesBaseline(scenario,parse(baseline.getResultJson()),runStore.artifacts(baseline.getId())))
                    throw new RunException(HttpStatus.CONFLICT,"新井方案必须绑定同版本完整无参数成功基线和对应EGRID/ACTNUM");
            } else if (EclipseCompletionGeometryParameters.valid(scenario)) {
                if (!EclipseCompletionGeometryParameters.matchesInspection(scenario, persistedEclipseInspection))
                    throw new RunException(HttpStatus.BAD_REQUEST, "完井数值方案须引用初始单连接、自动CF、正确单位与完整原记录；当前工程或后续覆盖不受支持");
                var baseline = runStore.find(scenario.path("baselineRunId").asLong());
                if (baseline == null || baseline.getModelVersionId() != versionId || !"eclipse".equals(baseline.getRunType())
                        || !"SUCCEEDED".equals(baseline.getStatus()) || !"VALID_FULL".equals(baseline.getResultContract()) || !parse(baseline.getParametersJson()).isNull())
                    throw new RunException(HttpStatus.CONFLICT, "完井数值方案必须绑定同版本完整无参数成功基线");
            } else if (EclipseCompletionFactorParameters.valid(scenario)) {
                if (!EclipseCompletionFactorParameters.matchesInspection(scenario, persistedEclipseInspection)) {
                    throw new RunException(HttpStatus.BAD_REQUEST, "ECLIPSE 完井连接因子方案必须引用检查结果中已有的 COMPDAT 完井段、原连接因子和源文件行号");
                }
                long baselineRunId = scenario.path("baselineRunId").asLong();
                SoftwareIntegrationRunEntity baseline = runStore.find(baselineRunId);
                if (baseline == null || baseline.getModelVersionId() != versionId
                        || !"eclipse".equals(baseline.getRunType()) || !"SUCCEEDED".equals(baseline.getStatus())
                        || !"VALID_FULL".equals(baseline.getResultContract()) || !parse(baseline.getParametersJson()).isNull()) {
                    throw new RunException(HttpStatus.CONFLICT, "完井连接因子方案必须绑定同一模型版本的无参数成功 ECLIPSE 基线运行");
                }
            } else if (EclipseCompletionParameters.valid(scenario)) {
                if (!EclipseCompletionParameters.matchesInspection(scenario, persistedEclipseInspection)) {
                    throw new RunException(HttpStatus.BAD_REQUEST, "ECLIPSE 完井方案必须引用检查结果中已有的 COMPDAT 完井段和源文件行号");
                }
                long baselineRunId = scenario.path("baselineRunId").asLong();
                SoftwareIntegrationRunEntity baseline = runStore.find(baselineRunId);
                if (baseline == null || baseline.getModelVersionId() != versionId
                        || !"eclipse".equals(baseline.getRunType()) || !"SUCCEEDED".equals(baseline.getStatus())
                        || !"VALID_FULL".equals(baseline.getResultContract()) || !parse(baseline.getParametersJson()).isNull()) {
                    throw new RunException(HttpStatus.CONFLICT, "完井方案必须绑定同一模型版本的无参数成功 ECLIPSE 基线运行");
                }
            } else if (EclipseControlPlanParameters.valid(scenario) || EclipseGroupGasInjectionParameters.valid(scenario) || EclipseGroupInjectionParameters.valid(scenario) || EclipseGroupStructureParameters.valid(scenario) || EclipseAdvancedInjectionParameters.valid(scenario) || EclipseGroupWaterProductionParameters.valid(scenario) || EclipseGroupRateParameters.valid(scenario) || EclipseGroupProductionParameters.valid(scenario) || EclipseScheduleParameters.valid(scenario) || EclipseProductionTargetParameters.valid(scenario) || EclipsePressureControlParameters.valid(scenario) || EclipseProductionControlParameters.valid(scenario) || EclipseThpControlParameters.valid(scenario) || EclipseNewDatesParameters.valid(scenario) || EclipseTstepParameters.valid(scenario)) {
                boolean matches = EclipseControlPlanParameters.valid(scenario) ? EclipseControlPlanParameters.matchesInspection(scenario, persistedEclipseInspection) : EclipseGroupGasInjectionParameters.valid(scenario)
                        ? EclipseGroupGasInjectionParameters.matchesInspection(scenario, persistedEclipseInspection) : EclipseGroupInjectionParameters.valid(scenario)
                        ? EclipseGroupInjectionParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseGroupStructureParameters.valid(scenario)
                        ? EclipseGroupStructureParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseAdvancedInjectionParameters.valid(scenario)
                        ? EclipseAdvancedInjectionParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseGroupWaterProductionParameters.valid(scenario)
                        ? EclipseGroupWaterProductionParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseGroupRateParameters.valid(scenario)
                        ? EclipseGroupRateParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseGroupProductionParameters.valid(scenario)
                        ? EclipseGroupProductionParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseTstepParameters.valid(scenario)
                        ? EclipseTstepParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseNewDatesParameters.valid(scenario)
                        ? EclipseNewDatesParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseThpControlParameters.valid(scenario)
                        ? EclipseThpControlParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseProductionControlParameters.valid(scenario)
                        ? EclipseProductionControlParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipsePressureControlParameters.valid(scenario)
                        ? EclipsePressureControlParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseProductionTargetParameters.valid(scenario)
                        ? EclipseProductionTargetParameters.matchesInspection(scenario, persistedEclipseInspection)
                        : EclipseScheduleParameters.matchesInspection(scenario, persistedEclipseInspection);
                if (!matches) {
                    throw new RunException(HttpStatus.BAD_REQUEST, "ECLIPSE 方案必须匹配检查结果中的井、作用阶段、源记录和原参数");
                }
                long baselineRunId = scenario.path("baselineRunId").asLong();
                SoftwareIntegrationRunEntity baseline = runStore.find(baselineRunId);
                if (baseline == null || baseline.getModelVersionId() != versionId
                        || !"eclipse".equals(baseline.getRunType()) || !"SUCCEEDED".equals(baseline.getStatus())
                        || !"VALID_FULL".equals(baseline.getResultContract()) || !parse(baseline.getParametersJson()).isNull()) {
                    throw new RunException(HttpStatus.CONFLICT, "调度方案必须绑定同一模型版本的无参数成功 ECLIPSE 基线运行");
                }
            } else if (EclipseGridRegionParameters.valid(scenario)) {
                long baselineRunId = scenario.path("baselineRunId").asLong();
                SoftwareIntegrationRunEntity baseline = runStore.find(baselineRunId);
                if (baseline == null || baseline.getModelVersionId() != versionId
                        || !"eclipse".equals(baseline.getRunType()) || !"SUCCEEDED".equals(baseline.getStatus())
                        || !"VALID_FULL".equals(baseline.getResultContract()) || !parse(baseline.getParametersJson()).isNull()
                        || !EclipseGridRegionParameters.matchesBaseline(scenario, parse(baseline.getResultJson()), runStore.artifacts(baselineRunId)))
                    throw new RunException(HttpStatus.CONFLICT, "区域物性方案须绑定同版本完整无参数成功基线、静态INIT和对应EGRID/ACTNUM");
            } else if (EclipseGridFieldParameters.valid(scenario)) {
                long baselineRunId = scenario.path("baselineRunId").asLong();
                SoftwareIntegrationRunEntity baseline = runStore.find(baselineRunId);
                if (baseline == null || baseline.getModelVersionId() != versionId
                        || !"eclipse".equals(baseline.getRunType()) || !"SUCCEEDED".equals(baseline.getStatus())
                        || !"VALID_FULL".equals(baseline.getResultContract()) || !parse(baseline.getParametersJson()).isNull()
                        || !EclipseGridFieldParameters.matchesBaseline(scenario, parse(baseline.getResultJson()), runStore.artifacts(baselineRunId))) {
                    throw new RunException(HttpStatus.CONFLICT, "ECLIPSE 场值方案必须绑定同一版本、含完整 PERMX INIT 场值和对应 Artifact 的无参数成功基线运行");
                }
            } else if (EclipseRestartForecastParameters.valid(scenario)) {
                SoftwareIntegrationRunEntity history = runStore.find(scenario.path("historyRunId").asLong());
                if (history == null || history.getModelVersionId() != versionId || !"eclipse".equals(history.getRunType())
                        || !"SUCCEEDED".equals(history.getStatus()) || !"VALID_FULL".equals(history.getResultContract())
                        || !parse(history.getParametersJson()).isNull())
                    throw new RunException(HttpStatus.CONFLICT, "接续预测必须绑定同一模型版本的无参数完整成功历史运行");
                var historicalArtifacts = runStore.artifacts(history.getId());
                var catalogArtifact = historicalArtifacts.stream().filter(a -> "eclipse-restart-catalog.json".equals(a.getArtifactName())).findFirst().orElse(null);
                var stateArtifact = historicalArtifacts.stream().filter(a -> scenario.path("restartArtifactName").asText().equals(a.getArtifactName())).findFirst().orElse(null);
                if (catalogArtifact == null || stateArtifact == null || stateArtifact.getSizeBytes() == null)
                    throw new RunException(HttpStatus.CONFLICT, "历史运行缺少可验证的报告目录或重启状态，请运行支持目录的历史模型");
                try {
                    var stateDownload = downloadArtifact(history.getId(), stateArtifact.getId());
                    var stateDigest = java.security.MessageDigest.getInstance("SHA-256");
                    try (var input = Files.newInputStream(stateDownload.path())) {
                        byte[] buffer = new byte[1024 * 1024]; int count;
                        while ((count = input.read(buffer)) != -1) stateDigest.update(buffer, 0, count);
                    }
                    if (!java.util.HexFormat.of().formatHex(stateDigest.digest()).equals(stateArtifact.getSha256()))
                        throw new RunException(HttpStatus.CONFLICT, "历史重启状态文件已变化，请重新计算历史模型");
                    var downloaded = downloadArtifact(history.getId(), catalogArtifact.getId());
                    if (downloaded.sizeBytes() > 8L * 1024 * 1024) throw new IOException("Catalog exceeds limit");
                    var bytes = Files.readAllBytes(downloaded.path());
                    var hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!hash.equals(catalogArtifact.getSha256()) || !EclipseRestartForecastParameters.matchesCatalog(scenario,
                            objectMapper.readTree(bytes), persistedEclipseInspection, stateArtifact.getArtifactName(), stateArtifact.getSha256(), stateArtifact.getSizeBytes()))
                        throw new RunException(HttpStatus.BAD_REQUEST, "预测输入、报告号、截止日期、天数或物理模型与已验证历史不匹配");
                } catch (IOException | java.security.NoSuchAlgorithmException e) {
                    throw new RunException(HttpStatus.CONFLICT, "历史报告目录不可用，请重新计算历史模型");
                }
            } else if (EclipseHistoryForecastParameters.valid(scenario)) {
                if (!EclipseHistoryForecastParameters.matchesInspection(scenario, persistedEclipseInspection)) {
                    throw new RunException(HttpStatus.BAD_REQUEST, "ECLIPSE 预测 DATA 必须来自当前版本已验证的模型包");
                }
                SoftwareIntegrationRunEntity history = runStore.find(scenario.path("historyRunId").asLong());
                if (history == null || history.getModelVersionId() != versionId || !"eclipse".equals(history.getRunType())
                        || !"SUCCEEDED".equals(history.getStatus()) || !"VALID_FULL".equals(history.getResultContract())
                        || !parse(history.getParametersJson()).isNull()) {
                    throw new RunException(HttpStatus.CONFLICT, "预测运行必须绑定同一版本的无参数成功历史 ECLIPSE 运行");
                }
                boolean restartMatches = runStore.artifacts(history.getId()).stream().anyMatch(artifact ->
                        scenario.path("restartArtifactName").asText().equals(artifact.getArtifactName())
                                && scenario.path("restartArtifactSha256").asText().equalsIgnoreCase(artifact.getSha256()));
                if (!restartMatches) throw new RunException(HttpStatus.CONFLICT, "历史运行未提供匹配的 FUNRST 重启 Artifact");
            } else {
                throw new RunException(HttpStatus.BAD_REQUEST, "ECLIPSE 参数必须是有效的受控调度、COMPDAT 完井/连接因子、单格 PERMX 场值方案或历史重启预测方案");
            }
        }
        if (!eclipse && "network".equals(runType) && !scenario.isNull()
                && NetworkChokeBeanSizeParameters.valid(scenario)) {
            if (persistedNetworkInspection == null) {
                throw new RunException(HttpStatus.CONFLICT, "Network Choke Bean Size 方案缺少有效的模型检查信息，请重新验证");
            }
            if (!NetworkChokeBeanSizeParameters.matchesInspection(scenario, persistedNetworkInspection)) {
                throw new RunException(HttpStatus.BAD_REQUEST, "Network Choke Bean Size 方案必须引用当前版本检查结果中的已有 Choke 和原始 Bean Size");
            }
            requirePipesimBaseline(versionId, request, scenario, "network", "Network Choke Bean Size");
        }
        if (!eclipse && "network".equals(runType) && persistedNetworkInspection != null && !scenario.isNull()
                && NetworkFlowlineDiameterParameters.valid(scenario, persistedNetworkInspection)) {
            requirePipesimBaseline(versionId, request, scenario, "network", "Network Flowline 内径");
        }
        if (!eclipse && "profile".equals(runType) && !scenario.isNull()
                && WellTubingDiameterParameters.valid(scenario, persistedWellInspection)) {
            requirePipesimBaseline(versionId, request, scenario, "profile", "PIPESIM Tubing 内径");
        }
        // A derived connection always needs a full baseline, even without inspection metadata.
        if (!eclipse && "network".equals(runType) && !scenario.isNull()
                && NetworkConnectionParameters.valid(scenario)) {
            SoftwareIntegrationRunEntity baseline = requirePipesimBaseline(
                    versionId, request, scenario, "network", "Network 连接");
            JsonNode baselineResult = parse(baseline.getResultJson());
            if (NetworkConnectionParameters.targetInletUnavailable(scenario, baselineResult)) {
                throw new RunException(HttpStatus.CONFLICT, "目标设备入口已占用或不支持作为入口；请选择有可用入口的设备后重算");
            }
            if (NetworkConnectionParameters.originalFlowlineInletWouldBeDisconnected(scenario, baselineResult)) {
                throw new RunException(HttpStatus.CONFLICT, "原连接终点是唯一入口相连的 Flowline；单边重接会使其 ConnectionsBoth 端口悬空，请选择其他连接或使用完整拓扑编辑");
            }
            if (!NetworkConnectionParameters.matchesBaseline(scenario, baselineResult)) {
                throw new RunException(HttpStatus.CONFLICT, "Network 连接方案必须绑定同一版本、同一 Study 的完整无参数成功基线，且重接目标必须来自该基线拓扑");
            }
        }
        boolean eclipsePackage = eclipse
                && lowerOriginalName.endsWith(".zip")
                && persistedEclipseInspection != null
                && Set.of("eclipse-data-inspection/3", "eclipse-data-inspection/4", "eclipse-data-inspection/5", "eclipse-data-inspection/6", "eclipse-data-inspection/7", "eclipse-data-inspection/8", "eclipse-data-inspection/9", "eclipse-data-inspection/10", "eclipse-data-inspection/11", "eclipse-data-inspection/12", "eclipse-data-inspection/13", "eclipse-data-inspection/14", "eclipse-data-inspection/15").contains(persistedEclipseInspection.path("schemaVersion").asText())
                && persistedEclipseInspection.path("packageFiles").isArray()
                && !persistedEclipseInspection.path("packageFiles").isEmpty();
        if (eclipse
                ? !(lowerOriginalName.endsWith(".data") || eclipsePackage)
                : !lowerOriginalName.endsWith(".pips")) {
            throw new RunException(HttpStatus.CONFLICT, "模型文件扩展名与已验证 modelKind 不匹配");
        }
        String study = null;
        if (eclipse) {
            if (version.getStudiesJson() != null && !version.getStudiesJson().isBlank()) {
                throw new RunException(HttpStatus.CONFLICT, "ECLIPSE 模型版本不得包含 Study");
            }
            if (!"eclipse".equals(runType) || !request.isStudyProvided() || request.getStudy() != null) {
                throw new RunException(HttpStatus.BAD_REQUEST, "ECLIPSE 运行必须使用 runType=eclipse 且 study=null");
            }
            if (persistedEclipseInspection == null) {
                throw new RunException(HttpStatus.CONFLICT, "ECLIPSE 模型版本缺少有效检查信息，请重新验证");
            }
        } else {
            if (!request.isStudyProvided() || request.getStudy() == null || request.getStudy().isBlank()) {
                throw new RunException(HttpStatus.BAD_REQUEST, "PIPESIM 运行必须显式提供非空 Study");
            }
            List<String> matchingStudies = version.getStudiesJson() == null ? List.of()
                    : Arrays.stream(version.getStudiesJson().split("\\n", -1))
                    .filter(candidate -> SoftwareIntegrationDiagnosticSanitizer.sanitize(candidate).equals(request.getStudy()))
                    .toList();
            if (matchingStudies.size() != 1) {
                throw new RunException(HttpStatus.BAD_REQUEST, "Study 不存在、名称不精确匹配或脱敏后不唯一");
            }
            study = matchingStudies.get(0);
        }
        if (Set.of("gas-lift-performance", "gas-lift-diagnostics").contains(runType)) {
            String rejection = com.grdp.studio.softwareintegration.support.GasLiftCapabilityValidator.rejection(
                    persistedWellInspection, scenario.path("producer").asText(), study);
            if (rejection != null) throw new RunException(HttpStatus.CONFLICT, rejection);
        }
        boolean networkRunType = Set.of("network", "system-analysis", "network-optimizer", "network-well-performance-curves").contains(runType);
        String resolvedStudy = study;
        boolean networkStudyValidated = networkRunType && persistedNetworkInspection != null
                && persistedNetworkInspection.path("studies").isArray()
                && java.util.stream.StreamSupport.stream(persistedNetworkInspection.path("studies").spliterator(), false)
                .anyMatch(item -> resolvedStudy.equals(item.path("study").asText()));
        boolean compatible = eclipse && "eclipse".equals(runType)
                || "PIPESIM_NETWORK".equals(versionSimulatorType) && Set.of("network", "system-analysis", "network-optimizer", "network-well-performance-curves").contains(runType)
                || "PIPESIM_WELL".equals(versionSimulatorType) && (WELL_RUN_TYPES.contains(runType)
                || networkRunType && networkStudyValidated);
        if (!compatible) throw new RunException(HttpStatus.BAD_REQUEST, "runType 与模型 simulatorType 不兼容");
        SoftwareIntegrationProjectEntity project = projectMapper.selectById(model.getProjectId());
        if (project == null || project.getDeletedAt() != null) throw new RunException(HttpStatus.NOT_FOUND, "软件集成项目不存在");
        normalizeStoredKey(version);
        if (eclipse && !(compositional ? capabilityService.eclipse300Available() : capabilityService.eclipseAvailable())) {
            throw new RunException(HttpStatus.SERVICE_UNAVAILABLE, compositional ? "ECLIPSE 300 2024.1 Worker 能力不可用" : "ECLIPSE 100 2024.1 Worker 能力不可用");
        }
        SoftwareIntegrationRunEntity run;
        try {
            run = runStore.createQueued(project.getId(), model.getId(), version.getId(),
                    study, runType, eclipse ? ECLIPSE_TIMEOUT_SECONDS : properties.getDefaultRunTimeoutSeconds(), scenario.toString());
        } catch (SoftwareIntegrationRunStore.ProjectInactiveException exception) {
            throw new RunException(HttpStatus.NOT_FOUND, "软件集成项目不存在");
        }
        return summary(run, model, version);
    }

    @Override
    public SoftwareIntegrationRunDetailResponse get(long runId) {
        SoftwareIntegrationRunEntity run = requireRun(runId);
        SoftwareIntegrationModelVersionEntity version = requireVersion(run.getModelVersionId());
        SoftwareIntegrationModelEntity model = modelMapper.selectById(run.getModelId());
        boolean resultExpired = run.getResultExpiresAt() != null
                && !LocalDateTime.now().isBefore(run.getResultExpiresAt());
        JsonNode result = resultExpired ? null : sanitize(parse(run.getResultJson()));
        return new SoftwareIntegrationRunDetailResponse(
                run.getId(), run.getProjectId(), run.getModelId(), run.getModelVersionId(), model.getName(), version.getVersionNo(),
                run.getStatus(), SoftwareIntegrationDiagnosticSanitizer.sanitize(run.getStudyName()),
                run.getRunType(), parse(run.getParametersJson()), run.getCreatedAt(), run.getQueuedAt(),
                run.getClaimedAt(), run.getStartedAt(), run.getDeadlineAt(), run.getFinishedAt(), run.getTimeoutSeconds(),
                elapsed(run), cancellable(run), sanitize(parse(run.getErrorJson())), sanitize(parse(run.getCleanupJson())), run.getResultContract(),
                result,
                runStore.events(runId).stream().map(event -> new SoftwareIntegrationRunEventResponse(
                        event.getId(), event.getEventSequence(), event.getWorkerSequence(), event.getEventType(), event.getStatus(),
                        SoftwareIntegrationDiagnosticSanitizer.sanitize(event.getMessage()),
                        sanitize(parse(event.getErrorJson())), event.getOccurredAt())).toList(),
                runStore.artifacts(runId).stream().map(artifact -> new SoftwareIntegrationArtifactResponse(
                        artifact.getId(), artifact.getArtifactName(), artifact.getArtifactType(), artifact.getContentType(),
                        artifact.getSizeBytes(), artifact.getSha256(), artifact.getCreatedAt(), artifact.getExpiresAt())).toList(),
                run.getResultExpiresAt(), resultExpired);
    }

    @Override
    public ArtifactDownload downloadArtifact(long runId, long artifactId) {
        requireRun(runId);
        SoftwareIntegrationArtifactEntity artifact = runStore.artifact(runId, artifactId);
        if (artifact == null) throw new RunException(HttpStatus.NOT_FOUND, "Artifact 不存在");
        if (artifact.getExpiresAt() != null && !LocalDateTime.now().isBefore(artifact.getExpiresAt())) {
            throw new RunException(HttpStatus.GONE, "Artifact 已过期");
        }
        final String storageKey;
        try {
            storageKey = normalizer.normalizeRelative(artifact.getStorageKey());
        } catch (IllegalArgumentException exception) {
            throw new RunException(HttpStatus.GONE, "Artifact 存储键无效");
        }
        String prefix = "artifacts/" + runId + "/";
        if (!storageKey.startsWith(prefix)) throw new RunException(HttpStatus.GONE, "Artifact 不属于当前运行");
        Path path;
        try {
            path = normalizer.resolve(storageKey);
            Path realRoot = normalizer.root().toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot) || !Files.isRegularFile(realPath)) {
                throw new IOException("Artifact source is unavailable");
            }
            long actualSize = Files.size(realPath);
            if (artifact.getSizeBytes() == null || actualSize != artifact.getSizeBytes()) {
                throw new IOException("Artifact size changed");
            }
            return new ArtifactDownload(realPath, safeArtifactName(artifact.getArtifactName()),
                    safeContentType(artifact.getContentType()), actualSize);
        } catch (IOException | SecurityException exception) {
            throw new RunException(HttpStatus.GONE, "Artifact 文件不可用");
        }
    }

    @Override
    public ArtifactRangeDownload downloadArtifactRange(long runId, long artifactId, long offset, long length) {
        if (offset < 0 || length <= 0 || length > 4L * 1024 * 1024) {
            throw new RunException(HttpStatus.BAD_REQUEST, "Artifact Range 参数无效");
        }
        ArtifactDownload artifact = downloadArtifact(runId, artifactId);
        if (!artifact.name().startsWith("eclipse-output-")
                || !"application/octet-stream".equals(artifact.contentType())) {
            throw new RunException(HttpStatus.BAD_REQUEST, "只有 ECLIPSE 二进制结果支持分段读取");
        }
        if (offset > artifact.sizeBytes() || length > artifact.sizeBytes() - offset) {
            throw new RunException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Artifact Range 超出文件范围");
        }
        return new ArtifactRangeDownload(artifact.path(), artifact.name(), artifact.contentType(),
                offset, length, artifact.sizeBytes());
    }

    @Override
    public List<SoftwareIntegrationRunSummaryResponse> list(long versionId, int limit) {
        SoftwareIntegrationModelVersionEntity version = requireVersion(versionId);
        SoftwareIntegrationModelEntity model = modelMapper.selectById(version.getModelId());
        if (model == null) throw new RunException(HttpStatus.NOT_FOUND, "模型不存在");
        return runStore.listByVersion(versionId, limit).stream().map(run -> summary(run, model, version)).toList();
    }

    @Override
    public CancelResult cancel(long runId) {
        SoftwareIntegrationRunStore.CancelDecision decision = runStore.requestCancel(runId);
        if (decision.outcome() == SoftwareIntegrationRunStore.CancelOutcome.NOT_FOUND) {
            throw new RunException(HttpStatus.NOT_FOUND, "运行不存在");
        }
        if (decision.outcome() == SoftwareIntegrationRunStore.CancelOutcome.CONFLICT) {
            throw new RunException(HttpStatus.CONFLICT, "终态运行不能取消");
        }
        SoftwareIntegrationRunEntity responseRun = decision.run();
        boolean uncertainAcceptance = responseRun.getAcceptanceUncertainAt() != null;
        if (decision.outcome() == SoftwareIntegrationRunStore.CancelOutcome.REQUESTED
                && (responseRun.getWorkerId() != null || uncertainAcceptance)) {
            try {
                workerClient.cancel(runId);
            } catch (WorkerClientException exception) {
                if (!uncertainAcceptance) {
                    JsonNode error = runStore.error("WORKER_LOST", "运行已被 Worker 接受，但取消时无法确认 Worker 状态");
                    SoftwareIntegrationRunEntity lost = runStore.transition(runId, SoftwareIntegrationRunStatus.WORKER_LOST, patch -> {
                        patch.setErrorCode("WORKER_LOST");
                        patch.setErrorJson(error.toString());
                    }, "取消调用失败，运行标记为 WORKER_LOST", error);
                    if (lost != null) responseRun = lost;
                }
            }
        }
        SoftwareIntegrationModelVersionEntity version = requireVersion(responseRun.getModelVersionId());
        SoftwareIntegrationModelEntity model = modelMapper.selectById(responseRun.getModelId());
        HttpStatus status = decision.outcome() == SoftwareIntegrationRunStore.CancelOutcome.REQUESTED
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return new CancelResult(status, summary(responseRun, model, version));
    }

    @Override
    public SoftwareIntegrationRunSummaryResponse retry(long runId) {
        SoftwareIntegrationRunEntity original = requireRun(runId);
        SoftwareIntegrationRunStatus status = SoftwareIntegrationRunStatus.valueOf(original.getStatus());
        if (status != SoftwareIntegrationRunStatus.TIMED_OUT && status != SoftwareIntegrationRunStatus.WORKER_LOST
                && status != SoftwareIntegrationRunStatus.FAILED) {
            throw new RunException(HttpStatus.CONFLICT, "只有失败、超时或 Worker 失联的终态运行可以手动重试");
        }
        if (status == SoftwareIntegrationRunStatus.FAILED
                && !parse(original.getErrorJson()).path("retryable").asBoolean(false)) {
            throw new RunException(HttpStatus.CONFLICT, "该运行失败原因不可安全重试，请检查模型版本和运行参数");
        }

        SoftwareIntegrationCreateRunRequest request = new SoftwareIntegrationCreateRunRequest();
        request.setStudy(original.getStudyName());
        request.setRunType(original.getRunType());
        request.setParameters(parse(original.getParametersJson()));
        // Re-enter the normal creation path so READY/version/capability/Study checks remain identical to a new run.
        return create(original.getModelVersionId(), request);
    }

    private SoftwareIntegrationRunSummaryResponse summary(SoftwareIntegrationRunEntity run,
                                                          SoftwareIntegrationModelEntity model,
                                                          SoftwareIntegrationModelVersionEntity version) {
        return new SoftwareIntegrationRunSummaryResponse(run.getId(), run.getProjectId(), run.getModelId(), run.getModelVersionId(),
                model.getName(), version.getVersionNo(), SoftwareIntegrationDiagnosticSanitizer.sanitize(run.getStudyName()),
                run.getRunType(), run.getResultContract(), parse(run.getParametersJson()), run.getStatus(),
                run.getCreatedAt(), run.getQueuedAt(), run.getStartedAt(), run.getFinishedAt(), elapsed(run), cancellable(run));
    }

    private boolean cancellable(SoftwareIntegrationRunEntity run) {
        SoftwareIntegrationRunStatus status = SoftwareIntegrationRunStatus.valueOf(run.getStatus());
        return status == SoftwareIntegrationRunStatus.CREATED || status == SoftwareIntegrationRunStatus.QUEUED
                || (status.isActive() && status != SoftwareIntegrationRunStatus.CANCEL_REQUESTED);
    }

    private Long elapsed(SoftwareIntegrationRunEntity run) {
        if (run.getElapsedMillis() != null) return run.getElapsedMillis();
        LocalDateTime start = run.getStartedAt() != null ? run.getStartedAt()
                : (run.getClaimedAt() != null ? run.getClaimedAt() : run.getCreatedAt());
        return Math.max(0, Duration.between(start, LocalDateTime.now()).toMillis());
    }

    private SoftwareIntegrationRunEntity requireRun(long id) {
        SoftwareIntegrationRunEntity run = runStore.find(id);
        if (run == null) throw new RunException(HttpStatus.NOT_FOUND, "运行不存在");
        return run;
    }

    private SoftwareIntegrationModelVersionEntity requireVersion(long id) {
        SoftwareIntegrationModelVersionEntity version = versionMapper.selectById(id);
        if (version == null) throw new RunException(HttpStatus.NOT_FOUND, "模型版本不存在");
        return version;
    }

    private void normalizeStoredKey(SoftwareIntegrationModelVersionEntity version) {
        final String normalized;
        try { normalized = normalizer.normalizeStoredKey(version.getStorageKey()); }
        catch (IllegalArgumentException exception) {
            throw new RunException(HttpStatus.CONFLICT, "模型存储键不在受控存储根目录内");
        }
        if (!normalized.equals(version.getStorageKey())) {
            int updated = versionMapper.update(null, new LambdaUpdateWrapper<SoftwareIntegrationModelVersionEntity>()
                    .eq(SoftwareIntegrationModelVersionEntity::getId, version.getId())
                    .eq(SoftwareIntegrationModelVersionEntity::getStorageKey, version.getStorageKey())
                    .set(SoftwareIntegrationModelVersionEntity::getStorageKey, normalized));
            if (updated != 1) throw new RunException(HttpStatus.CONFLICT, "模型存储键并发更新，请重试");
            version.setStorageKey(normalized);
        }
    }

    private SoftwareIntegrationRunEntity requirePipesimBaseline(long versionId, SoftwareIntegrationCreateRunRequest request,
                                                               JsonNode scenario, String task, String description) {
        SoftwareIntegrationRunEntity baseline = runStore.find(scenario.path("baselineRunId").asLong());
        if (baseline == null || baseline.getModelVersionId() != versionId || !task.equals(baseline.getRunType())
                || !"SUCCEEDED".equals(baseline.getStatus()) || !"VALID_FULL".equals(baseline.getResultContract())
                || !request.isStudyProvided() || request.getStudy() == null
                || !request.getStudy().equals(baseline.getStudyName()) || !parse(baseline.getParametersJson()).isNull()) {
            throw new RunException(HttpStatus.CONFLICT, description
                    + "方案必须绑定同一模型版本、同一 Study 的无参数成功 " + task + " 基线运行");
        }
        return baseline;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return nullNode();
        try { return objectMapper.readTree(json); }
        catch (RuntimeException exception) { return nullNode(); }
    }

    private JsonNode nullNode() { return objectMapper.readTree("null"); }

    private static String safeArtifactName(String value) {
        if (value == null || value.isBlank()) return "artifact.bin";
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}") ? name : "artifact.bin";
    }

    private static String safeContentType(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return "application/octet-stream";
        }
        return value;
    }

    private JsonNode sanitize(JsonNode value) {
        if (value == null || value.isNull() || value.isNumber() || value.isBoolean()) return value;
        if (value.isTextual()) {
            return objectMapper.getNodeFactory().textNode(
                    SoftwareIntegrationDiagnosticSanitizer.sanitize(value.asText()));
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(sanitize(item)));
            return result;
        }
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            value.properties().forEach(field -> {
                String sanitizedKey = SoftwareIntegrationDiagnosticSanitizer.sanitize(field.getKey());
                String uniqueKey = sanitizedKey;
                for (int suffix = 2; result.has(uniqueKey); suffix++) uniqueKey = sanitizedKey + "#" + suffix;
                result.set(uniqueKey, sanitize(field.getValue()));
            });
            return result;
        }
        return value;
    }

    private static String simulatorType(String modelKind) {
        if ("network".equals(modelKind)) return "PIPESIM_NETWORK";
        if ("eclipse_100".equals(modelKind)) return "ECLIPSE_100";
        if ("eclipse_300".equals(modelKind)) return "ECLIPSE_300";
        if ("black_oil_liquid".equals(modelKind) || "basic_gas".equals(modelKind)
                || "legacy_well".equals(modelKind)) return "PIPESIM_WELL";
        return null;
    }
}
