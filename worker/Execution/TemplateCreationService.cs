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
            var directory = store.DirectoryFor(request.RequestId);
            var adapterRequest = Path.Combine(directory, "adapter-request.json");
            await File.WriteAllTextAsync(adapterRequest, JsonSerializer.Serialize(new
                { well = request.Well, inputs = request.Inputs, output = store.ModelPath(request.RequestId) }), cancellationToken);
            adapterStarted = true;
            var execution = await adapter.ExecuteAsync(adapterRequest, TimeSpan.FromSeconds(options.MaxRunTimeoutSeconds), cancellationToken);
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
            var sha = Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken)).ToLowerInvariant();
            var record = new TemplateCreationRecord(request.RequestId, fingerprint, "SUCCEEDED", DateTimeOffset.UtcNow,
                result.Clone(), Model: new("created.pips", file.Length, sha));
            store.Save(record);
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
    }

    private TemplateCreationOutcome Fail(TemplateCreationRequest request, string fingerprint, string status, WorkerError error,
        int httpStatus, JsonElement? diagnostics = null)
    {
        var record = new TemplateCreationRecord(request.RequestId, fingerprint, status, DateTimeOffset.UtcNow, diagnostics?.Clone(), error);
        store.Save(record);
        return new(httpStatus, record);
    }
}
