using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed partial class PtkRunService : IDisposable
{
    private static readonly Regex DrivePath = new(@"(?<![a-z0-9])[a-z]:[\\/][^\s\""']*", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex UncPath = new(@"(?<!\\)\\\\[^\\/\s]+\\[^\s\""']+", RegexOptions.CultureInvariant);
    private static readonly Regex UnixPath = new(@"(?<![a-z0-9:/])/(?:[^\s\""']+)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex FileUri = new(@"file://[^\s\""']+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex LocalPipe = new(@"net\.pipe://[^\s\""']+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex SensitiveText = new(@"\b(?:password|passwd|pwd|secret|credential(?:s)?|authorization|cookie|api[ _-]?key|access[ _-]?token|refresh[ _-]?token|token|license(?:[ _-]?(?:key|file|server))?)\b", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
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
            terminalMessage = CompletionMessage(terminalState, request.RunTask!);

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
        if (request.RunTask is not ("nodal" or "profile" or "combined" or "network"))
            return WorkerApiError.Request("INVALID_RUN_TASK", "runTask must be nodal, profile, combined, or network.");
        if (request.Parameters.ValueKind != JsonValueKind.Null)
            return WorkerApiError.Request("PARAMETERS_NOT_NULL", "parameters is required and must be explicitly null.");
        if (request.TimeoutSeconds <= 0 || request.TimeoutSeconds > options.MaxRunTimeoutSeconds)
            return WorkerApiError.Request("INVALID_TIMEOUT", $"timeoutSeconds must be between 1 and {options.MaxRunTimeoutSeconds}.");
        return null;
    }

    internal static string CompletionMessage(string terminalState, string runTask) =>
        terminalState == "PARTIAL_SUCCEEDED"
            ? runTask == "network"
                ? "PIPESIM Network calculation completed with a limited display result."
                : "Nodal result succeeded; profile failed and was retained as an empty partial result."
            : runTask == "network"
                ? "PIPESIM Network result completed successfully."
                : "PIPESIM result completed successfully.";

    internal static bool TryReadEnvelope(
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
        if (expectedRunTask == "network")
        {
            if (!resultElement.TryGetProperty("schemaVersion", out var networkSchema) || networkSchema.GetString() != "pipesim-network-result/1" ||
                !resultElement.TryGetProperty("model_kind", out var modelKind) || modelKind.GetString() != "network" ||
                !resultElement.TryGetProperty("runTask", out var networkTask) || networkTask.GetString() != "network" ||
                !IsSafeTextProperty(resultElement, "study", false) ||
                !resultElement.TryGetProperty("simulationState", out var simulationState) || simulationState.GetString() != "Completed" ||
                !resultElement.TryGetProperty("topology", out var topology) || !HasValidNetworkTopology(topology))
            {
                return false;
            }
            if (!resultElement.TryGetProperty("resultContract", out var networkContract)) return false;
            if (status == "partial")
            {
                if (networkContract.GetString() != "VALID_PARTIAL" ||
                    !TryReadNetworkLimitedWarning(envelope, out warning)) return false;
                result = SanitizePartialNetworkResult(resultElement);
                return true;
            }
            if (networkContract.GetString() != "VALID_FULL" ||
                !resultElement.TryGetProperty("system", out var system) || system.ValueKind != JsonValueKind.Array ||
                !resultElement.TryGetProperty("node", out var node) || node.ValueKind != JsonValueKind.Array ||
                !resultElement.TryGetProperty("profiles", out var profiles) || profiles.ValueKind != JsonValueKind.Array || profiles.GetArrayLength() == 0 ||
                !resultElement.TryGetProperty("summary", out var summary) || summary.ValueKind != JsonValueKind.Object ||
                !resultElement.TryGetProperty("messages", out var messages) || messages.ValueKind != JsonValueKind.Array ||
                !resultElement.TryGetProperty("quality", out var quality) || quality.ValueKind != JsonValueKind.Array ||
                !HasSafeNetworkPayload(resultElement))
            {
                return false;
            }
            result = resultElement.Clone();
            return true;
        }
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

    private static bool HasValidNetworkTopology(JsonElement topology)
    {
        if (topology.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(topology, "nodes", "edges", "counts") ||
            !topology.TryGetProperty("nodes", out var nodes) || nodes.ValueKind != JsonValueKind.Array || nodes.GetArrayLength() == 0 ||
            !topology.TryGetProperty("edges", out var edges) || edges.ValueKind != JsonValueKind.Array || edges.GetArrayLength() == 0 ||
            !topology.TryGetProperty("counts", out var counts) || counts.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(counts, "nodes", "edges", "sources", "sinks", "flowlines"))
        {
            return false;
        }

        var nodeIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var node in nodes.EnumerateArray())
        {
            if (node.ValueKind != JsonValueKind.Object ||
                !HasExactlyProperties(node, "id", "componentType") ||
                !node.TryGetProperty("id", out var id) || !IsSafeTextValue(id, false) ||
                !node.TryGetProperty("componentType", out var componentType) || !IsSafeTextValue(componentType, false) ||
                !nodeIds.Add(id.GetString()!))
            {
                return false;
            }
        }

        foreach (var edge in edges.EnumerateArray())
        {
            if (edge.ValueKind != JsonValueKind.Object ||
                !HasExactlyProperties(edge, "source", "destination", "sourcePort") ||
                !edge.TryGetProperty("source", out var source) || !IsSafeTextValue(source, false) ||
                !edge.TryGetProperty("destination", out var destination) || !IsSafeTextValue(destination, false) ||
                !edge.TryGetProperty("sourcePort", out var sourcePort) || !IsSafeTextValue(sourcePort, true) ||
                !nodeIds.Contains(source.GetString()!) || !nodeIds.Contains(destination.GetString()!))
            {
                return false;
            }
        }

        foreach (var countName in new[] { "nodes", "edges", "sources", "sinks", "flowlines" })
        {
            if (!counts.TryGetProperty(countName, out var count) || count.ValueKind != JsonValueKind.Number ||
                !count.TryGetInt32(out var value) || value < 0)
            {
                return false;
            }
        }

        var sourceCount = CountComponents(nodes, "SOURCE") + CountComponents(nodes, "WELL");
        var reportedSourceCount = counts.GetProperty("sources").GetInt32();
        return counts.GetProperty("nodes").GetInt32() == nodes.GetArrayLength() &&
               counts.GetProperty("edges").GetInt32() == edges.GetArrayLength() &&
               reportedSourceCount == sourceCount &&
               counts.GetProperty("sinks").GetInt32() == CountComponents(nodes, "SINK") &&
               counts.GetProperty("flowlines").GetInt32() == CountComponents(nodes, "FLOWLINE");
    }

    private static int CountComponents(JsonElement nodes, string componentType) => nodes.EnumerateArray().Count(node =>
        string.Equals(node.GetProperty("componentType").GetString(), componentType, StringComparison.OrdinalIgnoreCase));

    private static JsonElement SanitizePartialNetworkResult(JsonElement result)
    {
        var sanitized = new JsonObject();
        foreach (var property in new[]
        {
            "schemaVersion", "model_kind", "runTask", "resultContract", "study", "simulationState", "topology"
        })
        {
            sanitized[property] = JsonNode.Parse(result.GetProperty(property).GetRawText());
        }
        foreach (var section in new[] { "system", "node" })
        {
            AddSanitizedPartialArray(result, sanitized, section, IsSafeScalarSeries);
        }
        AddSanitizedPartialArray(result, sanitized, "profiles", IsSafeProfile);
        return JsonSerializer.SerializeToElement(sanitized);
    }

    private static bool HasSafeNetworkPayload(JsonElement result)
    {
        foreach (var section in new[] { "system", "node" })
        {
            if (result.TryGetProperty(section, out var groups) &&
                (groups.ValueKind != JsonValueKind.Array || !groups.EnumerateArray().All(IsSafeScalarSeries))) return false;
        }
        return !result.TryGetProperty("profiles", out var profiles) ||
               profiles.ValueKind == JsonValueKind.Array && profiles.EnumerateArray().All(IsSafeProfile);
    }

    private static void AddSanitizedPartialArray(
        JsonElement source,
        JsonObject result,
        string section,
        Func<JsonElement, bool> isSafe)
    {
        if (!source.TryGetProperty(section, out var values) || values.ValueKind != JsonValueKind.Array) return;

        var safeValues = new JsonArray();
        foreach (var item in values.EnumerateArray())
        {
            if (isSafe(item)) safeValues.Add(JsonNode.Parse(item.GetRawText()));
        }
        result[section] = safeValues;
    }

    private static bool IsSafeScalarSeries(JsonElement group) =>
        group.ValueKind == JsonValueKind.Object && HasExactlyProperties(group, "variable", "unit", "values") &&
        IsSafeTextProperty(group, "variable", false) && IsSafeTextProperty(group, "unit", true) &&
        group.TryGetProperty("values", out var values) && values.ValueKind == JsonValueKind.Array && values.GetArrayLength() > 0 &&
        values.EnumerateArray().All(item => item.ValueKind == JsonValueKind.Object && HasExactlyProperties(item, "name", "value") &&
            IsSafeTextProperty(item, "name", false) && item.TryGetProperty("value", out var value) && HasSafeNumericLeaves(value));

    private static bool IsSafeProfile(JsonElement profile)
    {
        if (profile.ValueKind != JsonValueKind.Object || !HasExactlyProperties(profile, "branch", "pointCount", "variables") ||
            !IsSafeTextProperty(profile, "branch", false) || !profile.TryGetProperty("pointCount", out var pointCount) ||
            !pointCount.TryGetInt32(out var count) || count < 0 || !profile.TryGetProperty("variables", out var variables) || variables.ValueKind != JsonValueKind.Array)
            return false;
        return variables.GetArrayLength() > 0 &&
               variables.EnumerateArray().All(variable => IsSafeProfileSeries(variable, count)) &&
               HasMatchingRequiredProfileSeries(variables, "TotalDistance") &&
               HasMatchingRequiredProfileSeries(variables, "Pressure");
    }

    private static bool IsSafeProfileSeries(JsonElement variable, int pointCount) =>
        variable.ValueKind == JsonValueKind.Object && HasExactlyProperties(variable, "variable", "unit", "values") &&
        IsSafeTextProperty(variable, "variable", false) && IsSafeTextProperty(variable, "unit", true) &&
        variable.TryGetProperty("values", out var values) && values.ValueKind == JsonValueKind.Array &&
        values.GetArrayLength() > 0 && values.GetArrayLength() <= pointCount &&
        (variable.GetProperty("variable").GetString() == "BranchEquipment"
            ? values.EnumerateArray().All(IsSafeTextValue)
            : values.EnumerateArray().All(HasSafeNumericLeaves));

    private static bool HasMatchingRequiredProfileSeries(JsonElement variables, string requiredVariable)
    {
        JsonElement? values = null;
        foreach (var variable in variables.EnumerateArray())
        {
            if (variable.GetProperty("variable").GetString() == requiredVariable)
            {
                values = variable.GetProperty("values");
                break;
            }
        }
        return values is { ValueKind: JsonValueKind.Array } series && series.GetArrayLength() > 0 &&
               variables.EnumerateArray().Any(variable => variable.GetProperty("variable").GetString() ==
                   (requiredVariable == "TotalDistance" ? "Pressure" : "TotalDistance") &&
                   variable.GetProperty("values").GetArrayLength() == series.GetArrayLength());
    }

    private static bool HasSafeNumericLeaves(JsonElement value) => value.ValueKind switch
    {
        JsonValueKind.Null => true,
        JsonValueKind.Number => value.TryGetDouble(out var number) && double.IsFinite(number),
        JsonValueKind.Array => value.GetArrayLength() > 0 && value.EnumerateArray().All(HasSafeNumericLeaves),
        JsonValueKind.Object => value.EnumerateObject().Any() && value.EnumerateObject().All(property => HasSafeNumericLeaves(property.Value)),
        _ => false
    };

    private static bool IsSafeTextValue(JsonElement value) => value.ValueKind == JsonValueKind.Null ||
        IsSafeTextValue(value, false);

    private static bool IsSafeTextValue(JsonElement value, bool allowEmpty) =>
        value.ValueKind == JsonValueKind.String &&
        (allowEmpty || !string.IsNullOrWhiteSpace(value.GetString())) &&
        IsSafeText(value.GetString()!);

    private static bool IsSafeTextProperty(JsonElement value, string property, bool allowEmpty) =>
        value.TryGetProperty(property, out var text) && IsSafeTextValue(text, allowEmpty);

    private static bool IsSafeText(string value) => !HasControlCharacter(value) &&
        !DrivePath.IsMatch(value) && !UncPath.IsMatch(value) && !UnixPath.IsMatch(value) &&
        !FileUri.IsMatch(value) && !LocalPipe.IsMatch(value) && !SensitiveText.IsMatch(value);

    private static bool HasExactlyProperties(JsonElement value, params string[] names) =>
        value.EnumerateObject().Select(property => property.Name).OrderBy(name => name).SequenceEqual(names.OrderBy(name => name), StringComparer.Ordinal);

    private static bool HasControlCharacter(string value) => value.Any(char.IsControl);

    private static bool TryReadNetworkLimitedWarning(JsonElement envelope, out WorkerError? warning)
    {
        warning = null;
        if (!envelope.TryGetProperty("warnings", out var warnings) || warnings.ValueKind != JsonValueKind.Array || warnings.GetArrayLength() != 1) return false;
        try
        {
            warning = JsonSerializer.Deserialize<WorkerError>(warnings[0], new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        }
        catch (JsonException) { return false; }
        return warning is { Category: "PROTOCOL", Code: "NETWORK_RESULT_LIMITED" };
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
        "RUNNING_NETWORK" => "Running the selected Study network simulation.",
        "COLLECTING" => "Collecting and normalizing PIPESIM result arrays.",
        _ => throw new InvalidOperationException("The PIPESIM adapter emitted an unknown phase state.")
    };

    [GeneratedRegex("^[0-9a-f]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex LowerSha256();
}
