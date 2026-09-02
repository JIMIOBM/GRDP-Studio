using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Contracts;

public sealed record ModelValidationRequest(string? ModelStorageKey, string? ExpectedSha256);

public sealed record ModelValidationResponse(
    string Status,
    IReadOnlyList<string> Studies,
    string Message,
    string? ModelKind = null,
    string? Well = null,
    WorkerError? Error = null);

public sealed record RunExecuteRequest(
    long RunId,
    string? ModelStorageKey,
    string? ExpectedModelSha256,
    string? Study,
    string? RunTask,
    JsonElement Parameters,
    int TimeoutSeconds);

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
