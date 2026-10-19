using System.Text.Json;
using System.Text.Json.Serialization;

namespace Grdp.SoftwareIntegration.Worker.Contracts;

public sealed record ModelValidationRequest(string? ModelStorageKey, string? ExpectedSha256);

public sealed record ModelValidationResponse(
    string Status,
    IReadOnlyList<string> Studies,
    string Message,
    string? ModelKind = null,
    string? Well = null,
    WorkerError? Error = null,
    object? Inspection = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] IReadOnlyList<string>? EspContexts = null);

public sealed record WellDataInspection(
    string SchemaVersion,
    WellPressureInspection? ReservoirPressure,
    IReadOnlyList<EclipsePackageFile>? PackageFiles = null,
    string? Well = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] IReadOnlyList<string>? EspContexts = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] IReadOnlyList<WellEspContextInspection>? WellContexts = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] NetworkCapabilityInspection? NetworkCapability = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] IReadOnlyList<GasLiftCapabilityInspection>? GasLiftCapabilities = null);
public sealed record GasLiftCapabilityInspection(string Well, string Study, string State, string Reason,
    bool? UseGasliftValve, bool? TargetInjectionRateAvailable);
public sealed record NetworkCapabilityInspection(
    string SchemaVersion,
    IReadOnlyList<string> Studies,
    NetworkDataInspection NetworkInspection);
public sealed record WellEspContextInspection(
    string Context,
    IReadOnlyList<string> EspContexts,
    IReadOnlyList<string> CompletionContexts,
    IReadOnlyList<string> TubingContexts,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] IReadOnlyList<WellTubingComponentInspection>? TubingComponents = null);
public sealed record WellTubingComponentInspection(string Context, double InnerDiameter, string Unit);
public sealed record WellPressureInspection(double Value, string Unit);

public sealed record NetworkDataInspection(
    string SchemaVersion,
    IReadOnlyList<NetworkStudyInspection> Studies,
    IReadOnlyList<NetworkChokeInspection>? Chokes = null,
    IReadOnlyList<EclipsePackageFile>? PackageFiles = null,
    IReadOnlyList<string>? WellContexts = null,
    IReadOnlyList<string>? FlowlineContexts = null,
    IReadOnlyList<NetworkFlowlineInspection>? Flowlines = null);
public sealed record NetworkStudyInspection(string Study, IReadOnlyList<NetworkBoundaryInspection> Boundaries);
public sealed record NetworkChokeInspection(string Name, double BeanSize, string Unit);
public sealed record NetworkFlowlineInspection(string Name, double? InnerDiameter, string Unit);
public sealed record NetworkBoundaryInspection(
    string Node,
    string BoundaryNodeType,
    bool IsActive,
    bool IsSurfaceCondition,
    string? FlowRateType,
    double? Pressure,
    double? Temperature,
    double? GasFlowRate,
    double? LiquidFlowRate,
    double? MassFlowRate);

public sealed record EclipseDataInspection(
    string SchemaVersion,
    string CaseName,
    IReadOnlyList<string> Sections,
    string? UnitSystem,
    IReadOnlyList<string> Phases,
    EclipseDimensions? Dimensions,
    IReadOnlyList<string>? WellNames = null,
    IReadOnlyList<EclipseScheduleEvent>? ScheduleTimeline = null,
    IReadOnlyList<EclipsePackageFile>? PackageFiles = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    EclipseScheduleMetadata? ScheduleMetadata = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<EclipseVfpTable>? VfpTables = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<EclipseGroupProductionControl>? GroupControls = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<EclipseGroupInjectionControl>? InjectionGroupControls = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<EclipseGroupInjectionControl>? GasInjectionGroupControls = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<EclipseGroupProductionControl>? GroupRateControls = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<EclipseGroupWaterProductionControl>? WaterProductionGroupControls = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<EclipseAdvancedInjectionControl>? AdvancedInjectionGroupControls = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<EclipseGroupStructureControl>? GroupStructureControls = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    EclipseProductionControlPlan? ProductionControlPlan = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    JsonElement? Simulation = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    EclipseNewWellContext? NewWellContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    EclipsePackageProductionTargetContext? PackageProductionTargetContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    EclipsePackageProductionControlPlanContext? PackageProductionControlPlanContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    EclipseWaterInjectionControlPlanContext? WaterInjectionControlPlanContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    EclipseCompositionalProductionContext? CompositionalProductionContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    EclipseThermalInjectionContext? ThermalInjectionContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    EclipsePvtwContext? PvtwContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    EclipseOilPvtContext? OilPvtContext = null,
    [property: System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)] EclipseGasPvtContext? GasPvtContext = null,
    [property: System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)] EclipseRelpermContext? RelpermContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseLiveOilPvtContext? LiveOilPvtContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseWetGasPvtContext? WetGasPvtContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseRelpermContext? CoupledRelpermContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseMultiSatRelpermContext? MultiSatRelpermContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseMultiPvtContext? MultiPvtContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseMultiLiveOilPvtContext? MultiLiveOilPvtContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseMultiWetGasPvtContext? MultiWetGasPvtContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseMultiWaterPvtContext? MultiWaterPvtContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseCompositionalReinjectionContext? CompositionalReinjectionContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseCompositionalFluidPropertyContext? CompositionalFluidPropertyContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseCompositionalRestartContext? CompositionalRestartContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseCompositionalFluidPropertyContext? CompositionalTemperatureContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseCompositionalFluidPropertyContext? CompositionalCriticalZContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseCompositionalFluidPropertyContext? CompositionalAcentricFactorContext = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] IReadOnlyList<EclipseCompositionalFluidPropertyContext>? CompositionalEosPropertyContexts = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] EclipseThermalRockContext? ThermalRockContext = null);

public sealed record EclipseWetGasPvtContext(string SchemaVersion,string SourceFile,int KeywordLineNumber,IReadOnlyList<EclipseWetGasPvtRecord> Records);
public sealed record EclipseWetGasPvtRecord(IReadOnlyList<EclipseGasPvtRow> Rows);

public sealed record EclipseLiveOilPvtContext(string SchemaVersion,string SourceFile,int KeywordLineNumber,IReadOnlyList<EclipseLiveOilPvtRecord> Records);
public sealed record EclipseLiveOilPvtRecord(IReadOnlyList<EclipseOilPvtRow> Rows);

public sealed record EclipseOilPvtContext(string SchemaVersion,string SourceFile,int KeywordLineNumber,IReadOnlyList<EclipseOilPvtRow> Rows);
public sealed record EclipseOilPvtRow(int LineNumber,IReadOnlyList<string> Values);

public sealed record EclipsePvtwContext(string SchemaVersion, string SourceFile, int LineNumber, IReadOnlyList<string> RecordValues);

public sealed record EclipseNewWellContext(string SchemaVersion, string SourceFile, int InsertionLine,
    int MaxWells, int MaxConnections, int MaxGroups, int MaxWellsPerGroup);

public sealed record EclipseGroupInjectionControl(string Group, string SourceFile, int LineNumber,
    string ControlMode, string InjectionPhase, string Unit, double OriginalTargetRate, IReadOnlyList<EclipseGroupInjectionMember> Members);
public sealed record EclipseGroupInjectionMember(string Well, double BhpLimit);

public sealed record EclipseGroupProductionControl(string Group, string SourceFile, int LineNumber,
    string ControlMode, string Unit, double OriginalTargetRate, IReadOnlyList<EclipseGroupProductionMember> Members);
public sealed record EclipseGroupProductionMember(string Well, double BhpLimit);
public sealed record EclipseGroupWaterProductionControl(string Group, string SourceFile, int LineNumber,
    string ControlMode, string Unit, double OriginalTargetRate, IReadOnlyList<EclipseGroupWaterProductionMember> Members);
public sealed record EclipseGroupWaterProductionMember(string Well, double BhpLimit, double OilRateLimit);
public sealed record EclipseAdvancedInjectionControl(string Group, string SourceFile, int LineNumber,
    string ControlMode, string InjectionPhase, string Unit, double OriginalTargetRate, IReadOnlyList<EclipseAdvancedInjectionMember> Members,
    string? ReferenceGroup, IReadOnlyList<string> ReferenceMembers);
public sealed record EclipseAdvancedInjectionMember(string Well, double BhpLimit, double SurfaceRateLimit);
public sealed record EclipseGroupStructureControl(string ParentGroup, string ObjectName, string Keyword,
    string SourceFile, int LineNumber, string Unit, double OriginalValue, double ParentTarget,
    IReadOnlyList<EclipseGroupStructureLeaf> Leaves);
public sealed record EclipseGroupStructureLeaf(string Group, double Efficiency, IReadOnlyList<EclipseGroupStructureMember> Members);
public sealed record EclipseGroupStructureMember(string Well, double BhpLimit, double OilRateLimit, double GuideRate);
public sealed record EclipseProductionControlPlan(string StartDate, int EndTimeDays, IReadOnlyList<EclipseProductionPlanRecord> Records);
public sealed record EclipsePackageProductionTargetContext(string SchemaVersion, IReadOnlyList<EclipsePackageProductionTarget> Targets);
public sealed record EclipsePackageProductionTarget(string Well, string SourceFile, string SourceSha256, int LineNumber, double OriginalOilRate);
public sealed record EclipseProductionPlanRecord(string Well, string SourceFile, int LineNumber, string Date,
    int TimeDays, string Unit, double OriginalOilRate, double BhpLimit);

public sealed record EclipseVfpTable(int Number, double DatumDepth, string FlowType, string WaterFractionType,
    string GasFractionType, string AlqType, string UnitSystem, string SourceFile, int LineNumber, string Sha256,
    IReadOnlyList<double> FlowAxis, IReadOnlyList<double> ThpAxis, IReadOnlyList<double> WaterFractionAxis,
    IReadOnlyList<double> GasFractionAxis, IReadOnlyList<double> AlqAxis);

public sealed record EclipsePackageFile(string RelativePath, long SizeBytes, string Sha256);

public sealed record EclipseDimensions(int Nx, int Ny, int Nz);

public sealed record EclipseScheduleEvent(
    string Kind,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<EclipseScheduleDate>? Records = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    IReadOnlyList<string>? Steps = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    string? SourceFile = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    int? LineNumber = null);

public sealed record EclipseScheduleDate(string Day, string Month, string Year, string? Time = null);

public sealed record EclipseScheduleMetadata(
    IReadOnlyList<EclipseScheduleWell> Wells,
    IReadOnlyList<EclipseScheduleGroup> Groups,
    IReadOnlyList<EclipseScheduleKeywordRecord> Records,
    IReadOnlyList<EclipseScheduleCompletion> Completions);

public sealed record EclipseScheduleWell(string Name, string? Group = null, string? SourceFile = null, int? LineNumber = null);

public sealed record EclipseScheduleGroup(string Name, string? Parent = null, string? SourceFile = null, int? LineNumber = null);

public sealed record EclipseScheduleKeywordRecord(string Keyword, IReadOnlyList<string> Values, string? SourceFile = null, int? LineNumber = null);

public sealed record EclipseScheduleCompletion(
    string Keyword,
    string Well,
    string I,
    string J,
    string K1,
    string K2,
    string Status,
    string SourceFile,
    int LineNumber);

public sealed record RunExecuteRequest(
    long RunId,
    string? ModelStorageKey,
    string? ExpectedModelSha256,
    string? Study,
    string? RunTask,
    JsonElement Parameters,
    int TimeoutSeconds,
    IReadOnlyList<EclipsePackageFile>? ExpectedPackageFiles = null);

public sealed record RunAcceptedResponse(
    long RunId,
    string State,
    string WorkerId,
    string GenerationId,
    DateTimeOffset AcceptedAtUtc);

public sealed record RunEvent(long Sequence, string State, DateTimeOffset OccurredAtUtc, string Message);

public sealed record WorkerError(
    string Category,
    string Code,
    string Message,
    bool Retryable,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] IReadOnlyList<WorkerDiagnostic>? Diagnostics = null);

public sealed record WorkerDiagnostic(string Path, string Property, string Message);

public sealed record ArtifactDescriptor(string StorageKey, long Size, string Sha256, string ContentType);

public sealed record RunCleanup(
    bool ProcessTreeExitConfirmed,
    bool InputDeleted,
    bool WorkDirectoryDeleted,
    bool KillUsed,
    string Message);

public sealed record RunSnapshot(
    long RunId,
    string State,
    long LastSequence,
    string WorkerId,
    string GenerationId,
    DateTimeOffset AcceptedAtUtc,
    DateTimeOffset? StartedAtUtc,
    DateTimeOffset? CompletedAtUtc,
    long ElapsedMillis,
    IReadOnlyList<RunEvent> Events,
    JsonElement? Result,
    WorkerError? Error,
    IReadOnlyList<ArtifactDescriptor> Artifacts,
    RunCleanup? Cleanup);

public sealed record CancelResponse(long RunId, string State, DateTimeOffset RequestedAtUtc);

public sealed record ApiOutcome(int HttpStatus, object Body);

public static class WorkerApiError
{
    public static WorkerError Request(string code, string message) => new("REQUEST", code, message, false);
    public static WorkerError Storage(string code, string message, bool retryable = false) => new("STORAGE", code, message, retryable);
    public static WorkerError Coordination(string code, string message, bool retryable = true) => new("COORDINATION", code, message, retryable);
}

public sealed record EclipseGasPvtContext(string SchemaVersion,string SourceFile,int KeywordLineNumber,IReadOnlyList<EclipseGasPvtRow> Rows);
public sealed record EclipseGasPvtRow(int LineNumber,IReadOnlyList<string> Values);

public sealed record EclipseRelpermContext(string SchemaVersion,string SourceFile,IReadOnlyList<EclipseRelpermTable> Tables);
public sealed record EclipseRelpermTable(string Keyword,int KeywordLineNumber,IReadOnlyList<EclipseRelpermRow> Rows);
public sealed record EclipseRelpermRow(int LineNumber,IReadOnlyList<string> Values);

public sealed record EclipseMultiSatRelpermContext(string SchemaVersion,string SourceFile,int RegionCount,IReadOnlyList<EclipseSatRelpermRegion> Regions);
public sealed record EclipseSatRelpermRegion(int Number,int CellCount,IReadOnlyList<EclipseRelpermTable> Tables);

public sealed record EclipseMultiPvtContext(string SchemaVersion,string SourceFile,int RegionCount,IReadOnlyList<EclipsePvtRegion> Regions);
public sealed record EclipsePvtRegion(int Number,int CellCount,IReadOnlyList<EclipsePvtTable> Tables);
public sealed record EclipsePvtTable(string Keyword,int KeywordLineNumber,IReadOnlyList<EclipsePvtRow> Rows);
public sealed record EclipsePvtRow(int LineNumber,IReadOnlyList<string> Values);

public sealed record EclipseMultiLiveOilPvtContext(string SchemaVersion,string SourceFile,int RegionCount,IReadOnlyList<EclipseLiveOilPvtRegion> Regions);
public sealed record EclipseLiveOilPvtRegion(int Number,int CellCount,int KeywordLineNumber,IReadOnlyList<EclipseLiveOilPvtRecord> Records,EclipseRegionGasPvtTable GasTable);
public sealed record EclipseRegionGasPvtTable(int KeywordLineNumber,IReadOnlyList<EclipseGasPvtRow> Rows);

public sealed record EclipseMultiWetGasPvtContext(string SchemaVersion,string SourceFile,int RegionCount,IReadOnlyList<EclipseWetGasPvtRegion> Regions);
public sealed record EclipseWetGasPvtRegion(int Number,int CellCount,int KeywordLineNumber,IReadOnlyList<EclipseWetGasPvtRecord> Records,EclipseRegionLiveOilPvtTable OilTable);
public sealed record EclipseRegionLiveOilPvtTable(int KeywordLineNumber,IReadOnlyList<EclipseLiveOilPvtRecord> Records);

public sealed record EclipseMultiWaterPvtContext(string SchemaVersion,string SourceFile,int RegionCount,IReadOnlyList<EclipseWaterPvtRegion> Regions);
public sealed record EclipseWaterPvtRegion(int Number,int CellCount,int LineNumber,IReadOnlyList<string> RecordValues);

public sealed record EclipsePackageProductionControlPlanContext(string SchemaVersion, string StartDate, int EndTimeDays, IReadOnlyList<EclipsePackageProductionPlanRecord> Records);
public sealed record EclipsePackageProductionPlanRecord(string Well, string SourceFile, string SourceSha256, int LineNumber, string Date, int TimeDays, string Unit, double OriginalOilRate, double BhpLimit);

public sealed record EclipseCompositionalReinjectionContext(string SchemaVersion, string SourceFile, string SourceSha256, long SourceSizeBytes,
    int Solver, int ComponentCount, IReadOnlyList<string> ComponentNames, string EquationOfState, IReadOnlyList<EclipseCompositionalReinjectionRecord> Records);
public sealed record EclipseCompositionalReinjectionRecord(string Group, string Phase, string ControlMode, int LineNumber, double OriginalFraction,
    string GasSourceKind, string GasSourceGroup, int GasSourceLineNumber, double SalesTarget, int SalesLineNumber, string RateUnit, string PressureUnit,
    string InjectionWell, double InjectionBhpUpperLimit, int InjectionLineNumber, string ProductionWell, double ProductionGasTarget,
    double ProductionBhpLowerLimit, double ShutAfterDays, int ShutLineNumber);

public sealed record EclipseCompositionalFluidPropertyContext(string SchemaVersion, string SourceFile, string SourceSha256, long SourceSizeBytes,
    int Solver, int ComponentCount, IReadOnlyList<string> ComponentNames, string EquationOfState, IReadOnlyList<EclipseCompositionalFluidPropertyRecord> Records);
public sealed record EclipseCompositionalFluidPropertyRecord(int ComponentIndex, string ComponentName, string Field, int LineNumber, string Unit, double OriginalValue);
public sealed record EclipseCompositionalRestartContext(string SchemaVersion, string SourceFile, string SourceSha256, long SourceSizeBytes,
    string HistorySourceFile, double? TargetGasRate, Grdp.SoftwareIntegration.Worker.Execution.EclipseCompositionalSaveCatalog HistoryCatalog);
