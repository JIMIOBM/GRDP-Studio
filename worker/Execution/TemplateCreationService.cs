using System.Security.Cryptography;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public interface ITemplateCreationAdapter
{
    Task<AdapterExecutionResult> ExecuteAsync(string requestPath, TimeSpan timeout, CancellationToken cancellationToken);
}

public sealed class TemplateCreationAdapter(PtkProcessRunner runner) : ITemplateCreationAdapter
{
    public Task<AdapterExecutionResult> ExecuteAsync(string path, TimeSpan timeout, CancellationToken token) =>
        runner.CreateTemplateAsync(path, timeout, token);
}

public sealed record TemplateCreationOutcome(int HttpStatus, object Body);

public sealed class TemplateCreationService(TemplateCreationStore store, PtkExecutionCoordinator coordinator,
    ITemplateCreationAdapter adapter, IOptions<WorkerOptions> configuredOptions)
{
    private readonly WorkerOptions options = configuredOptions.Value;
    private readonly object cancellationGate = new();
    private readonly Dictionary<Guid, CancellationTokenSource> activeCreations = new();

    public TemplateCreationOutcome Cancel(Guid id)
    {
        if (id == Guid.Empty) return new(400, WorkerApiError.Request("INVALID_CREATION_ID", "Creation ID is required."));
        lock (cancellationGate)
        {
            var record = store.Read(id);
            // Cancellation never rewrites an already committed terminal result.
            if (record is not null && record.Status != "PREPARING") return new(200, record);
            if (activeCreations.TryGetValue(id, out var cancellation))
            {
                cancellation.Cancel();
                return new(202, new { requestId = id, status = "CANCEL_REQUESTED" });
            }
            return record is null ? new(404, WorkerApiError.Request("CREATION_NOT_FOUND", "Creation record does not exist."))
                : new(409, WorkerApiError.Request("CREATION_CANCEL_UNAVAILABLE", "This generation is not executing the creation; recover its record."));
        }
    }

    public object Capabilities() => new
    {
        schemaVersion = "pipesim-template-creation-capabilities/1", template = "Simple vertical",
        unitsSystem = "PIPESIM_FIELD", supportsCancellation = true,
        maxRunTimeoutSeconds = options.MaxRunTimeoutSeconds,
        maxCleanupSeconds = (long)options.GracefulStopSeconds + options.ProcessExitConfirmationSeconds,
        maxModelBytes = 67_108_864
    };

    public async Task<TemplateCreationOutcome> CreateAsync(TemplateCreationRequest request, CancellationToken cancellationToken)
    {
        if (!TemplateCreationParameters.IsValid(request)) return new(400, WorkerApiError.Request("INVALID_TEMPLATE_INPUTS", "Explicit FIELD template inputs and a safe creation ID/name are required."));
        var fingerprint = TemplateCreationParameters.Fingerprint(request);
        var previous = store.Read(request.RequestId);
        if (previous is not null) return previous.Fingerprint == fingerprint ? new(200, previous)
            : new(409, WorkerApiError.Request("CREATION_ID_CONFLICT", "Creation ID belongs to different inputs."));
        if (!File.Exists(options.EffectivePythonPath) || !File.Exists(options.EffectivePipesimPtkPath))
            return new(503, new WorkerError("ENVIRONMENT", "PTK_UNAVAILABLE", "Python or the installed Toolkit is unavailable.", true));
        var acquired = coordinator.TryAcquire("template-creation");
        if (!acquired.Acquired) return new(409, acquired.Error!);
        using var lease = acquired.Lease!;
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var token = cancellation.Token;
        var adapterStarted = false;
        var exitConfirmed = false;
        try
        {
            // Recheck after reserving the shared engine; never re-execute a durable claim.
            previous = store.Read(request.RequestId);
            if (previous is not null) return previous.Fingerprint == fingerprint ? new(200, previous)
                : new(409, WorkerApiError.Request("CREATION_ID_CONFLICT", "Creation ID belongs to different inputs."));
            try { store.Claim(request, fingerprint); }
            catch (IOException) { return new(409, WorkerApiError.Request("CREATION_ALREADY_CLAIMED", "Creation storage is already claimed; inspect its record, do not resubmit.")); }
            lock (cancellationGate) activeCreations.Add(request.RequestId, cancellation);
            var directory = store.DirectoryFor(request.RequestId);
            var adapterRequest = Path.Combine(directory, "adapter-request.json");
            await File.WriteAllTextAsync(adapterRequest, JsonSerializer.Serialize(new
                { well = request.Well, inputs = request.Inputs, output = store.ModelPath(request.RequestId) }), token);
            adapterStarted = true;
            var execution = await adapter.ExecuteAsync(adapterRequest, TimeSpan.FromSeconds(options.MaxRunTimeoutSeconds), token);
            exitConfirmed = execution.ProcessTreeExitConfirmed;
            if (!execution.ProcessTreeExitConfirmed)
            {
                lease.BlockRelease();
                return Fail(request, fingerprint, "FAILED", new("CLEANUP", "PROCESS_TREE_EXIT_UNCONFIRMED", "Creation process-tree exit is unconfirmed; engine coordination remains blocked.", false), 503);
            }
            if (execution.Error is not null) return Fail(request, fingerprint,
                execution.StopReason == AdapterStopReason.Cancelled ? "CANCELLED" : execution.StopReason == AdapterStopReason.TimedOut ? "TIMED_OUT" : "FAILED", execution.Error, 422);
            if (execution.Envelope is not { } result || !TemplateCreationResultValidator.IsValid(result, request))
                return Fail(request, fingerprint, "FAILED", new("VALIDATION", "TEMPLATE_CREATION_NOT_VERIFIED", "Creation failed native validation or its readback/result contract.", false), 422, execution.Envelope);
            var path = store.ModelPath(request.RequestId);
            var file = new FileInfo(path);
            if (!file.Exists || file.Length is < 1 or > 67_108_864)
                return Fail(request, fingerprint, "FAILED", new("STORAGE", "CREATED_MODEL_MISSING", "A valid created engineering file is required.", false), 422);
            await using var stream = File.OpenRead(path);
            var sha = Convert.ToHexString(await SHA256.HashDataAsync(stream, token)).ToLowerInvariant();
            var record = new TemplateCreationRecord(request.RequestId, fingerprint, "SUCCEEDED", DateTimeOffset.UtcNow,
                result.Clone(), Model: new("created.pips", file.Length, sha));
            lock (cancellationGate)
            {
                // Cancel and success publication have a single ordering point.
                token.ThrowIfCancellationRequested();
                store.Save(record);
            }
            return new(201, record);
        }
        catch (OperationCanceledException)
        {
            if (adapterStarted && !exitConfirmed) lease.BlockRelease();
            return Fail(request, fingerprint, "CANCELLED", new("CANCELLATION", "CREATION_CANCELLED", "Creation request was cancelled.", false), 422);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            if (adapterStarted && !exitConfirmed) lease.BlockRelease();
            // Do not publish or download a file when persistence itself failed.
            return new(503, WorkerApiError.Storage("CREATION_STORAGE_ERROR", "Creation storage could not be safely persisted.", false));
        }
        catch (Exception)
        {
            if (adapterStarted && !exitConfirmed) lease.BlockRelease();
            return Fail(request, fingerprint, "FAILED", new("EXECUTION", "CREATION_ADAPTER_FAILED", "Creation adapter failed; no model was published.", false), 503);
        }
        finally
        {
            lock (cancellationGate) activeCreations.Remove(request.RequestId);
        }
    }

    private TemplateCreationOutcome Fail(TemplateCreationRequest request, string fingerprint, string status, WorkerError error,
        int httpStatus, JsonElement? diagnostics = null)
    {
        var record = new TemplateCreationRecord(request.RequestId, fingerprint, status, DateTimeOffset.UtcNow, diagnostics?.Clone(), error);
        store.Save(record);
        return new(httpStatus, record);
    }
}
