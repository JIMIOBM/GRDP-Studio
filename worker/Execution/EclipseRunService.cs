using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed partial class EclipseRunService : IDisposable
{
    private readonly StorageResolver storage; private readonly ArtifactStore artifacts; private readonly EclipseExecutionCoordinator coordinator;
    private readonly PtkRunRegistry registry; private readonly EclipseLauncher launcher; private readonly WorkerOptions options; private readonly SemaphoreSlim submissions = new(1, 1);
    public EclipseRunService(StorageResolver storage, EclipseExecutionCoordinator coordinator, PtkRunRegistry registry, EclipseLauncher launcher, IOptions<WorkerOptions> options)
        => (this.storage, artifacts, this.coordinator, this.registry, this.launcher, this.options) = (storage, new ArtifactStore(storage), coordinator, registry, launcher, options.Value);

    public async Task<ApiOutcome> SubmitAsync(RunExecuteRequest request, CancellationToken cancellationToken)
    {
        var error = Validate(request); if (error is not null) return new(400, error);
        await submissions.WaitAsync(cancellationToken);
        try
        {
            var fingerprint = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("\n", request.RunId, request.ModelStorageKey, request.ExpectedModelSha256, "eclipse", request.TimeoutSeconds))));
            if (registry.ExistsWithFingerprint(request.RunId, fingerprint, out var existing, out var conflict)) return conflict ? new(409, WorkerApiError.Request("RUN_ID_CONFLICT", "runId is already associated with a different request.")) : new(202, existing!);
            string source;
            try { source = storage.ResolveExistingData(request.ModelStorageKey); if (await storage.ComputeSha256Async(source, cancellationToken) != request.ExpectedModelSha256) return new(422, WorkerApiError.Storage("MODEL_SHA256_MISMATCH", "The source model SHA-256 does not match expectedModelSha256.")); storage.EnsureRunDirectoryAvailable(request.RunId); }
            catch (StorageException exception) { return new(exception.HttpStatus, WorkerApiError.Storage(exception.Code, exception.Message)); }
            var capability = await launcher.GetCapabilityAsync(cancellationToken);
            if (!capability.Available) return new(503, new WorkerError("ENVIRONMENT", "ECLIPSE_UNAVAILABLE", "ECLIPSE launcher is unavailable.", true));
            var lease = coordinator.TryAcquire("run"); if (!lease.Acquired) return new(409, lease.Error!);
            var claim = registry.TryClaim(request, fingerprint);
            if (claim.Status != ClaimStatus.Created) { lease.Lease!.Dispose(); return new(409, claim.Error!); }
            _ = Task.Run(() => ExecuteAsync(request, source, lease.Lease!), CancellationToken.None);
            return new(202, claim.Accepted!);
        }
        finally { submissions.Release(); }
    }

    private async Task ExecuteAsync(RunExecuteRequest request, string source, PtkExecutionCoordinator.CoordinatorLease lease)
    {
        RunDirectories? dirs = null; var descriptors = new List<ArtifactDescriptor>(); JsonElement? result = null; WorkerError? error = null; var state = "FAILED"; var message = "ECLIPSE run failed."; var treeConfirmed = true; var killUsed = false; var cleanupFailed = false;
        var cancellation = registry.GetCancellationToken(request.RunId);
        try
        {
            registry.Transition(request.RunId, "PREPARING", "Preparing isolated ECLIPSE deck.");
            using (var sourceReader = File.OpenText(source)) if (EclipseDeckScanner.ContainsInclude(sourceReader)) { error = new("MODEL", "ECLIPSE_INCLUDE_UNSUPPORTED", "INCLUDE is unsupported for ECLIPSE 100 MVP.", false); return; }
            dirs = storage.CreateRunDirectories(request.RunId, source);
            if (await storage.ComputeSha256Async(dirs.ModelCopy, CancellationToken.None) != request.ExpectedModelSha256) throw new StorageException("MODEL_COPY_SHA256_MISMATCH", "The task model copy failed SHA-256 verification.");
            using (var reader = File.OpenText(dirs.ModelCopy)) if (EclipseDeckScanner.ContainsInclude(reader)) { error = new("MODEL", "ECLIPSE_INCLUDE_UNSUPPORTED", "INCLUDE is unsupported for ECLIPSE 100 MVP.", false); return; }
            var deck = Path.GetFileName(dirs.ModelCopy); File.Copy(dirs.ModelCopy, Path.Combine(dirs.Work, deck));
            var before = Snapshot(dirs.Work);
            registry.Transition(request.RunId, "RUNNING_ECLIPSE", "Running ECLIPSE 100.");
            var launch = await launcher.RunAsync(deck, dirs.Work, TimeSpan.FromSeconds(request.TimeoutSeconds), cancellation);
            treeConfirmed = launch.ProcessTreeExitConfirmed; killUsed |= launch.KillUsed;
            var cleanup = await launcher.CleanupAsync(deck, dirs.Work, launch.StopReason == AdapterStopReason.Cancelled, CancellationToken.None);
            treeConfirmed &= cleanup.ProcessTreeExitConfirmed; killUsed |= cleanup.KillUsed;
            if (!treeConfirmed) { cleanupFailed = true; error = new("CLEANUP", "PROCESS_TREE_EXIT_UNCONFIRMED", "ECLIPSE process-tree exit could not be confirmed.", false); return; }
            if (cleanup.Error is not null || cleanup.ExitCode != 0) { cleanupFailed = true; error = new("CLEANUP", "ECLIPSE_CLEANUP_FAILED", "ECLIPSE cleanup could not be confirmed.", false); return; }
            if (launch.StopReason == AdapterStopReason.Cancelled) { state = "CANCELLED"; error = launch.Error; message = "Run cancellation completed after ECLIPSE cleanup."; return; }
            if (launch.StopReason == AdapterStopReason.TimedOut) { state = "TIMED_OUT"; error = launch.Error; message = "Run timed out after ECLIPSE cleanup."; return; }
            var fresh = Fresh(dirs.Work, before).ToArray();
            var eclEnd = fresh.FirstOrDefault(path => path.EndsWith(".ECLEND", StringComparison.OrdinalIgnoreCase));
            var diagnostics = launch.Diagnostics + "\n" + string.Join("\n", fresh.Where(path => path.EndsWith(".MSG", StringComparison.OrdinalIgnoreCase) || path.EndsWith(".ECLEND", StringComparison.OrdinalIgnoreCase)).Select(File.ReadAllText));
            if (EclipseRunRules.HasLicenseDiagnostic(diagnostics)) { error = new("LICENSE", "LICENSE_UNAVAILABLE", "ECLIPSE reported a license diagnostic.", true); return; }
            if (launch.ExitCode != 0 || eclEnd is null || !EclipseParsers.TryParseEclEnd(await File.ReadAllTextAsync(eclEnd), out var counts)) { error = new("EXECUTION", "ECLIPSE_RUN_FAILED", "ECLIPSE did not produce a valid fresh ECLEND result.", false); return; }
            if (counts!.Errors != 0 || counts.Problems != 0 || counts.Bugs != 0 || EclipseRunRules.HasFatalDiagnostic(diagnostics)) { error = new("SOLVER", "ECLIPSE_SOLVER_FAILED", "ECLIPSE reported solver errors.", false); return; }
            if (await storage.ComputeSha256Async(source, CancellationToken.None) != request.ExpectedModelSha256)
                throw new StorageException("MODEL_SOURCE_CHANGED", "The source model changed before ECLIPSE result publication.");
            registry.Transition(request.RunId, "COLLECTING", "Collecting fresh ECLIPSE output.");
            var rsm = fresh.FirstOrDefault(path => path.EndsWith(".RSM", StringComparison.OrdinalIgnoreCase));
            var rsmSeries = await ReadRsmAsync(rsm);
            object? summary = CreateSummary(rsmSeries);
            var outputFiles = await artifacts.DescribeEclipseOutputsAsync(fresh, CancellationToken.None);
            result = JsonSerializer.SerializeToElement(CreateResultEnvelope(deck, counts, summary, outputFiles));
            descriptors.Add(await artifacts.WriteJsonElementAsync(dirs.Output, "normalized-result.json", result.Value));
            state = "SUCCEEDED"; message = "ECLIPSE 100 completed successfully.";
        }
        catch (StorageException exception) { error = new("STORAGE", exception.Code, exception.Message, false); }
        catch (Exception) { error = new("EXECUTION", "ECLIPSE_RUN_FAILED", "The Worker could not complete the ECLIPSE run.", false); }
        finally
        {
            if (dirs is not null) { descriptors.Add(await artifacts.WriteLogAsync(dirs.Output, [state, error?.Code ?? "SUCCESS"])); descriptors.Add(await artifacts.WriteManifestAsync(dirs.Output, request.RunId, descriptors)); }
            var cleanup = CreateCleanup(
                treeConfirmed,
                dirs is null || StorageResolver.TryDeleteDirectory(dirs.Input),
                dirs is null || StorageResolver.TryDeleteDirectory(dirs.Work),
                killUsed,
                cleanupFailed);
            PtkRunService.PublishCompletionAndRelease(registry, request.RunId, state, result, error, descriptors, cleanup, message, lease, cleanupFailed);
        }
    }

    private WorkerError? Validate(RunExecuteRequest request) => request.RunId <= 0 ? WorkerApiError.Request("INVALID_RUN_ID", "runId must be positive.") : !EclipseRunRules.IsDataStorageKey(request.ModelStorageKey) ? WorkerApiError.Request("INVALID_ECLIPSE_DATA_FILE", "modelStorageKey must reference a .DATA file.") : string.IsNullOrWhiteSpace(request.ExpectedModelSha256) || request.ExpectedModelSha256.Length != 64 || request.ExpectedModelSha256.Any(value => value is not (>= '0' and <= '9' or >= 'a' and <= 'f')) ? WorkerApiError.Request("INVALID_EXPECTED_SHA256", "expectedModelSha256 must be lowercase SHA-256.") : request.RunTask != "eclipse" ? WorkerApiError.Request("INVALID_RUN_TASK", "runTask must be eclipse.") : request.Study is not null ? WorkerApiError.Request("INVALID_STUDY", "study must be null for ECLIPSE.") : request.Parameters.ValueKind != JsonValueKind.Null ? WorkerApiError.Request("PARAMETERS_NOT_NULL", "parameters must be explicitly null.") : request.TimeoutSeconds is <= 0 || request.TimeoutSeconds > options.EclipseMaxRunTimeoutSeconds ? WorkerApiError.Request("INVALID_TIMEOUT", "timeoutSeconds exceeds the ECLIPSE limit.") : null;
    private static Dictionary<string, (long Size, DateTime LastWrite)> Snapshot(string root) => Directory.EnumerateFiles(root).ToDictionary(path => path, path => (new FileInfo(path).Length, File.GetLastWriteTimeUtc(path)), StringComparer.OrdinalIgnoreCase);
    private static IEnumerable<string> Fresh(string root, Dictionary<string, (long Size, DateTime LastWrite)> before) => Directory.EnumerateFiles(root).Where(path => Output().IsMatch(Path.GetFileName(path)) && EclipseRunRules.IsFresh(before.TryGetValue(path, out var prior) ? prior : null, new FileInfo(path).Length, File.GetLastWriteTimeUtc(path)));
    internal static async Task<IReadOnlyList<EclipseSummarySeries>?> ReadRsmAsync(string? path, Func<string, IReadOnlyList<EclipseSummarySeries>>? parser = null)
    {
        if (path is null) return null;
        try { return (parser ?? EclipseParsers.ParseRsm)(await File.ReadAllTextAsync(path)); }
        catch { return null; }
    }
    internal static object? CreateSummary(IReadOnlyList<EclipseSummarySeries>? series) =>
        EclipseParsers.IsPublishableSummary(series) ? new { series } : null;
    internal static object CreateResultEnvelope(string deck, EclipseEndCounts counts, object? summary, IEnumerable<EclipseOutputMetadata> outputFiles) =>
        new { schemaVersion = "eclipse-summary-result/1", modelKind = "eclipse_100", runTask = "eclipse", resultContract = "VALID_FULL", caseName = deck, eclEnd = counts, summary, outputFiles = outputFiles.Select(file => new { name = file.Filename, sizeBytes = file.SizeBytes, sha256 = file.Sha256 }) };
    internal static RunCleanup CreateCleanup(bool treeConfirmed, bool inputDeleted, bool workDirectoryDeleted, bool killUsed, bool cleanupFailed) =>
        new(treeConfirmed, inputDeleted, workDirectoryDeleted, killUsed,
            !treeConfirmed ? "ECLIPSE process-tree exit is unconfirmed."
            : cleanupFailed ? "ECLIPSE cleanup failed or could not be confirmed."
            : "ECLIPSE cleanup completed.");
    [GeneratedRegex("^.+\\.(ECLEND|MSG|PRT|RSM|SMSPEC|UNSMRY|EGRID|INIT|S\\d{4,5})$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)] private static partial Regex Output();
    public void Dispose() => submissions.Dispose();
}
