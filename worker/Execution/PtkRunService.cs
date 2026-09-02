using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed partial class PtkRunService : IDisposable
{
    private readonly StorageResolver storage;
    private readonly ArtifactStore artifacts;
    private readonly PtkExecutionCoordinator coordinator;
    private readonly PtkRunRegistry registry;
    private readonly PtkProcessRunner runner;
    private readonly WorkerOptions options;
    private readonly SemaphoreSlim submissionGate = new(1, 1);
    private readonly CancellationTokenSource stopping = new();

    public PtkRunService(
        StorageResolver storage,
        PtkExecutionCoordinator coordinator,
        PtkRunRegistry registry,
        PtkProcessRunner runner,
        IOptions<WorkerOptions> options)
    {
        this.storage = storage;
        artifacts = new ArtifactStore(storage);
        this.coordinator = coordinator;
        this.registry = registry;
        this.runner = runner;
        this.options = options.Value;
    }

    public async Task<ApiOutcome> SubmitAsync(RunExecuteRequest request, CancellationToken cancellationToken)
    {
        var requestError = ValidateRequest(request);
        if (requestError is not null) return new(StatusCodes.Status400BadRequest, requestError);
        var fingerprint = Fingerprint(request);

        await submissionGate.WaitAsync(cancellationToken);
        try
        {
            if (registry.ExistsWithFingerprint(request.RunId, fingerprint, out var existing, out var conflict))
            {
                return conflict
                    ? new(StatusCodes.Status409Conflict, WorkerApiError.Request("RUN_ID_CONFLICT", "runId is already associated with a different request."))
                    : new(StatusCodes.Status202Accepted, existing!);
            }

            string sourceModel;
            try
            {
                sourceModel = storage.ResolveExistingModel(request.ModelStorageKey);
                var actualSha = await storage.ComputeSha256Async(sourceModel, cancellationToken);
                if (!string.Equals(actualSha, request.ExpectedModelSha256, StringComparison.Ordinal))
                {
                    return new(StatusCodes.Status422UnprocessableEntity,
                        WorkerApiError.Storage("MODEL_SHA256_MISMATCH", "The source model SHA-256 does not match expectedModelSha256."));
                }
                storage.EnsureRunDirectoryAvailable(request.RunId);
            }
            catch (StorageException exception)
            {
                return new(exception.HttpStatus, WorkerApiError.Storage(exception.Code, exception.Message));
            }

            if (!File.Exists(options.EffectivePythonPath) || !File.Exists(options.EffectivePipesimPtkPath))
            {
                return new(StatusCodes.Status503ServiceUnavailable,
                    new WorkerError("ENVIRONMENT", "PTK_UNAVAILABLE", "Python or PIPESIM Python Toolkit is unavailable.", true));
            }

            var acquired = coordinator.TryAcquire("run");
            if (!acquired.Acquired)
            {
                return new(StatusCodes.Status409Conflict, acquired.Error!);
            }

            var claim = registry.TryClaim(request, fingerprint);
            if (claim.Status != ClaimStatus.Created)
            {
                acquired.Lease!.Dispose();
                return claim.Status switch
                {
                    ClaimStatus.Idempotent => new(StatusCodes.Status202Accepted, claim.Accepted!),
                    _ => new(StatusCodes.Status409Conflict, claim.Error!)
                };
            }

            _ = Task.Run(() => ExecuteAsync(request, sourceModel, acquired.Lease!), CancellationToken.None);
            return new(StatusCodes.Status202Accepted, claim.Accepted!);
        }
        finally
        {
            submissionGate.Release();
        }
    }

    public void Dispose()
    {
        stopping.Cancel();
        stopping.Dispose();
        submissionGate.Dispose();
    }

    private async Task ExecuteAsync(
        RunExecuteRequest request,
        string sourceModel,
        PtkExecutionCoordinator.CoordinatorLease lease)
    {
        RunDirectories? directories = null;
        var descriptors = new List<ArtifactDescriptor>();
        var log = new List<string>();
        var processTreeConfirmed = true;
        var killUsed = false;
        var blockRelease = false;
        JsonElement? result = null;
        JsonElement? envelope = null;
        WorkerError? terminalError = null;
        string terminalState = "FAILED";
        string terminalMessage = "Run failed.";
        var deadline = lease.AcquiredAtUtc.AddSeconds(request.TimeoutSeconds);
        using var linkedCancellation = CancellationTokenSource.CreateLinkedTokenSource(registry.GetCancellationToken(request.RunId), stopping.Token);

        try
        {
            registry.Transition(request.RunId, "PREPARING", "Preparing an isolated task model copy.");
            log.Add("PREPARING Preparing an isolated task model copy.");
            if (linkedCancellation.IsCancellationRequested)
            {
                terminalState = "CANCELLED";
                terminalError = new WorkerError("CANCELLATION", "RUN_CANCELLED", "Run cancellation was requested.", false);
                terminalMessage = "Run cancelled before Python startup.";
                return;
            }

            var sourceShaBefore = await storage.ComputeSha256Async(sourceModel, CancellationToken.None);
            if (!string.Equals(sourceShaBefore, request.ExpectedModelSha256, StringComparison.Ordinal))
            {
                throw new StorageException("SOURCE_MODEL_CHANGED", "The source model changed before task preparation.", StatusCodes.Status422UnprocessableEntity);
            }

            directories = storage.CreateRunDirectories(request.RunId, sourceModel);
            var copySha = await storage.ComputeSha256Async(directories.ModelCopy, CancellationToken.None);
            if (!string.Equals(copySha, request.ExpectedModelSha256, StringComparison.Ordinal))
            {
                throw new StorageException("MODEL_COPY_SHA256_MISMATCH", "The task model copy failed SHA-256 verification.");
            }

            var requestArtifact = new
            {
                request.RunId,
                request.ModelStorageKey,
                request.ExpectedModelSha256,
                request.Study,
                request.RunTask,
                parameters = (object?)null,
                request.TimeoutSeconds
            };
            descriptors.Add(await artifacts.WriteJsonAsync(directories.Output, "request.json", requestArtifact));
            var adapterRequestPath = Path.Combine(directories.Work, "adapter-request.json");
            await File.WriteAllTextAsync(adapterRequestPath, JsonSerializer.Serialize(new
            {
                modelPath = directories.ModelCopy,
                study = request.Study,
                runTask = request.RunTask,
                parameters = (object?)null
            }));

            var remaining = deadline - DateTimeOffset.UtcNow;
            if (remaining <= TimeSpan.Zero)
            {
                terminalState = "TIMED_OUT";
                terminalError = new WorkerError("TIMEOUT", "RUN_TIMEOUT", "The configured run timeout elapsed during preparation.", false);
                terminalMessage = "Run timed out during preparation before Python startup.";
                return;
            }
            var adapter = await runner.RunAsync(
                adapterRequestPath,
                remaining,
                linkedCancellation.Token,
                (state, message) =>
                {
                    var controlledMessage = ControlledPhaseMessage(state);
                    registry.Transition(request.RunId, state, controlledMessage);
                    lock (log) log.Add(state + " " + controlledMessage);
                });
            processTreeConfirmed = adapter.ProcessTreeExitConfirmed;
            killUsed = adapter.KillUsed;
            envelope = adapter.Envelope?.Clone();

            if (!processTreeConfirmed)
            {
                blockRelease = true;
                terminalError = new WorkerError("CLEANUP", "PROCESS_TREE_EXIT_UNCONFIRMED", "Python process-tree exit could not be confirmed; global PIPESIM execution remains blocked.", false);
                terminalState = "FAILED";
                terminalMessage = "Process-tree cleanup was not confirmed; coordinator remains blocked.";
                return;
            }

            if (adapter.StopReason == AdapterStopReason.Cancelled)
            {
                terminalState = "CANCELLED";
                terminalError = adapter.Error;
                terminalMessage = "Run cancellation completed after process-tree exit confirmation.";
                return;
            }

            if (adapter.StopReason == AdapterStopReason.TimedOut)
            {
                terminalState = "TIMED_OUT";
                terminalError = adapter.Error;
                terminalMessage = "Run timed out after process-tree exit confirmation.";
                return;
            }

            if (linkedCancellation.IsCancellationRequested)
            {
                terminalState = "CANCELLED";
                terminalError = new WorkerError("CANCELLATION", "RUN_CANCELLED", "Run cancellation was requested.", false);
                terminalMessage = "Run cancellation completed after process-tree exit confirmation.";
                return;
            }

            if (adapter.Error is not null || envelope is null)
            {
                terminalError = adapter.Error ?? new WorkerError("PROTOCOL", "MISSING_RESULT_ENVELOPE", "The adapter returned no result envelope.", false);
                terminalMessage = terminalError.Message;
                return;
            }

            descriptors.Add(await artifacts.WriteJsonElementAsync(directories.Output, "raw-response.json", envelope.Value));
            if (!TryReadEnvelope(envelope.Value, request.RunTask!, out var envelopeStatus, out var parsedResult, out var parsedError, out var parsedWarning))
            {
                terminalError = new WorkerError("PROTOCOL", "INVALID_RESULT_ENVELOPE", "The adapter result envelope violates the Worker protocol.", false);
                terminalMessage = terminalError.Message;
                return;
            }

            if (envelopeStatus == "error")
            {
                terminalError = parsedError;
                terminalMessage = parsedError?.Message ?? "PIPESIM execution failed.";
                return;
            }

            result = parsedResult!.Value.Clone();
            descriptors.Add(await artifacts.WriteJsonElementAsync(directories.Output, "normalized-result.json", result.Value));
            terminalState = envelopeStatus == "partial" ? "PARTIAL_SUCCEEDED" : "SUCCEEDED";
            terminalError = envelopeStatus == "partial" ? parsedWarning : null;
            terminalMessage = terminalState == "PARTIAL_SUCCEEDED"
                ? "Nodal result succeeded; profile failed and was retained as an empty partial result."
                : "PIPESIM result completed successfully.";

            var sourceShaAfter = await storage.ComputeSha256Async(sourceModel, CancellationToken.None);
            if (!string.Equals(sourceShaAfter, request.ExpectedModelSha256, StringComparison.Ordinal))
            {
                terminalState = "FAILED";
                result = null;
                terminalError = new WorkerError("STORAGE", "SOURCE_MODEL_CHANGED", "The source model SHA-256 changed during execution.", false);
                terminalMessage = terminalError.Message;
            }
        }
        catch (StorageException exception)
        {
            terminalError = new WorkerError("STORAGE", exception.Code, exception.Message, false);
            terminalMessage = exception.Message;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            terminalError = new WorkerError("STORAGE", "STORAGE_IO_ERROR", "Worker storage could not be read or written.", true);
            terminalMessage = terminalError.Message;
        }
        catch (Exception)
        {
            terminalError = new WorkerError("EXECUTION", "WORKER_EXECUTION_FAILED", "The Worker could not complete the PIPESIM run.", false);
            terminalMessage = terminalError.Message;
        }
        finally
        {
            RunCleanup cleanup;
            try
            {
                try
                {
                    var sourceShaAfter = await storage.ComputeSha256Async(sourceModel, CancellationToken.None);
                    if (!string.Equals(sourceShaAfter, request.ExpectedModelSha256, StringComparison.Ordinal))
                    {
                        terminalState = "FAILED";
                        result = null;
                        terminalError = new WorkerError("STORAGE", "SOURCE_MODEL_CHANGED", "The source model SHA-256 changed during execution.", false);
                        terminalMessage = terminalError.Message;
                    }
                }
                catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
                {
                    terminalState = "FAILED";
                    result = null;
                    terminalError = new WorkerError("STORAGE", "SOURCE_MODEL_RECHECK_FAILED", "The source model could not be rechecked after execution.", false);
                    terminalMessage = terminalError.Message;
                }

                if (directories is not null)
                {
                    try
                    {
                        string[] logCopy;
                        lock (log) logCopy = log.ToArray();
                        var logDescriptor = await artifacts.WriteLogAsync(directories.Output, logCopy);
                        descriptors.Add(logDescriptor);
                        descriptors.Add(await artifacts.WriteManifestAsync(directories.Output, request.RunId, descriptors));
                    }
                    catch (Exception)
                    {
                        terminalError ??= new WorkerError("STORAGE", "ARTIFACT_WRITE_FAILED", "One or more controlled run artifacts could not be written.", false);
                        if (terminalState is "SUCCEEDED" or "PARTIAL_SUCCEEDED") terminalState = "FAILED";
                        terminalMessage = terminalError.Message;
                    }
                }

                var inputDeleted = directories is null || StorageResolver.TryDeleteDirectory(directories.Input);
                var workDeleted = directories is null || StorageResolver.TryDeleteDirectory(directories.Work);
                if (DateTimeOffset.UtcNow >= deadline && terminalState is "SUCCEEDED" or "PARTIAL_SUCCEEDED")
                {
                    terminalState = "TIMED_OUT";
                    result = null;
                    terminalError = new WorkerError("TIMEOUT", "RUN_TIMEOUT", "The configured run timeout elapsed during result collection.", false);
                    terminalMessage = "Run timed out after process-tree exit confirmation.";
                }
                if (linkedCancellation.IsCancellationRequested &&
                    terminalState is ("SUCCEEDED" or "PARTIAL_SUCCEEDED" or "FAILED") &&
                    terminalError?.Category is not ("STORAGE" or "CLEANUP"))
                {
                    terminalState = "CANCELLED";
                    result = null;
                    terminalError = new WorkerError("CANCELLATION", "RUN_CANCELLED", "Run cancellation was requested.", false);
                    terminalMessage = "Run cancellation completed after process-tree exit confirmation.";
                }
                cleanup = new RunCleanup(
                    processTreeConfirmed,
                    inputDeleted,
                    workDeleted,
                    killUsed,
                    processTreeConfirmed ? "Process-tree exit was confirmed before terminal publication." : "Process-tree exit is unconfirmed; PIPESIM coordination remains blocked.");
            }
            catch (Exception)
            {
                terminalState = "FAILED";
                result = null;
                terminalError = new WorkerError(
                    "CLEANUP",
                    "RUN_FINALIZATION_FAILED",
                    "Run finalization failed; Worker coordination recovery was attempted.",
                    false);
                terminalMessage = terminalError.Message;
                cleanup = new RunCleanup(
                    processTreeConfirmed,
                    directories is null || !Directory.Exists(directories.Input),
                    directories is null || !Directory.Exists(directories.Work),
                    killUsed,
                    "Run finalization failed before terminal publication.");
            }
            PublishCompletionAndRelease(
                registry,
                request.RunId,
                terminalState,
                result,
                terminalError,
                descriptors,
                cleanup,
                terminalMessage,
                lease,
                blockRelease);
        }
    }

    internal static RunCompletionResult PublishCompletionAndRelease(
        PtkRunRegistry registry,
        long runId,
        string terminalState,
        JsonElement? result,
        WorkerError? error,
        IReadOnlyList<ArtifactDescriptor> artifacts,
        RunCleanup cleanup,
        string message,
        PtkExecutionCoordinator.CoordinatorLease lease,
        bool blockRelease)
    {
        try
        {
            return registry.Complete(runId, terminalState, result, error, artifacts, cleanup, message);
        }
        catch (Exception)
        {
            registry.TryFailCompletion(runId, artifacts, cleanup);
            return new RunCompletionResult(RunCompletionDisposition.PublicationFailed, "FAILED");
        }
        finally
        {
            if (blockRelease)
            {
                lease.BlockRelease();
            }
            else
            {
                lease.Dispose();
            }
        }
    }

    private WorkerError? ValidateRequest(RunExecuteRequest request)
    {
        if (request.RunId <= 0) return WorkerApiError.Request("INVALID_RUN_ID", "runId must be a positive int64.");
        if (string.IsNullOrWhiteSpace(request.ModelStorageKey)) return WorkerApiError.Request("MODEL_STORAGE_KEY_REQUIRED", "modelStorageKey is required.");
        if (string.IsNullOrWhiteSpace(request.ExpectedModelSha256) || !LowerSha256().IsMatch(request.ExpectedModelSha256))
            return WorkerApiError.Request("INVALID_EXPECTED_SHA256", "expectedModelSha256 must be 64 lowercase hexadecimal characters.");
        if (string.IsNullOrWhiteSpace(request.Study) || request.Study.Length > 256 || request.Study.Any(char.IsControl))
            return WorkerApiError.Request("INVALID_STUDY", "study is required and must be a controlled model Study name.");
        if (request.RunTask is not ("nodal" or "profile" or "combined"))
            return WorkerApiError.Request("INVALID_RUN_TASK", "runTask must be nodal, profile, or combined.");
        if (request.Parameters.ValueKind != JsonValueKind.Null)
            return WorkerApiError.Request("PARAMETERS_NOT_NULL", "parameters is required and must be explicitly null.");
        if (request.TimeoutSeconds <= 0 || request.TimeoutSeconds > options.MaxRunTimeoutSeconds)
            return WorkerApiError.Request("INVALID_TIMEOUT", $"timeoutSeconds must be between 1 and {options.MaxRunTimeoutSeconds}.");
        return null;
    }

    private static bool TryReadEnvelope(
        JsonElement envelope,
        string expectedRunTask,
        out string? status,
        out JsonElement? result,
        out WorkerError? error,
        out WorkerError? warning)
    {
        status = null;
        result = null;
        error = null;
        warning = null;
        if (!envelope.TryGetProperty("status", out var statusElement) || statusElement.ValueKind != JsonValueKind.String) return false;
        status = statusElement.GetString();
        if (status == "error")
        {
            if (!envelope.TryGetProperty("error", out var errorElement) || errorElement.ValueKind != JsonValueKind.Object) return false;
            try
            {
                error = JsonSerializer.Deserialize<WorkerError>(errorElement, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                return error is not null && !string.IsNullOrWhiteSpace(error.Category) && !string.IsNullOrWhiteSpace(error.Code);
            }
            catch (JsonException) { return false; }
        }

        if (status is not ("ok" or "partial") || !envelope.TryGetProperty("result", out var resultElement) || resultElement.ValueKind != JsonValueKind.Object) return false;
        if (!resultElement.TryGetProperty("schemaVersion", out var schema) || schema.GetString() != "pipesim-well-result/1" ||
            !resultElement.TryGetProperty("runTask", out var runTask) || runTask.GetString() != expectedRunTask ||
            !resultElement.TryGetProperty("resultContract", out var contract)) return false;
        var expectedContract = status == "partial" ? "VALID_PARTIAL" : "VALID_FULL";
        if (contract.GetString() != expectedContract) return false;
        foreach (var arrayName in new[] { "ipr", "vlp", "profile" })
        {
            if (!resultElement.TryGetProperty(arrayName, out var array) || array.ValueKind != JsonValueKind.Array) return false;
        }
        if (status == "partial")
        {
            if (!envelope.TryGetProperty("warnings", out var warnings) || warnings.ValueKind != JsonValueKind.Array || warnings.GetArrayLength() != 1) return false;
            try
            {
                warning = JsonSerializer.Deserialize<WorkerError>(warnings[0], new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
            }
            catch (JsonException) { return false; }
            if (warning is null) return false;
        }
        result = resultElement.Clone();
        return true;
    }

    private static string Fingerprint(RunExecuteRequest request)
    {
        var canonical = string.Join("\n", request.RunId, request.ModelStorageKey, request.ExpectedModelSha256, request.Study, request.RunTask, "null", request.TimeoutSeconds);
        return Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(canonical)));
    }

    private static string ControlledPhaseMessage(string state) => state switch
    {
        "RUNNING_NODAL" => "Running the selected Study nodal analysis.",
        "RUNNING_PROFILE" => "Running the selected Study pressure-temperature profile.",
        "COLLECTING" => "Collecting and normalizing PIPESIM result arrays.",
        _ => throw new InvalidOperationException("The PIPESIM adapter emitted an unknown phase state.")
    };

    [GeneratedRegex("^[0-9a-f]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex LowerSha256();
}
