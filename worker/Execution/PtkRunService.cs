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
            await PipesimPackageIntegrity.VerifyAsync(
                storage.ResolveModelPackageRoot(sourceModel), request.ExpectedPackageFiles, CancellationToken.None);

            directories = storage.CreateRunDirectories(request.RunId, sourceModel);
            var copySha = await storage.ComputeSha256Async(directories.ModelCopy, CancellationToken.None);
            if (!string.Equals(copySha, request.ExpectedModelSha256, StringComparison.Ordinal))
            {
                throw new StorageException("MODEL_COPY_SHA256_MISMATCH", "The task model copy failed SHA-256 verification.");
            }
            await PipesimPackageIntegrity.VerifyAsync(
                directories.Input, request.ExpectedPackageFiles, CancellationToken.None);

            var requestArtifact = new
            {
                request.RunId,
                request.ModelStorageKey,
                request.ExpectedModelSha256,
                request.Study,
                request.RunTask,
                parameters = request.Parameters,
                request.TimeoutSeconds
            };
            descriptors.Add(await artifacts.WriteJsonAsync(directories.Output, "request.json", requestArtifact));
            var adapterRequestPath = Path.Combine(directories.Work, "adapter-request.json");
            await File.WriteAllTextAsync(adapterRequestPath, JsonSerializer.Serialize(new
            {
                modelPath = directories.ModelCopy,
                study = request.Study,
                runTask = request.RunTask,
                parameters = request.Parameters
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
                    if (string.Equals(registry.GetState(request.RunId), state, StringComparison.Ordinal))
                    {
                        registry.Report(request.RunId, state, controlledMessage);
                    }
                    else
                    {
                        registry.Transition(request.RunId, state, controlledMessage);
                    }
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
            if (request.RunTask == "network-optimizer" && NetworkOptimizerApplyRequested(request.Parameters))
            {
                // Python has closed the PTK model before the copy is published. The
                // artifact is therefore the persisted isolated model copy, never the
                // uploaded source model.
                descriptors.Add(await artifacts.CopyPipesimAppliedModelAsync(
                    directories.Output, directories.ModelCopy, CancellationToken.None));
            }
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
            if (terminalState is "SUCCEEDED" or "PARTIAL_SUCCEEDED")
            {
                await PipesimPackageIntegrity.VerifyAsync(
                    storage.ResolveModelPackageRoot(sourceModel), request.ExpectedPackageFiles, CancellationToken.None);
            }
        }
        catch (StorageException exception)
        {
            if (terminalState is "SUCCEEDED" or "PARTIAL_SUCCEEDED")
            {
                terminalState = "FAILED";
                result = null;
            }
            terminalError = new WorkerError("STORAGE", exception.Code, exception.Message, false);
            terminalMessage = exception.Message;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            if (terminalState is "SUCCEEDED" or "PARTIAL_SUCCEEDED")
            {
                terminalState = "FAILED";
                result = null;
            }
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

                if (terminalState is "SUCCEEDED" or "PARTIAL_SUCCEEDED")
                {
                    try
                    {
                        await PipesimPackageIntegrity.VerifyAsync(
                            storage.ResolveModelPackageRoot(sourceModel), request.ExpectedPackageFiles, CancellationToken.None);
                    }
                    catch (StorageException exception)
                    {
                        terminalState = "FAILED";
                        result = null;
                        terminalError = new WorkerError("STORAGE", exception.Code, exception.Message, false);
                        terminalMessage = terminalError.Message;
                    }
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
        if (request.RunTask is not ("nodal" or "profile" or "combined" or "sensitivity" or "network" or "system-analysis" or "network-optimizer" or "gas-lift-performance" or "gas-lift-diagnostics" or "vfp-tables" or "esp-curves" or "trajectory"))
            return WorkerApiError.Request("INVALID_RUN_TASK", "runTask must be nodal, profile, combined, sensitivity, network, system-analysis, network-optimizer, gas-lift-performance, gas-lift-diagnostics, vfp-tables, esp-curves, or trajectory.");
        if (!WellScenarioParameters.Valid(request.Parameters, request.RunTask))
            return WorkerApiError.Request("INVALID_SCENARIO_PARAMETERS", "Only validated well/network scenarios, sensitivity parameters, or explicit null parameters are supported.");
        if (request.TimeoutSeconds <= 0 || request.TimeoutSeconds > options.MaxRunTimeoutSeconds)
            return WorkerApiError.Request("INVALID_TIMEOUT", $"timeoutSeconds must be between 1 and {options.MaxRunTimeoutSeconds}.");
        if (request.ExpectedPackageFiles is not null && !PipesimPackageIntegrity.IsValidManifest(request.ExpectedPackageFiles))
            return WorkerApiError.Request("INVALID_PACKAGE_MANIFEST", "The persisted PIPESIM package manifest is invalid.");
        return null;
    }

    private static bool NetworkOptimizerApplyRequested(JsonElement parameters) =>
        parameters.ValueKind == JsonValueKind.Object &&
        parameters.TryGetProperty("schemaVersion", out var schema) &&
        schema.ValueKind == JsonValueKind.String &&
        schema.GetString() == "pipesim-network-optimizer-parameters/2" &&
        parameters.TryGetProperty("applyResults", out var applyResults) &&
        applyResults.ValueKind == JsonValueKind.True;

    internal static string CompletionMessage(string terminalState, string runTask) =>
        terminalState == "PARTIAL_SUCCEEDED"
                ? runTask == "system-analysis"
                ? "PIPESIM System Analysis completed with a limited display result."
            : runTask is "network" or "network-optimizer"
                ? "PIPESIM Network calculation completed with a limited display result."
                : "Nodal result succeeded; profile failed and was retained as an empty partial result."
            : runTask == "system-analysis"
                ? "PIPESIM System Analysis result completed successfully."
            : runTask is "network" or "network-optimizer"
                ? "PIPESIM Network result completed successfully."
            : runTask == "sensitivity"
                    ? "PIPESIM sensitivity calculation completed successfully."
                : runTask == "gas-lift-performance"
                    ? "PIPESIM Gas Lift Performance calculation completed successfully."
                : runTask == "gas-lift-diagnostics"
                    ? "PIPESIM Gas Lift Diagnostics calculation completed successfully."
                : runTask == "vfp-tables"
                    ? "PIPESIM VFP Tables calculation completed successfully."
                : runTask == "esp-curves"
                    ? "PIPESIM ESP curve calculation completed successfully."
                : runTask == "trajectory"
                    ? "PIPESIM well trajectory read completed successfully."
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
        if (expectedRunTask == "network-optimizer")
        {
            if (status != "ok" || !HasValidNetworkOptimizerResult(resultElement)) return false;
            result = resultElement.Clone();
            return true;
        }
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
        if (expectedRunTask == "sensitivity")
        {
            if (status != "ok" || !HasValidSensitivityResult(resultElement)) return false;
            result = resultElement.Clone();
            return true;
        }
        if (expectedRunTask == "system-analysis")
        {
            if (status != "ok" || !HasValidSystemAnalysisResult(resultElement)) return false;
            result = resultElement.Clone();
            return true;
        }
        if (expectedRunTask == "gas-lift-performance")
        {
            if (status != "ok" || !HasValidGasLiftPerformanceResult(resultElement)) return false;
            result = resultElement.Clone();
            return true;
        }
        if (expectedRunTask == "gas-lift-diagnostics")
        {
            if (status != "ok" || !HasValidGasLiftDiagnosticsResult(resultElement)) return false;
            result = resultElement.Clone();
            return true;
        }
        if (expectedRunTask == "vfp-tables")
        {
            if (status != "ok" || !HasValidVfpTablesResult(resultElement)) return false;
            result = resultElement.Clone();
            return true;
        }
        if (expectedRunTask == "esp-curves")
        {
            if (status != "ok" || !HasValidEspCurvesResult(resultElement)) return false;
            result = resultElement.Clone();
            return true;
        }
        if (expectedRunTask == "trajectory")
        {
            if (status != "ok" || !HasValidTrajectoryResult(resultElement)) return false;
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

    private static bool HasValidNetworkOptimizerResult(JsonElement result)
    {
        if (result.ValueKind != JsonValueKind.Object ||
            !result.TryGetProperty("schemaVersion", out var schemaVersion) ||
            schemaVersion.ValueKind != JsonValueKind.String) return false;
        var schema = schemaVersion.GetString();
        var applied = schema == "pipesim-network-optimizer-result/2";
        if (!applied && schema != "pipesim-network-optimizer-result/1") return false;
        var fields = applied
            ? new[] { "schemaVersion", "model_kind", "runTask", "resultContract", "simulationState",
                "summary", "messages", "variables", "wells", "flowlines", "sinks", "quality", "application" }
            : new[] { "schemaVersion", "model_kind", "runTask", "resultContract", "simulationState",
                "summary", "messages", "variables", "wells", "flowlines", "sinks", "quality" };
        if (result.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(result, fields) ||
            !IsSafeTextProperty(result, "schemaVersion", false) ||
            !IsSafeTextProperty(result, "model_kind", false) || result.GetProperty("model_kind").GetString() != "network" ||
            !IsSafeTextProperty(result, "runTask", false) || result.GetProperty("runTask").GetString() != "network-optimizer" ||
            !IsSafeTextProperty(result, "resultContract", false) || result.GetProperty("resultContract").GetString() != "VALID_FULL" ||
            !IsSafeTextProperty(result, "simulationState", false) || result.GetProperty("simulationState").GetString() != "Completed") return false;
        if (!HasValidOptimizerSummary(result.GetProperty("summary")) || !HasSafeTextArray(result.GetProperty("messages"))) return false;
        var variables = result.GetProperty("variables");
        if (variables.ValueKind != JsonValueKind.Array || variables.GetArrayLength() == 0 || variables.GetArrayLength() > 128) return false;
        var keys = new HashSet<string>(StringComparer.Ordinal);
        foreach (var variable in variables.EnumerateArray())
        {
            if (variable.ValueKind != JsonValueKind.Object || !HasExactlyProperties(variable, "key", "label", "unit") ||
                !IsSafeTextProperty(variable, "key", false) || !IsSafeTextProperty(variable, "label", false) ||
                !IsSafeTextProperty(variable, "unit", true) || !keys.Add(variable.GetProperty("key").GetString()!)) return false;
        }
        var missing = new HashSet<string>(StringComparer.Ordinal);
        var data = new HashSet<string>(StringComparer.Ordinal);
        foreach (var group in new[] { "wells", "flowlines", "sinks" })
        {
            if (!HasValidOptimizerGroups(result.GetProperty(group), group, keys, data, missing)) return false;
        }
        if (!result.GetProperty("quality").ValueKind.Equals(JsonValueKind.Array)) return false;
        var quality = new HashSet<string>(StringComparer.Ordinal);
        foreach (var item in result.GetProperty("quality").EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object || !HasExactlyProperties(item, "path", "code") ||
                !IsSafeTextProperty(item, "path", false) || !IsSafeTextProperty(item, "code", false) ||
                item.GetProperty("code").GetString() != "UNAVAILABLE" || !quality.Add(item.GetProperty("path").GetString()!)) return false;
        }
        return quality.SetEquals(missing) && (!applied || HasValidOptimizerApplication(result.GetProperty("application")));
    }

    private static bool HasValidOptimizerApplication(JsonElement application)
    {
        if (application.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(application, "requested", "applied", "scope", "sourceModelUnchanged", "artifactName", "changes") ||
            application.GetProperty("requested").ValueKind != JsonValueKind.True ||
            application.GetProperty("applied").ValueKind != JsonValueKind.True ||
            !IsSafeTextProperty(application, "scope", false) || application.GetProperty("scope").GetString() != "isolated-model-copy" ||
            application.GetProperty("sourceModelUnchanged").ValueKind != JsonValueKind.True ||
            !IsSafeTextProperty(application, "artifactName", false) ||
            application.GetProperty("artifactName").GetString() != "pipesim-network-optimizer-applied.pips") return false;
        var changes = application.GetProperty("changes");
        if (changes.ValueKind != JsonValueKind.Array || changes.GetArrayLength() > 256) return false;
        foreach (var change in changes.EnumerateArray())
        {
            if (change.ValueKind != JsonValueKind.Object ||
                !HasExactlyProperties(change, "context", "parameter", "unit", "before", "after") ||
                !IsSafeTextProperty(change, "context", false) ||
                !IsSafeTextProperty(change, "parameter", false) || change.GetProperty("parameter").GetString() != "GasRate" ||
                !IsSafeTextProperty(change, "unit", true) ||
                !FiniteNumber(change.GetProperty("before")) || !FiniteNumber(change.GetProperty("after"))) return false;
        }
        return true;
    }

    private static bool HasValidOptimizerSummary(JsonElement summary)
    {
        if (summary.ValueKind != JsonValueKind.Object || !HasExactlyProperties(summary, "info", "warnings", "errors")) return false;
        return new[] { "info", "warnings", "errors" }.All(field => HasSafeTextArray(summary.GetProperty(field)));
    }

    private static bool HasSafeTextArray(JsonElement values) =>
        values.ValueKind == JsonValueKind.Array && values.GetArrayLength() <= 256 && values.EnumerateArray().All(value => IsSafeTextValue(value, true));

    private static bool HasValidOptimizerGroups(JsonElement groups, string groupName, HashSet<string> keys,
                                                HashSet<string> data, HashSet<string> missing)
    {
        if (groups.ValueKind != JsonValueKind.Array || groups.GetArrayLength() == 0 || groups.GetArrayLength() > 256) return false;
        var names = new HashSet<string>(StringComparer.Ordinal);
        foreach (var group in groups.EnumerateArray())
        {
            if (group.ValueKind != JsonValueKind.Object || !HasExactlyProperties(group, "name", "values") ||
                !IsSafeTextProperty(group, "name", false) || !names.Add(group.GetProperty("name").GetString()!)) return false;
            var values = group.GetProperty("values");
            if (values.ValueKind != JsonValueKind.Array || values.GetArrayLength() > 128) return false;
            var seen = new HashSet<string>(StringComparer.Ordinal);
            foreach (var value in values.EnumerateArray())
            {
                if (value.ValueKind != JsonValueKind.Object || !HasExactlyProperties(value, "key", "value") ||
                    !IsSafeTextProperty(value, "key", false)) return false;
                var key = value.GetProperty("key").GetString()!;
                if (!keys.Contains(key) || !seen.Add(key)) return false;
                var path = groupName + "." + group.GetProperty("name").GetString() + "." + key;
                if (!data.Add(path)) return false;
                var scalar = value.GetProperty("value");
                if (scalar.ValueKind == JsonValueKind.Null)
                {
                    if (!missing.Add(path)) return false;
                }
                else if (scalar.ValueKind == JsonValueKind.Number)
                {
                    if (!FiniteNumber(scalar)) return false;
                }
                else if (scalar.ValueKind != JsonValueKind.True && scalar.ValueKind != JsonValueKind.False) return false;
            }
        }
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
            if (result.TryGetProperty(section, out var groups))
            {
                if (groups.ValueKind != JsonValueKind.Array) return false;
                foreach (var group in groups.EnumerateArray())
                {
                    if (!IsSafeScalarSeries(group)) return false;
                }
            }
        }
        if (result.TryGetProperty("profiles", out var profiles))
        {
            if (profiles.ValueKind != JsonValueKind.Array) return false;
            foreach (var profile in profiles.EnumerateArray())
            {
                if (!IsSafeProfile(profile)) return false;
            }
        }
        if (!result.TryGetProperty("summary", out var summary) || !IsSafeNetworkSummary(summary)) return false;
        if (!result.TryGetProperty("messages", out var messages) || !IsSafeTextArray(messages)) return false;
        if (!result.TryGetProperty("quality", out var quality) || !IsSafeNetworkQuality(quality)) return false;
        return true;
    }

    private static bool HasValidSensitivityResult(JsonElement result)
    {
        if (result.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(result, "schemaVersion", "model_kind", "runTask", "resultContract", "targetVariable", "units", "cases") ||
            result.GetProperty("schemaVersion").GetString() != "pipesim-well-sensitivity-result/1" ||
            result.GetProperty("model_kind").GetString() is not ("black_oil_liquid" or "basic_gas") ||
            result.GetProperty("runTask").GetString() != "sensitivity" ||
            result.GetProperty("resultContract").GetString() != "VALID_FULL" ||
            !IsSafeTextValue(result.GetProperty("targetVariable"), false)) return false;

        var modelKind = result.GetProperty("model_kind").GetString()!;
        var target = result.GetProperty("targetVariable").GetString();
        if (target is not ("reservoirPressure" or "waterCut" or "gor" or "tubingInnerDiameter") ||
            (modelKind == "basic_gas" && target is "waterCut" or "gor") ||
            !HasValidSensitivityUnits(result.GetProperty("units"), modelKind)) return false;
        if (!result.TryGetProperty("cases", out var cases) || cases.ValueKind != JsonValueKind.Array ||
            cases.GetArrayLength() < 2 || cases.GetArrayLength() > 12) return false;

        var previous = double.NegativeInfinity;
        foreach (var item in cases.EnumerateArray())
        {
            if (!IsSafeSensitivityCase(item) || item.GetProperty("value").GetDouble() <= previous) return false;
            previous = item.GetProperty("value").GetDouble();
        }
        return true;
    }

    private static bool HasValidSensitivityUnits(JsonElement units, string modelKind) =>
        units.ValueKind == JsonValueKind.Object && HasExactlyProperties(units, "flow", "pressure", "depth", "temperature") &&
        HasUnit(units.GetProperty("flow"), modelKind == "basic_gas" ? "mmscf/d" : null,
            modelKind == "basic_gas" ? "standard_gas_volume_rate" : "unspecified") &&
        HasUnit(units.GetProperty("pressure"), null, "unspecified") &&
        HasUnit(units.GetProperty("depth"), null, "unspecified") &&
        HasUnit(units.GetProperty("temperature"), null, "unspecified");

    private static bool HasUnit(JsonElement unit, string? displayUnit, string semantics) =>
        unit.ValueKind == JsonValueKind.Object && HasExactlyProperties(unit, "displayUnit", "semantics") &&
        ((displayUnit is null && unit.GetProperty("displayUnit").ValueKind == JsonValueKind.Null) ||
         (displayUnit is not null && unit.GetProperty("displayUnit").ValueKind == JsonValueKind.String && unit.GetProperty("displayUnit").GetString() == displayUnit)) &&
        IsSafeTextValue(unit.GetProperty("semantics"), false) && unit.GetProperty("semantics").GetString() == semantics;

    private static bool HasValidSystemAnalysisResult(JsonElement result)
    {
        if (result.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(result, "schemaVersion", "model_kind", "runTask", "resultContract", "study",
                "producer", "branchTerminator", "outletPressurePsi", "scanVariable", "cases") ||
            result.GetProperty("schemaVersion").GetString() != "pipesim-system-analysis-result/1" ||
            result.GetProperty("model_kind").GetString() != "network" ||
            result.GetProperty("runTask").GetString() != "system-analysis" ||
            result.GetProperty("resultContract").GetString() != "VALID_FULL" ||
            !IsSafeTextValue(result.GetProperty("study"), false) ||
            !IsSafeTextValue(result.GetProperty("producer"), false) ||
            result.GetProperty("producer").GetString() != "Well" ||
            !IsSafeTextValue(result.GetProperty("branchTerminator"), false) ||
            !IsSafeTextValue(result.GetProperty("scanVariable"), false) ||
            result.GetProperty("scanVariable").GetString() != "liquidFlowRate" ||
            result.GetProperty("outletPressurePsi").ValueKind != JsonValueKind.Number ||
            !result.GetProperty("outletPressurePsi").TryGetDouble(out var outletPressure) ||
            !double.IsFinite(outletPressure) || outletPressure <= 0 || outletPressure > 100000)
        {
            return false;
        }

        var cases = result.GetProperty("cases");
        if (cases.ValueKind != JsonValueKind.Array || cases.GetArrayLength() is < 2 or > 8) return false;
        var previous = double.NegativeInfinity;
        foreach (var item in cases.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object ||
                !HasExactlyProperties(item, "caseName", "scanValue", "system", "node", "profile") ||
                !IsSafeTextValue(item.GetProperty("caseName"), false) ||
                item.GetProperty("scanValue").ValueKind != JsonValueKind.Number ||
                !item.GetProperty("scanValue").TryGetDouble(out var scanValue) ||
                !double.IsFinite(scanValue) || scanValue <= 0 || scanValue <= previous ||
                !IsSafeSystemSeries(item.GetProperty("system")) ||
                !IsSafeSystemNodes(item.GetProperty("node")) ||
                !IsSafeSystemProfile(item.GetProperty("profile")))
            {
                return false;
            }
            previous = scanValue;
        }
        return true;
    }

    private static bool HasValidGasLiftPerformanceResult(JsonElement result)
    {
        if (result.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(result, "schemaVersion", "model_kind", "runTask", "resultContract", "producer",
                "outletPressurePsi", "surfaceInjectionTemperatureF", "targetInjectionRateMmscfd",
                "reservoirPressurePsi", "gorScfPerStb", "waterCutPercent", "scanVariable", "scanUnit",
                "productionUnit", "cases") ||
            result.GetProperty("schemaVersion").GetString() != "pipesim-gas-lift-performance-result/1" ||
            result.GetProperty("model_kind").GetString() != "black_oil_liquid" ||
            result.GetProperty("runTask").GetString() != "gas-lift-performance" ||
            result.GetProperty("resultContract").GetString() != "VALID_FULL" ||
            !IsSafeTextValue(result.GetProperty("producer"), false) ||
            !FiniteInRange(result.GetProperty("outletPressurePsi"), 0, 100000) ||
            !FiniteInRange(result.GetProperty("surfaceInjectionTemperatureF"), -1000, 100000) ||
            !FiniteInRange(result.GetProperty("targetInjectionRateMmscfd"), 0, 100000) ||
            !FiniteInRange(result.GetProperty("reservoirPressurePsi"), 0, 100000) ||
            !FiniteInRange(result.GetProperty("gorScfPerStb"), 0, 1000000) ||
            !FiniteInRange(result.GetProperty("waterCutPercent"), 0, 100) ||
            result.GetProperty("scanVariable").GetString() != "gasLiftInjectionRate" ||
            result.GetProperty("scanUnit").GetString() != "mmscf/d" ||
            result.GetProperty("productionUnit").GetString() != "STB/d")
        {
            return false;
        }
        var cases = result.GetProperty("cases");
        if (cases.ValueKind != JsonValueKind.Array || cases.GetArrayLength() is < 2 or > 16) return false;
        var previous = double.NegativeInfinity;
        foreach (var item in cases.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object ||
                !HasExactlyProperties(item, "caseName", "injectionRateMmscfd", "liquidRateStbPerDay") ||
                !IsSafeTextValue(item.GetProperty("caseName"), false) ||
                !FiniteInRange(item.GetProperty("injectionRateMmscfd"), 0, 100000) ||
                !FiniteNumber(item.GetProperty("liquidRateStbPerDay")) ||
                item.GetProperty("injectionRateMmscfd").GetDouble() <= previous)
            {
                return false;
            }
            previous = item.GetProperty("injectionRateMmscfd").GetDouble();
        }
        return true;
    }

    private static bool HasValidGasLiftDiagnosticsResult(JsonElement result)
    {
        if (result.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(result, "schemaVersion", "model_kind", "runTask", "resultContract", "producer",
                "outletPressurePsi", "surfaceInjectionTemperatureF", "targetInjectionRateMmscfd",
                "reservoirPressurePsi", "gorScfPerStb", "waterCutPercent", "diagnosticType", "throttling",
                "usePhaseRatio", "injectionUnit", "liquidRateUnit", "cases") ||
            result.GetProperty("schemaVersion").GetString() != "pipesim-gas-lift-diagnostics-result/1" ||
            result.GetProperty("model_kind").GetString() != "black_oil_liquid" ||
            result.GetProperty("runTask").GetString() != "gas-lift-diagnostics" ||
            result.GetProperty("resultContract").GetString() != "VALID_FULL" ||
            !IsSafeTextValue(result.GetProperty("producer"), false) ||
            !FiniteInRange(result.GetProperty("outletPressurePsi"), 0, 100000) ||
            !FiniteInRange(result.GetProperty("surfaceInjectionTemperatureF"), -1000, 100000) ||
            !FiniteInRange(result.GetProperty("targetInjectionRateMmscfd"), 0, 100000) ||
            !FiniteInRange(result.GetProperty("reservoirPressurePsi"), 0, 100000) ||
            !FiniteInRange(result.GetProperty("gorScfPerStb"), 0, 1000000) ||
            !FiniteInRange(result.GetProperty("waterCutPercent"), 0, 100) ||
            result.GetProperty("diagnosticType").GetString() != "FIXEDINJECTION" ||
            result.GetProperty("throttling").GetString() != "ON" ||
            result.GetProperty("usePhaseRatio").ValueKind != JsonValueKind.True ||
            result.GetProperty("injectionUnit").GetString() != "mmscf/d" ||
            result.GetProperty("liquidRateUnit").GetString() != "STB/d")
        {
            return false;
        }

        var cases = result.GetProperty("cases");
        if (cases.ValueKind != JsonValueKind.Array || cases.GetArrayLength() is < 1 or > 32) return false;
        var previous = double.NegativeInfinity;
        var valveCount = -1;
        foreach (var item in cases.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object ||
                !HasExactlyProperties(item, "caseName", "injectionRateMmscfd", "liquidRateStbPerDay", "valves") ||
                !IsSafeTextValue(item.GetProperty("caseName"), false) ||
                !FiniteInRange(item.GetProperty("injectionRateMmscfd"), 0, 100000) ||
                !FiniteNumber(item.GetProperty("liquidRateStbPerDay")) ||
                item.GetProperty("injectionRateMmscfd").GetDouble() <= previous)
            {
                return false;
            }
            previous = item.GetProperty("injectionRateMmscfd").GetDouble();
            var valves = item.GetProperty("valves");
            if (valves.ValueKind != JsonValueKind.Array || valves.GetArrayLength() is < 1 or > 64) return false;
            valveCount = valveCount < 0 ? valves.GetArrayLength() : valveCount;
            if (valves.GetArrayLength() != valveCount || !valves.EnumerateArray().All(IsSafeGasLiftValve)) return false;
        }
        return true;
    }

    private static bool IsSafeGasLiftValve(JsonElement valve)
    {
        if (valve.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(valve, "valveName", "positionStatus", "status", "gasRateNoThrottlingMmscfd",
                "portDiameterIn", "domeTemperatureF", "closingPressurePsi", "openingPressurePsi", "ptroPsi",
                "dischargeCoefficient", "portToBellowArea", "operationMode", "portType") ||
            !IsSafeTextValue(valve.GetProperty("valveName"), false) ||
            !IsSafeTextValue(valve.GetProperty("positionStatus"), false) ||
            !IsSafeTextValue(valve.GetProperty("status")) ||
            !IsSafeTextValue(valve.GetProperty("operationMode")) ||
            !IsSafeTextValue(valve.GetProperty("portType"))) return false;

        return IsOptionalFiniteNumber(valve.GetProperty("gasRateNoThrottlingMmscfd")) &&
               IsOptionalFiniteNumber(valve.GetProperty("portDiameterIn")) &&
               IsOptionalFiniteNumber(valve.GetProperty("domeTemperatureF")) &&
               IsOptionalFiniteNumber(valve.GetProperty("closingPressurePsi")) &&
               IsOptionalFiniteNumber(valve.GetProperty("openingPressurePsi")) &&
               IsOptionalFiniteNumber(valve.GetProperty("ptroPsi")) &&
               IsOptionalFiniteNumber(valve.GetProperty("dischargeCoefficient")) &&
               IsOptionalFiniteNumber(valve.GetProperty("portToBellowArea"));
    }

    private static bool HasValidVfpTablesResult(JsonElement result)
    {
        if (result.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(result, "schemaVersion", "model_kind", "runTask", "resultContract", "producer",
                "reservoirSimulator", "tableNumber", "includeTemperature", "bottomHoleDatumDepth", "axes", "table",
                "temperatureTable", "vfpTableContent", "vfpTableWithTemperatureContent") ||
            result.GetProperty("schemaVersion").GetString() != "pipesim-vfp-tables-result/1" ||
            result.GetProperty("model_kind").GetString() != "black_oil_liquid" ||
            result.GetProperty("runTask").GetString() != "vfp-tables" ||
            result.GetProperty("resultContract").GetString() != "VALID_FULL" ||
            !IsSafeTextValue(result.GetProperty("producer"), false) ||
            result.GetProperty("reservoirSimulator").GetString() != "ECLIPSE" ||
            result.GetProperty("tableNumber").ValueKind != JsonValueKind.Number ||
            !result.GetProperty("tableNumber").TryGetInt32(out var tableNumber) || tableNumber <= 0 ||
            result.GetProperty("includeTemperature").ValueKind is not (JsonValueKind.True or JsonValueKind.False) ||
            !FiniteInRange(result.GetProperty("bottomHoleDatumDepth"), 0, 100000) ||
            !IsSafeVfpAxes(result.GetProperty("axes")) ||
            !IsSafeVfpTable(result.GetProperty("table"), "BHP", "psia", result.GetProperty("axes"), false) ||
            !IsSafeVfpTable(result.GetProperty("temperatureTable"), "TEMP", "F", result.GetProperty("axes"), result.GetProperty("includeTemperature").ValueKind == JsonValueKind.False) ||
            !IsSafeVfpContent(result.GetProperty("vfpTableContent")) ||
            !IsSafeVfpContent(result.GetProperty("vfpTableWithTemperatureContent"))) return false;
        return true;
    }

    private static bool HasValidEspCurvesResult(JsonElement result)
    {
        if (result.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(result, "schemaVersion", "model_kind", "runTask", "resultContract", "producer", "pump", "nodalPump") ||
            result.GetProperty("schemaVersion").GetString() != "pipesim-esp-curves-result/1" ||
            result.GetProperty("model_kind").GetString() is not ("black_oil_liquid" or "basic_gas") ||
            result.GetProperty("runTask").GetString() != "esp-curves" ||
            result.GetProperty("resultContract").GetString() != "VALID_FULL" ||
            !IsSafeTextValue(result.GetProperty("producer"), false) ||
            !IsSafeEspPump(result.GetProperty("pump")) ||
            !IsSafeEspPump(result.GetProperty("nodalPump"))) return false;
        return true;
    }

    private static bool HasValidTrajectoryResult(JsonElement result)
    {
        if (result.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(result, "schemaVersion", "model_kind", "runTask", "resultContract", "producer", "units", "points") ||
            result.GetProperty("schemaVersion").GetString() != "pipesim-well-trajectory-result/1" ||
            result.GetProperty("model_kind").GetString() is not ("black_oil_liquid" or "basic_gas" or "legacy_well") ||
            result.GetProperty("runTask").GetString() != "trajectory" ||
            result.GetProperty("resultContract").GetString() != "VALID_FULL" ||
            !IsSafeTextValue(result.GetProperty("producer"), false) ||
            !HasValidTrajectoryUnits(result.GetProperty("units"))) return false;
        var points = result.GetProperty("points");
        if (points.ValueKind != JsonValueKind.Array || points.GetArrayLength() is < 2 or > 4096) return false;
        var previousDepth = -1D;
        foreach (var point in points.EnumerateArray())
        {
            if (!IsSafeTrajectoryPoint(point) || point.GetProperty("measuredDepth").GetDouble() <= previousDepth) return false;
            previousDepth = point.GetProperty("measuredDepth").GetDouble();
        }
        return true;
    }

    private static bool HasValidTrajectoryUnits(JsonElement units) =>
        units.ValueKind == JsonValueKind.Object && HasExactlyProperties(units, "measuredDepth", "trueVerticalDepth", "inclination", "azimuth", "maxDogLegSeverity") &&
        IsSafeTextValue(units.GetProperty("measuredDepth"), false) &&
        IsSafeTextValue(units.GetProperty("trueVerticalDepth"), false) &&
        IsSafeTextValue(units.GetProperty("inclination"), false) &&
        IsSafeTextValue(units.GetProperty("azimuth"), false) &&
        IsSafeTextValue(units.GetProperty("maxDogLegSeverity"), false);

    private static bool IsSafeTrajectoryPoint(JsonElement point)
    {
        if (point.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(point, "measuredDepth", "trueVerticalDepth", "inclination", "azimuth", "maxDogLegSeverity") ||
            !FiniteInRange(point.GetProperty("measuredDepth"), 0, 1000000) ||
            !FiniteInRange(point.GetProperty("trueVerticalDepth"), 0, 1000000) ||
            !FiniteInRange(point.GetProperty("inclination"), 0, 180) ||
            !IsOptionalFiniteNumber(point.GetProperty("azimuth")) ||
            !IsOptionalFiniteNumber(point.GetProperty("maxDogLegSeverity"))) return false;
        return true;
    }

    private static bool IsSafeEspPump(JsonElement pump)
    {
        if (pump.ValueKind != JsonValueKind.Object || !HasExactlyProperties(pump, "pumpName", "inputs", "frequencies", "operatingEnvelope") ||
            !IsSafeTextValue(pump.GetProperty("pumpName"), false) || pump.GetProperty("pumpName").GetString() != "B-ESP") return false;
        var inputs = pump.GetProperty("inputs");
        if (inputs.ValueKind != JsonValueKind.Object || !HasExactlyProperties(inputs, "frequency", "frequencyUnit", "manufacturer", "model", "minFlowRate", "maxFlowRate", "stages") ||
            !FiniteInRange(inputs.GetProperty("frequency"), 0, 200) || !IsSafeTextValue(inputs.GetProperty("frequencyUnit"), true) ||
            !IsSafeTextValue(inputs.GetProperty("manufacturer"), false) || !IsSafeTextValue(inputs.GetProperty("model"), false) ||
            !FiniteInRange(inputs.GetProperty("minFlowRate"), 0, 1000000) || !FiniteInRange(inputs.GetProperty("maxFlowRate"), 0, 1000000) ||
            !FiniteInRange(inputs.GetProperty("stages"), 0, 100000) || inputs.GetProperty("maxFlowRate").GetDouble() <= inputs.GetProperty("minFlowRate").GetDouble()) return false;
        var frequencies = pump.GetProperty("frequencies");
        if (frequencies.ValueKind != JsonValueKind.Array || frequencies.GetArrayLength() is < 1 or > 64 ||
            !frequencies.EnumerateArray().All(IsSafeEspFrequency)) return false;
        var envelope = pump.GetProperty("operatingEnvelope");
        return envelope.ValueKind == JsonValueKind.Object && HasExactlyProperties(envelope, "qMin", "bep", "qMax") &&
               IsSafeEspCurve(envelope.GetProperty("qMin")) && IsSafeEspCurve(envelope.GetProperty("bep")) && IsSafeEspCurve(envelope.GetProperty("qMax"));
    }

    private static bool IsSafeEspFrequency(JsonElement frequency)
    {
        if (frequency.ValueKind != JsonValueKind.Object || !HasExactlyProperties(frequency, "frequencyHz", "frequencyLabel", "flowRate", "flowRateUnit", "head", "headUnit") ||
            !FiniteInRange(frequency.GetProperty("frequencyHz"), 0, 200) || !IsSafeTextValue(frequency.GetProperty("frequencyLabel"), false) ||
            !IsSafeTextValue(frequency.GetProperty("flowRateUnit"), true) || !IsSafeTextValue(frequency.GetProperty("headUnit"), true)) return false;
        return IsSafeMatchingEspArrays(frequency.GetProperty("flowRate"), frequency.GetProperty("head"));
    }

    private static bool IsSafeEspCurve(JsonElement curve) =>
        curve.ValueKind == JsonValueKind.Object && HasExactlyProperties(curve, "flowRate", "flowRateUnit", "head", "headUnit") &&
        IsSafeTextValue(curve.GetProperty("flowRateUnit"), true) && IsSafeTextValue(curve.GetProperty("headUnit"), true) &&
        IsSafeMatchingEspArrays(curve.GetProperty("flowRate"), curve.GetProperty("head"));

    private static bool IsSafeMatchingEspArrays(JsonElement flowRate, JsonElement head)
    {
        if (flowRate.ValueKind != JsonValueKind.Array || head.ValueKind != JsonValueKind.Array ||
            flowRate.GetArrayLength() < 1 || flowRate.GetArrayLength() > 512 || flowRate.GetArrayLength() != head.GetArrayLength()) return false;
        return flowRate.EnumerateArray().All(value => FiniteInRange(value, -1000000, 100000000)) &&
               head.EnumerateArray().All(value => FiniteInRange(value, -1000000, 100000000));
    }

    private static bool IsSafeVfpAxes(JsonElement axes)
    {
        if (axes.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(axes, "liquidRatesStbPerDay", "outletPressuresPsi", "waterCutFraction", "gorMscfPerStb", "artificialLiftInjectionDpPsi")) return false;
        return IsStrictFiniteArray(axes.GetProperty("liquidRatesStbPerDay"), 1, 16) &&
               IsStrictFiniteArray(axes.GetProperty("outletPressuresPsi"), 1, 16) &&
               IsStrictFiniteArray(axes.GetProperty("waterCutFraction"), 1, 16) &&
               IsStrictFiniteArray(axes.GetProperty("gorMscfPerStb"), 1, 16) &&
               IsStrictFiniteArray(axes.GetProperty("artificialLiftInjectionDpPsi"), 1, 16);
    }

    private static bool IsStrictFiniteArray(JsonElement values, int minLength, int maxLength)
    {
        if (values.ValueKind != JsonValueKind.Array || values.GetArrayLength() < minLength || values.GetArrayLength() > maxLength) return false;
        var previous = double.NegativeInfinity;
        foreach (var value in values.EnumerateArray())
        {
            if (!FiniteNumber(value) || value.GetDouble() <= previous) return false;
            previous = value.GetDouble();
        }
        return true;
    }

    private static bool IsSafeVfpTable(JsonElement table, string valueName, string unit, JsonElement axes, bool allowEmpty)
    {
        if (table.ValueKind != JsonValueKind.Object || !HasExactlyProperties(table, "valueName", "unit", "rows") ||
            table.GetProperty("valueName").GetString() != valueName || table.GetProperty("unit").GetString() != unit) return false;
        var rows = table.GetProperty("rows");
        if (rows.ValueKind != JsonValueKind.Array || rows.GetArrayLength() > 65536 || (!allowEmpty && rows.GetArrayLength() < 1)) return false;
        if (rows.GetArrayLength() == 0) return true;
        var valueCount = axes.GetProperty("outletPressuresPsi").GetArrayLength();
        foreach (var row in rows.EnumerateArray())
        {
            if (row.ValueKind != JsonValueKind.Object || !HasExactlyProperties(row, "liquidRateIndex", "waterCutIndex", "gorIndex", "artificialLiftIndex", "values") ||
                !PositiveIndex(row.GetProperty("liquidRateIndex")) ||
                !PositiveIndex(row.GetProperty("waterCutIndex")) || !PositiveIndex(row.GetProperty("gorIndex")) || !PositiveIndex(row.GetProperty("artificialLiftIndex"))) return false;
            var values = row.GetProperty("values");
            if (values.ValueKind != JsonValueKind.Array || values.GetArrayLength() != valueCount || !values.EnumerateArray().All(FiniteNumber)) return false;
        }
        return true;
    }

    private static bool PositiveIndex(JsonElement value) => value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var number) && number > 0;

    private static bool IsSafeVfpContent(JsonElement value) => value.ValueKind == JsonValueKind.String &&
        value.GetString()!.Length <= 500000 && !value.GetString()!.Contains('\0');

    private static bool IsOptionalFiniteNumber(JsonElement value) => value.ValueKind == JsonValueKind.Null || FiniteNumber(value);

    private static bool FiniteNumber(JsonElement value) => value.ValueKind == JsonValueKind.Number
        && value.TryGetDouble(out var number) && double.IsFinite(number);

    private static bool FiniteInRange(JsonElement value, double lowerInclusive, double upperInclusive) =>
        FiniteNumber(value) && value.GetDouble() >= lowerInclusive && value.GetDouble() <= upperInclusive;

    private static bool IsSafeSystemSeries(JsonElement values)
    {
        if (values.ValueKind != JsonValueKind.Array || values.GetArrayLength() == 0) return false;
        foreach (var item in values.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object ||
                !HasExactlyProperties(item, "variable", "unit", "value") ||
                !IsSafeTextValue(item.GetProperty("variable"), false) ||
                !IsSafeOptionalUnit(item.GetProperty("unit")) ||
                !HasSafeFiniteNumber(item.GetProperty("value"))) return false;
        }
        return true;
    }

    private static bool IsSafeSystemNodes(JsonElement nodes)
    {
        if (nodes.ValueKind != JsonValueKind.Array || nodes.GetArrayLength() == 0) return false;
        foreach (var node in nodes.EnumerateArray())
        {
            if (node.ValueKind != JsonValueKind.Object ||
                !HasExactlyProperties(node, "node", "variables") ||
                !IsSafeTextValue(node.GetProperty("node"), false) ||
                !IsSafeSystemSeries(node.GetProperty("variables"))) return false;
        }
        return true;
    }

    private static bool IsSafeSystemProfile(JsonElement profile)
    {
        if (profile.ValueKind != JsonValueKind.Object ||
            !HasExactlyProperties(profile, "pointCount", "variables") ||
            !profile.GetProperty("pointCount").TryGetInt32(out var pointCount) ||
            pointCount <= 0 || pointCount > 100000 ||
            profile.GetProperty("variables").ValueKind != JsonValueKind.Array ||
            profile.GetProperty("variables").GetArrayLength() == 0) return false;

        JsonElement? distance = null;
        JsonElement? pressure = null;
        foreach (var variable in profile.GetProperty("variables").EnumerateArray())
        {
            if (variable.ValueKind != JsonValueKind.Object ||
                !HasExactlyProperties(variable, "variable", "unit", "values") ||
                !IsSafeTextValue(variable.GetProperty("variable"), false) ||
                !IsSafeOptionalUnit(variable.GetProperty("unit")) ||
                variable.GetProperty("values").ValueKind != JsonValueKind.Array ||
                variable.GetProperty("values").GetArrayLength() != pointCount) return false;

            var name = variable.GetProperty("variable").GetString();
            foreach (var value in variable.GetProperty("values").EnumerateArray())
            {
                var safe = name == "BranchEquipment"
                    ? value.ValueKind == JsonValueKind.Null || IsSafeTextValue(value, true)
                    : HasSafeFiniteNumber(value);
                if (!safe) return false;
            }
            if (name == "TotalDistance") distance = variable.GetProperty("values");
            if (name == "Pressure") pressure = variable.GetProperty("values");
        }
        return distance is { } distanceValues && pressure is { } pressureValues
            && distanceValues.GetArrayLength() == pressureValues.GetArrayLength();
    }

    private static bool IsSafeOptionalUnit(JsonElement value) =>
        value.ValueKind == JsonValueKind.Null || IsSafeTextValue(value, true);

    private static bool HasSafeFiniteNumber(JsonElement value) =>
        value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var number) && double.IsFinite(number);

    private static bool IsSafeSensitivityCase(JsonElement item) =>
        item.ValueKind == JsonValueKind.Object && HasExactlyProperties(item, "value", "ipr", "vlp") &&
        item.TryGetProperty("value", out var value) && value.ValueKind == JsonValueKind.Number &&
        value.TryGetDouble(out var number) && double.IsFinite(number) && number > 0 &&
        IsSafeSensitivityCurve(item.GetProperty("ipr")) && IsSafeSensitivityCurve(item.GetProperty("vlp"));

    private static bool IsSafeSensitivityCurve(JsonElement curve) =>
        curve.ValueKind == JsonValueKind.Array && curve.GetArrayLength() > 0 &&
        curve.EnumerateArray().All(point => point.ValueKind == JsonValueKind.Object && HasExactlyProperties(point, "flow", "pressure") &&
            point.GetProperty("flow").ValueKind == JsonValueKind.Number && point.GetProperty("pressure").ValueKind == JsonValueKind.Number &&
            point.GetProperty("flow").TryGetDouble(out var flow) && double.IsFinite(flow) &&
            point.GetProperty("pressure").TryGetDouble(out var pressure) && double.IsFinite(pressure));

    private static bool IsSafeNetworkSummary(JsonElement summary) =>
        summary.ValueKind == JsonValueKind.Object &&
        HasExactlyProperties(summary, "info", "warnings", "errors") &&
        summary.TryGetProperty("info", out var info) && IsSafeTextArray(info) &&
        summary.TryGetProperty("warnings", out var warnings) && IsSafeTextArray(warnings) &&
        summary.TryGetProperty("errors", out var errors) && IsSafeTextArray(errors);

    private static bool IsSafeTextArray(JsonElement values) =>
        values.ValueKind == JsonValueKind.Array && values.EnumerateArray().All(value => IsSafeTextValue(value, true));

    private static bool IsSafeNetworkQuality(JsonElement quality)
    {
        if (quality.ValueKind != JsonValueKind.Array) return false;
        var paths = new HashSet<string>(StringComparer.Ordinal);
        foreach (var item in quality.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object || !HasExactlyProperties(item, "path", "code") ||
                !IsSafeTextProperty(item, "path", false) ||
                !item.TryGetProperty("code", out var code) || code.ValueKind != JsonValueKind.String ||
                code.GetString() is not ("NON_FINITE" or "UNAVAILABLE") ||
                !paths.Add(item.GetProperty("path").GetString()!))
            {
                return false;
            }
        }
        return true;
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
        IsSafeTextProperty(group, "variable", false) && IsSafeNetworkUnitProperty(group, "unit") &&
        group.TryGetProperty("values", out var values) && values.ValueKind == JsonValueKind.Array && values.GetArrayLength() > 0 &&
        values.EnumerateArray().All(item => item.ValueKind == JsonValueKind.Object && HasExactlyProperties(item, "name", "value") &&
            IsSafeTextProperty(item, "name", false) && item.TryGetProperty("value", out var value) && HasSafeNetworkScalarLeaves(value));

    private static bool IsSafeNetworkUnitProperty(JsonElement value, string property) =>
        value.TryGetProperty(property, out var unit) && IsSafeNetworkUnit(unit);

    private static bool IsSafeNetworkUnit(JsonElement value) =>
        value.ValueKind == JsonValueKind.String &&
        (value.GetString()?.Length ?? 0) <= 128 &&
        !HasControlCharacter(value.GetString()!);

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

    private static bool HasSafeNetworkScalarLeaves(JsonElement value) => HasSafeNetworkScalarLeaves(value, true);

    private static bool HasSafeNetworkScalarLeaves(JsonElement value, bool allowNativeScalar) => value.ValueKind switch
    {
        JsonValueKind.Null => true,
        JsonValueKind.Number => value.TryGetDouble(out var number) && double.IsFinite(number),
        JsonValueKind.True or JsonValueKind.False => allowNativeScalar,
        JsonValueKind.String => allowNativeScalar && IsSafeTextValue(value, false) && !double.TryParse(value.GetString(), out _),
        JsonValueKind.Array => value.GetArrayLength() > 0 && value.EnumerateArray().All(item => HasSafeNetworkScalarLeaves(item, allowNativeScalar)),
        JsonValueKind.Object => value.EnumerateObject().Any() && value.EnumerateObject().All(property => HasSafeNetworkScalarLeaves(property.Value, false)),
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
        "READING_TRAJECTORY" => "Reading the official PIPESIM well trajectory.",
        "COLLECTING" => "Collecting and normalizing PIPESIM result arrays.",
        _ => throw new InvalidOperationException("The PIPESIM adapter emitted an unknown phase state.")
    };

    [GeneratedRegex("^[0-9a-f]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex LowerSha256();
}
