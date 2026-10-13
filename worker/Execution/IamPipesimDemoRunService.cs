using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record IamPipesimDemoRunEvent(long Sequence, string State, DateTimeOffset OccurredAtUtc, string Message);
public sealed record IamPipesimDemoRunAccepted(string RunId, string State, DateTimeOffset AcceptedAtUtc);
public sealed record IamPipesimDemoRunListItem(string RunId, string State, DateTimeOffset AcceptedAtUtc,
    DateTimeOffset? CompletedAtUtc, long ElapsedMillis);
public sealed record IamPipesimDemoRunSnapshot(string RunId, string State, DateTimeOffset AcceptedAtUtc,
    DateTimeOffset? CompletedAtUtc, long ElapsedMillis, IReadOnlyList<IamPipesimDemoRunEvent> Events,
    JsonElement? Result, string? Error);
public sealed record IamPipesimDemoStartOutcome(int HttpStatus, object Body);
public sealed record IamPipesimDemoRunRequest(double OutletPressurePsia = 600);

public sealed class IamPipesimDemoRunService
{
    private const string Terminal = "SUCCEEDED";
    private const int RetainedRunLimit = 20;
    private const double MinimumOutletPressurePsia = 500;
    private const double MaximumOutletPressurePsia = 1000;
    private const double TemplateOutletPressurePsia = 600;
    private const string OutletEquipmentName = "OIL_EXPORT";
    private readonly WorkerOptions options;
    private readonly WorkerIdentity identity;
    private readonly PtkExecutionCoordinator coordinator;
    private readonly ConcurrentDictionary<string, RunEntry> runs = new(StringComparer.Ordinal);

    public IamPipesimDemoRunService(IOptions<WorkerOptions> options, WorkerIdentity identity,
        PtkExecutionCoordinator coordinator)
    {
        this.options = options.Value;
        this.identity = identity;
        this.coordinator = coordinator;
    }

    public object GetCapability()
    {
        var missing = new List<string>();
        CheckFile(options.EffectiveIamDemoSourceModelPath, "sourceModel", missing);
        CheckFile(options.EffectiveIamDemoEngineInputPath, "engineInput", missing);
        CheckFile(options.EffectiveIamDemoProbePath, "agentProbe", missing);
        CheckDirectory(options.EffectiveIamDemoInstallationPath, "iamRuntime", missing);
        CheckDirectory(options.EffectiveIamDemoPipesimProgramsPath, "pipesimPrograms", missing);
        var sourceSha = TryComputeSha256(options.EffectiveIamDemoSourceModelPath);
        var inputSha = TryComputeSha256(options.EffectiveIamDemoEngineInputPath);
        var busy = runs.Values.Any(run => !IsTerminal(run.State));
        return new
        {
            status = missing.Count == 0 ? "AVAILABLE" : "UNAVAILABLE",
            reasonCode = missing.Count == 0 ? null : "IAM_DEMO_CONFIGURATION_MISSING",
            message = missing.Count == 0 ? null : "IAM Agent Manager demo runtime is not configured on this Worker.",
            missing,
            iamVersion = "2022.1",
            pipesimVersion = "2022.1",
            model = new { name = "SIS2 network demo", sourceFile = "SIS2.pips", format = "PIPESIM network", sourceSha256 = sourceSha },
            engineInput = new { fileName = "SIS2.tnt", format = "PIPESIM engine keywords", sha256 = inputSha },
            execution = "IAM StingrayEngineAgentManager -> PIPESIM 2022.1 Engine",
            controls = new[]
            {
                new { equipment = OutletEquipmentName, parameter = "outletPressure", label = "出口压力", value = TemplateOutletPressurePsia,
                    unit = "psia", minimum = MinimumOutletPressurePsia, maximum = MaximumOutletPressurePsia }
            },
            idle = !busy && !coordinator.IsBusy,
            workerId = identity.WorkerId,
            generationId = identity.GenerationId
        };
    }

    public IamPipesimDemoStartOutcome Start(IamPipesimDemoRunRequest request)
    {
        if (!double.IsFinite(request.OutletPressurePsia) || request.OutletPressurePsia < MinimumOutletPressurePsia ||
            request.OutletPressurePsia > MaximumOutletPressurePsia)
            return new(StatusCodes.Status400BadRequest, WorkerApiError.Request("INVALID_DEMO_BOUNDARY", $"{OutletEquipmentName} outlet pressure must be between {MinimumOutletPressurePsia} and {MaximumOutletPressurePsia} psia."));

        var capability = GetCapability();
        var serializedCapability = JsonSerializer.SerializeToElement(capability);
        if (serializedCapability.GetProperty("status").GetString() != "AVAILABLE")
            return new(StatusCodes.Status503ServiceUnavailable, new { code = "IAM_DEMO_UNAVAILABLE", capability });

        var acquired = coordinator.TryAcquire("IAM PIPESIM demo");
        if (!acquired.Acquired)
            return new(StatusCodes.Status409Conflict, acquired.Error ?? WorkerApiError.Coordination("WORKER_BUSY", "Another PIPESIM calculation is running."));

        PruneOldRuns();
        var runId = Guid.NewGuid().ToString("N");
        var now = DateTimeOffset.UtcNow;
        var runRoot = Path.Combine(options.StorageRoot, "iam-pipesim-demo", "runs", runId);
        try
        {
            Directory.CreateDirectory(runRoot);
            var tntPath = Path.Combine(runRoot, "SIS2.tnt");
            File.Copy(options.EffectiveIamDemoEngineInputPath, tntPath, overwrite: false);
            ApplyOutletPressure(tntPath, request.OutletPressurePsia);
            var entry = new RunEntry(runId, now, runRoot, tntPath, request.OutletPressurePsia, acquired.Lease!);
            entry.AddEvent("QUEUED", "IAM–PIPESIM demo run was accepted by the Worker.");
            if (!runs.TryAdd(runId, entry)) throw new InvalidOperationException("Demo run ID collision.");
            _ = Task.Run(() => ExecuteAsync(entry));
            return new(StatusCodes.Status202Accepted, new IamPipesimDemoRunAccepted(runId, entry.State, now));
        }
        catch
        {
            acquired.Lease!.Dispose();
            try { if (Directory.Exists(runRoot)) Directory.Delete(runRoot, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
            throw;
        }
    }

    public IReadOnlyList<IamPipesimDemoRunListItem> List() => runs.Values
        .OrderByDescending(run => run.AcceptedAtUtc)
        .Take(20)
        .Select(run => run.ToListItem())
        .ToArray();

    public IamPipesimDemoRunSnapshot? Get(string runId) => runs.TryGetValue(runId, out var run) ? run.Snapshot() : null;

    public bool Cancel(string runId)
    {
        if (!runs.TryGetValue(runId, out var run) || IsTerminal(run.State)) return false;
        run.Cancel();
        return true;
    }

    private void PruneOldRuns()
    {
        if (runs.Count < RetainedRunLimit) return;
        var oldRuns = runs.Values.Where(run => IsTerminal(run.State))
            .OrderByDescending(run => run.AcceptedAtUtc)
            .Skip(RetainedRunLimit - 1)
            .ToArray();
        var runRoot = Path.GetFullPath(Path.Combine(options.StorageRoot, "iam-pipesim-demo", "runs"))
            .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        foreach (var oldRun in oldRuns)
        {
            if (!runs.TryRemove(oldRun.RunId, out _)) continue;
            var target = Path.GetFullPath(oldRun.RunRoot);
            if (!string.Equals(Path.GetDirectoryName(target), runRoot, StringComparison.OrdinalIgnoreCase) ||
                !target.StartsWith(runRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) continue;
            try { if (Directory.Exists(target)) Directory.Delete(target, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private async Task ExecuteAsync(RunEntry run)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(run.Cancellation.Token);
        linked.CancelAfter(TimeSpan.FromSeconds(options.IamDemoTimeoutSeconds));
        var evidencePath = Path.Combine(run.RunRoot, "iam-agent-manager-result.json");
        Process? process = null;
        try
        {
            run.Transition("PREPARING", $"Prepared an isolated SIS2 input with {OutletEquipmentName} outlet pressure {run.OutletPressurePsia.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture)} psia; the source template was left unchanged.");
            run.InputSha256 = await ComputeSha256Async(run.TntPath, linked.Token);
            run.SourceInputSha256 = await ComputeSha256Async(options.EffectiveIamDemoEngineInputPath, linked.Token);
            run.SourceModelSha256 = await ComputeSha256Async(options.EffectiveIamDemoSourceModelPath, linked.Token);
            run.Transition("IAM_AGENT_INITIALIZING", "Loading the isolated IAM Agent Manager and PIPESIM adapter.");

            var start = new ProcessStartInfo
            {
                FileName = options.EffectiveIamDemoProbePath,
                WorkingDirectory = options.EffectiveIamDemoPipesimProgramsPath,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            start.ArgumentList.Add(options.EffectiveIamDemoInstallationPath);
            start.ArgumentList.Add(options.EffectiveIamDemoPipesimProgramsPath);
            start.ArgumentList.Add(evidencePath);
            start.ArgumentList.Add(run.TntPath);
            start.Environment["GRDP_IAM_PROBE_ACTUAL_MANAGER"] = "1";
            start.Environment["GRDP_IAM_PROBE_SKIP_MUTEX"] = "1";
            start.Environment["PATH"] = options.EffectiveIamDemoPipesimProgramsPath + ";" +
                Path.Combine(options.EffectiveIamDemoInstallationPath, "Extensions", "Adapters", "Stingray2021CLR") + ";" +
                Environment.GetEnvironmentVariable("PATH");

            process = new Process { StartInfo = start, EnableRaisingEvents = true };
            if (!process.Start()) throw new InvalidOperationException("IAM agent probe process did not start.");
            var outputTask = ReadProgressAsync(process.StandardOutput, run);
            var errorTask = process.StandardError.ReadToEndAsync();
            try
            {
                await process.WaitForExitAsync(linked.Token);
            }
            catch (OperationCanceledException)
            {
                if (!process.HasExited)
                {
                    try { process.Kill(entireProcessTree: true); }
                    catch (InvalidOperationException) { }
                    catch (System.ComponentModel.Win32Exception) { }
                }
                await process.WaitForExitAsync(CancellationToken.None);
                await outputTask;
                _ = await errorTask;
                if (run.Cancellation.IsCancellationRequested)
                    run.Finish("CANCELLED", null, "The demo calculation was cancelled and the agent process exited.");
                else
                    run.Finish("TIMED_OUT", null, $"The IAM/PIPESIM calculation exceeded {options.IamDemoTimeoutSeconds} seconds.");
                return;
            }

            await outputTask;
            var stderr = await errorTask;
            if (!File.Exists(evidencePath))
                throw new InvalidDataException(string.IsNullOrWhiteSpace(stderr)
                    ? "The IAM Agent Manager probe did not write its result evidence."
                    : "The IAM Agent Manager probe failed before writing result evidence.");

            using var evidence = JsonDocument.Parse(await File.ReadAllTextAsync(evidencePath, linked.Token));
            var root = evidence.RootElement;
            var managerInitialized = ReadBool(root, "managerInitialized");
            var serviceCreated = ReadBool(root, "managerServiceCreated");
            var calculated = ReadBool(root, "managerCalculated");
            var cleaned = ReadBool(root, "cleaned");
            var exceptionCount = root.TryGetProperty("exceptionMessages", out var exceptions) && exceptions.ValueKind == JsonValueKind.Array
                ? exceptions.GetArrayLength() : 0;
            if (root.TryGetProperty("error", out var error)) throw new InvalidOperationException(ShortError(error.GetString()));
            if (process.ExitCode != 0 || !managerInitialized || !serviceCreated || !calculated || !cleaned || exceptionCount != 0)
                throw new InvalidOperationException($"IAM Agent Manager calculation did not pass acceptance checks (agent={managerInitialized}, service={serviceCreated}, calculate={calculated}, cleanup={cleaned}, adapterErrors={exceptionCount}).");

            if (!root.TryGetProperty("managerResults", out var results) || !ReadBool(results, "Success"))
                throw new InvalidOperationException("IAM Agent Manager returned no successful PIPESIM simulation result.");
            var normalizedResult = NormalizeResult(run, results, root);
            var resultPath = Path.Combine(run.RunRoot, "result.json");
            await File.WriteAllTextAsync(resultPath, normalizedResult.GetRawText(), linked.Token);
            run.Finish(Terminal, normalizedResult, "PIPESIM calculated the requested outlet-pressure case through the IAM Agent Manager; result and cleanup checks passed.");
        }
        catch (OperationCanceledException) when (run.Cancellation.IsCancellationRequested)
        {
            run.Finish("CANCELLED", null, "The demo calculation was cancelled.");
        }
        catch (OperationCanceledException)
        {
            run.Finish("TIMED_OUT", null, $"The IAM/PIPESIM calculation exceeded {options.IamDemoTimeoutSeconds} seconds.");
        }
        catch (Exception exception)
        {
            run.Finish("FAILED", null, exception.Message);
        }
        finally
        {
            process?.Dispose();
            run.Lease.Dispose();
        }
    }

    private static async Task ReadProgressAsync(StreamReader reader, RunEntry run)
    {
        while (await reader.ReadLineAsync() is { } line)
        {
            if (!line.StartsWith("@@IAM_PHASE:", StringComparison.Ordinal)) continue;
            var phase = line[12..].Trim();
            switch (phase)
            {
                case "SERVICE_CREATING": run.Transition("PIPESIM_SERVICE_CREATING", "IAM is creating the PIPESIM simulation service."); break;
                case "CALCULATING": run.Transition("CALCULATING", "PIPESIM 2022.1 is calculating through the IAM Agent interface."); break;
                case "COLLECTING": run.Transition("COLLECTING", "Reading the returned equipment and point results."); break;
            }
        }
    }

    private static JsonElement NormalizeResult(RunEntry run, JsonElement results, JsonElement evidence)
    {
        var variables = new Dictionary<int, (string Name, string? Measurement, string? Unit)>();
        var hasTypes = TryProperty(results, "VariableTypes", out var types) && types.ValueKind == JsonValueKind.Array;
        if (!hasTypes && TryProperty(evidence, "managerVariableTypes", out var managerTypes) && managerTypes.ValueKind == JsonValueKind.Array)
        {
            types = managerTypes;
            hasTypes = true;
        }
        if (hasTypes)
        {
            foreach (var variable in types.EnumerateArray())
            {
                if (!TryInt(variable, "id", out var id)) continue;
                variables[id] = (ReadString(variable, "name") ?? $"Variable {id}", ReadString(variable, "measurement"), ReadString(variable, "unit"));
            }
        }

        var values = new List<object>();
        var equipmentNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var pointCount = 0;
        if (TryProperty(results, "EquipmentResults", out var equipmentResults) && equipmentResults.ValueKind == JsonValueKind.Array)
        {
            foreach (var equipment in equipmentResults.EnumerateArray())
            {
                var equipmentName = ReadString(equipment, "equipment_name") ?? "Unknown equipment";
                equipmentNames.Add(equipmentName);
                if (TryProperty(equipment, "results", out var equipmentValues))
                    AddValues(equipmentValues, equipmentName, null, null, variables, values);
                if (!TryProperty(equipment, "point_results", out var points) || points.ValueKind != JsonValueKind.Array) continue;
                foreach (var point in points.EnumerateArray())
                {
                    pointCount++;
                    var locator = ReadString(point, "locator") ?? ReadNumberAsString(point, "locator");
                    var offset = ReadNumberAsString(point, "location_offset");
                    if (TryProperty(point, "results", out var pointValues))
                        AddValues(pointValues, equipmentName, locator, offset, variables, values);
                }
            }
        }

        return JsonSerializer.SerializeToElement(new
        {
            schemaVersion = "iam-pipesim-demo-result/1",
            success = true,
            iamManager = ReadString(evidence, "iamManagerLoadedType"),
            calculated = ReadBool(evidence, "managerCalculated"),
            sourceModel = "SIS2.pips",
            sourceModelSha256 = run.SourceModelSha256,
            engineInput = "SIS2.tnt",
            scenario = new
            {
                equipment = OutletEquipmentName,
                parameter = "outletPressure",
                value = run.OutletPressurePsia,
                unit = "psia",
                templateValue = TemplateOutletPressurePsia
            },
            sourceEngineInputSha256 = run.SourceInputSha256,
            engineInputSha256 = run.InputSha256,
            equipmentCount = equipmentNames.Count,
            pointResultCount = pointCount,
            valueCount = values.Count,
            completedAtUtc = DateTimeOffset.UtcNow,
            values
        });
    }

    private static void ApplyOutletPressure(string tntPath, double pressurePsia)
    {
        // Latin-1 keeps every source byte intact while replacing the ASCII PIPESIM keyword only.
        var encoding = System.Text.Encoding.Latin1;
        var source = encoding.GetString(File.ReadAllBytes(tntPath));
        const string number = @"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?";
        var pattern = new System.Text.RegularExpressions.Regex(
            $"(?m)^(?<prefix>\\s*sink\\s+name\\s*=\\s*'OIL_EXPORT'\\s+pres\\s+psia\\s*=\\s*){number}(?<suffix>\\s+uid\\s*=\\s*50\\s*)$",
            System.Text.RegularExpressions.RegexOptions.CultureInvariant | System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        var matches = pattern.Matches(source);
        if (matches.Count != 1)
            throw new InvalidDataException($"Expected exactly one {OutletEquipmentName} sink-pressure keyword in SIS2.tnt; found {matches.Count}.");

        var formatted = pressurePsia.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
        var updated = pattern.Replace(source, match => match.Groups["prefix"].Value + formatted + match.Groups["suffix"].Value, 1);
        File.WriteAllBytes(tntPath, encoding.GetBytes(updated));
    }

    private static void AddValues(JsonElement entries, string equipmentName, string? locator, string? offset,
        IReadOnlyDictionary<int, (string Name, string? Measurement, string? Unit)> variables, ICollection<object> values)
    {
        if (entries.ValueKind != JsonValueKind.Array) return;
        foreach (var value in entries.EnumerateArray())
        {
            if (!TryInt(value, "variable_type_id", out var id)) continue;
            variables.TryGetValue(id, out var variable);
            var result = TryProperty(value, "value", out var raw) ? raw.Clone() : default;
            if (result.ValueKind == JsonValueKind.Number &&
                (!result.TryGetDouble(out var numeric) || !double.IsFinite(numeric) || Math.Abs(numeric) >= 1e30)) continue;
            object? displayedValue = result.ValueKind == JsonValueKind.Undefined ? null : result;
            values.Add(new
            {
                equipmentName,
                locator,
                locationOffset = offset,
                variableTypeId = id,
                variableName = string.IsNullOrWhiteSpace(variable.Name) ? $"Variable {id}" : variable.Name,
                measurement = variable.Measurement,
                unit = variable.Unit,
                value = displayedValue
            });
        }
    }

    private static bool ReadBool(JsonElement node, string property) => TryProperty(node, property, out var value) && value.ValueKind == JsonValueKind.True;
    private static bool TryInt(JsonElement node, string property, out int result)
    {
        result = 0;
        return TryProperty(node, property, out var value) && value.TryGetInt32(out result);
    }
    private static string? ReadString(JsonElement node, string property) =>
        TryProperty(node, property, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    private static string? ReadNumberAsString(JsonElement node, string property) =>
        TryProperty(node, property, out var value) && value.ValueKind == JsonValueKind.Number ? value.GetRawText() : null;
    private static bool TryProperty(JsonElement node, string name, out JsonElement value) =>
        node.TryGetProperty(name, out value) || node.TryGetProperty(char.ToUpperInvariant(name[0]) + name[1..], out value);
    private static string ShortError(string? value) => string.IsNullOrWhiteSpace(value) ? "IAM Agent Manager reported an unspecified error." : value.Length > 500 ? value[..500] : value;

    private static void CheckFile(string path, string label, ICollection<string> missing)
    {
        if (!Path.IsPathFullyQualified(path) || !File.Exists(path)) missing.Add(label);
    }
    private static void CheckDirectory(string path, string label, ICollection<string> missing)
    {
        if (!Path.IsPathFullyQualified(path) || !Directory.Exists(path)) missing.Add(label);
    }
    private static string? TryComputeSha256(string path)
    {
        try { return File.Exists(path) ? Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path))).ToLowerInvariant() : null; }
        catch (IOException) { return null; }
        catch (UnauthorizedAccessException) { return null; }
    }
    private static async Task<string> ComputeSha256Async(string path, CancellationToken cancellationToken)
    {
        await using var stream = File.OpenRead(path);
        return Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken)).ToLowerInvariant();
    }
    private static bool IsTerminal(string state) => state is "SUCCEEDED" or "FAILED" or "CANCELLED" or "TIMED_OUT";

    private sealed class RunEntry
    {
        private readonly object gate = new();
        private readonly List<IamPipesimDemoRunEvent> events = [];
        private JsonElement? result;
        private string? error;
        private DateTimeOffset? completedAt;
        private long sequence;

        public RunEntry(string runId, DateTimeOffset acceptedAtUtc, string runRoot, string tntPath,
            double outletPressurePsia, PtkExecutionCoordinator.CoordinatorLease lease)
        {
            RunId = runId;
            AcceptedAtUtc = acceptedAtUtc;
            RunRoot = runRoot;
            TntPath = tntPath;
            OutletPressurePsia = outletPressurePsia;
            Lease = lease;
            Cancellation = new CancellationTokenSource();
            State = "QUEUED";
        }
        public string RunId { get; }
        public DateTimeOffset AcceptedAtUtc { get; }
        public string RunRoot { get; }
        public string TntPath { get; }
        public double OutletPressurePsia { get; }
        public PtkExecutionCoordinator.CoordinatorLease Lease { get; }
        public CancellationTokenSource Cancellation { get; }
        public string State { get; private set; }
        public string? InputSha256 { get; set; }
        public string? SourceInputSha256 { get; set; }
        public string? SourceModelSha256 { get; set; }

        public void Transition(string state, string message)
        {
            lock (gate)
            {
                if (IsTerminal(State)) return;
                State = state;
                AddEventLocked(state, message);
            }
        }
        public void Finish(string state, JsonElement? finalResult, string? finalError)
        {
            lock (gate)
            {
                if (IsTerminal(State)) return;
                State = state;
                result = finalResult?.Clone();
                error = state == Terminal ? null : finalError;
                completedAt = DateTimeOffset.UtcNow;
                AddEventLocked(state, state == Terminal
                    ? $"PIPESIM completed the {OutletEquipmentName} {OutletPressurePsia.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture)} psia case through IAM Agent Manager; result and cleanup checks passed."
                    : finalError ?? "Run completed.");
            }
        }
        public void Cancel() => Cancellation.Cancel();
        public IamPipesimDemoRunSnapshot Snapshot()
        {
            lock (gate)
                return new(RunId, State, AcceptedAtUtc, completedAt, ElapsedMillisLocked(), events.ToArray(), result?.Clone(), error);
        }
        public IamPipesimDemoRunListItem ToListItem()
        {
            lock (gate) return new(RunId, State, AcceptedAtUtc, completedAt, ElapsedMillisLocked());
        }
        private long ElapsedMillisLocked() => Math.Max(0, (long)(((completedAt ?? DateTimeOffset.UtcNow) - AcceptedAtUtc).TotalMilliseconds));
        public void AddEvent(string state, string message)
        {
            lock (gate) { State = state; AddEventLocked(state, message); }
        }
        private void AddEventLocked(string state, string message) => events.Add(new(++sequence, state, DateTimeOffset.UtcNow, message));
    }
}
