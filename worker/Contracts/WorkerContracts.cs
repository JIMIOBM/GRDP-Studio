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
    object? Inspection = null);

public sealed record WellDataInspection(
    string SchemaVersion,
    WellPressureInspection? ReservoirPressure,
    IReadOnlyList<EclipsePackageFile>? PackageFiles = null,
    string? Well = null);
public sealed record WellPressureInspection(double Value, string Unit);

public sealed record NetworkDataInspection(
    string SchemaVersion,
    IReadOnlyList<NetworkStudyInspection> Studies,
    IReadOnlyList<NetworkChokeInspection>? Chokes = null,
    IReadOnlyList<EclipsePackageFile>? PackageFiles = null);
public sealed record NetworkStudyInspection(string Study, IReadOnlyList<NetworkBoundaryInspection> Boundaries);
public sealed record NetworkChokeInspection(string Name, double BeanSize, string Unit);
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
    EclipseScheduleMetadata? ScheduleMetadata = null);

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

public sealed record WorkerError(string Category, string Code, string Message, bool Retryable);

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
