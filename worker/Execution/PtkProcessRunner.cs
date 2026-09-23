using System.Diagnostics;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public enum AdapterStopReason { None, Cancelled, TimedOut, ProtocolFailure }

public sealed record AdapterExecutionResult(
    AdapterStopReason StopReason,
    int? ExitCode,
    JsonElement? Envelope,
    bool ProcessTreeExitConfirmed,
    bool GracefulStopRequested,
    bool KillUsed,
    WorkerError? Error);

internal sealed class ProcessStartupException : Exception
{
    public ProcessStartupException(bool cleanupConfirmed, Exception innerException)
        : base("Python process startup failed.", innerException) => CleanupConfirmed = cleanupConfirmed;

    public bool CleanupConfirmed { get; }
}

public sealed class PtkProcessRunner
{
    private readonly WorkerOptions options;

    public PtkProcessRunner(IOptions<WorkerOptions> options) => this.options = options.Value;

    public async Task<AdapterExecutionResult> RunAsync(
        string requestPath,
        TimeSpan timeout,
        CancellationToken cancellationToken,
        Action<string, string> onEvent)
    {
        var script = Path.Combine(AppContext.BaseDirectory, "ptk_run.py");
        return await RunJsonLinesProcessAsync(script, [requestPath], timeout, cancellationToken, onEvent);
    }

    public async Task<AdapterExecutionResult> ValidateAsync(
        string modelPath,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        var script = Path.Combine(AppContext.BaseDirectory, "ptk_validate.py");
        return await RunSingleJsonProcessAsync(script, [modelPath], timeout, cancellationToken);
    }

    private async Task<AdapterExecutionResult> RunJsonLinesProcessAsync(
        string script,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        CancellationToken cancellationToken,
        Action<string, string> onEvent)
    {
        Process? process = null;
        WindowsJobObject? processTree = null;
        try
        {
            (process, processTree) = StartProcess(script, arguments);
            var protocol = ReadProtocolAsync(process, onEvent);
            var stderr = DrainStandardErrorAsync(process);
            var exit = process.WaitForExitAsync();
            var cancelSignal = Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            var timeoutSignal = Task.Delay(timeout < TimeSpan.Zero ? TimeSpan.Zero : timeout);
            var completed = await Task.WhenAny(exit, protocol, cancelSignal, timeoutSignal);

            if (completed == cancelSignal)
            {
                var cleanup = await StopAsync(process, processTree, exit);
                await IgnoreFailureAsync(protocol);
                await stderr;
                return new(AdapterStopReason.Cancelled, TryGetExitCode(process), null, cleanup.Confirmed, true, cleanup.KillUsed,
                    new WorkerError("CANCELLATION", "RUN_CANCELLED", "Run cancellation was requested.", false));
            }

            if (completed == timeoutSignal)
            {
                var cleanup = await StopAsync(process, processTree, exit);
                await IgnoreFailureAsync(protocol);
                await stderr;
                return new(AdapterStopReason.TimedOut, TryGetExitCode(process), null, cleanup.Confirmed, true, cleanup.KillUsed,
                    new WorkerError("TIMEOUT", "RUN_TIMEOUT", "The configured run timeout elapsed.", false));
            }

            if (completed == protocol && protocol.IsFaulted)
            {
                var cleanup = await StopAsync(process, processTree, exit);
                await stderr;
                return new(AdapterStopReason.ProtocolFailure, TryGetExitCode(process), null, cleanup.Confirmed, true, cleanup.KillUsed,
                    new WorkerError("PROTOCOL", "INVALID_ADAPTER_PROTOCOL", "The PIPESIM adapter returned an invalid structured protocol.", false));
            }

            if (completed == protocol)
            {
                completed = await Task.WhenAny(exit, cancelSignal, timeoutSignal);
                if (completed == cancelSignal)
                {
                    var cleanup = await StopAsync(process, processTree, exit);
                    await IgnoreFailureAsync(protocol);
                    await stderr;
                    return new(AdapterStopReason.Cancelled, TryGetExitCode(process), null, cleanup.Confirmed, true, cleanup.KillUsed,
                        new WorkerError("CANCELLATION", "RUN_CANCELLED", "Run cancellation was requested.", false));
                }
                if (completed == timeoutSignal)
                {
                    var cleanup = await StopAsync(process, processTree, exit);
                    await IgnoreFailureAsync(protocol);
                    await stderr;
                    return new(AdapterStopReason.TimedOut, TryGetExitCode(process), null, cleanup.Confirmed, true, cleanup.KillUsed,
                        new WorkerError("TIMEOUT", "RUN_TIMEOUT", "The configured run timeout elapsed.", false));
                }
            }

            await exit;
            var normalCleanup = await processTree.ConfirmNormalExitAsync(process, TimeSpan.FromSeconds(options.ProcessExitConfirmationSeconds));
            if (!normalCleanup.Confirmed)
            {
                return new(AdapterStopReason.ProtocolFailure, TryGetExitCode(process), null, false, false, normalCleanup.TerminateUsed,
                    new WorkerError("CLEANUP", "PROCESS_TREE_EXIT_UNCONFIRMED", "Python process-tree exit could not be confirmed.", false));
            }

            JsonElement? envelope;
            try
            {
                envelope = await protocol;
            }
            catch (Exception exception) when (exception is JsonException or InvalidOperationException)
            {
                await stderr;
                return new(AdapterStopReason.ProtocolFailure, TryGetExitCode(process), null, true, false, normalCleanup.TerminateUsed,
                    new WorkerError("PROTOCOL", "INVALID_ADAPTER_PROTOCOL", "The PIPESIM adapter returned an invalid structured protocol.", false));
            }
            await stderr;

            if (process.ExitCode != 0 || envelope is null)
            {
                return new(AdapterStopReason.ProtocolFailure, process.ExitCode, envelope, true, false, normalCleanup.TerminateUsed,
                    new WorkerError("PROTOCOL", "ADAPTER_EXITED", "The PIPESIM adapter exited without a complete result envelope.", false));
            }

            return new(AdapterStopReason.None, process.ExitCode, envelope, true, false, normalCleanup.TerminateUsed, null);
        }
        catch (ProcessStartupException exception)
        {
            return new(AdapterStopReason.ProtocolFailure, null, null, exception.CleanupConfirmed, false, true,
                new WorkerError("ENVIRONMENT", "PTK_UNAVAILABLE", "Python or the PIPESIM Toolkit adapter could not be started.", true));
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or InvalidOperationException or System.ComponentModel.Win32Exception or PlatformNotSupportedException)
        {
            var confirmed = true;
            var killUsed = false;
            if (process is not null && processTree is not null)
            {
                var cleanup = await StopAsync(process, processTree, process.WaitForExitAsync());
                confirmed = cleanup.Confirmed;
                killUsed = cleanup.KillUsed;
            }

            return new(AdapterStopReason.ProtocolFailure, process is null ? null : TryGetExitCode(process), null, confirmed, false, killUsed,
                new WorkerError("ENVIRONMENT", "PTK_UNAVAILABLE", "Python or the PIPESIM Toolkit adapter could not be started.", true));
        }
        finally
        {
            processTree?.Dispose();
            process?.Dispose();
        }
    }

    private async Task<AdapterExecutionResult> RunSingleJsonProcessAsync(
        string script,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        Process? process = null;
        WindowsJobObject? processTree = null;
        try
        {
            (process, processTree) = StartProcess(script, arguments);
            var stdout = process.StandardOutput.ReadToEndAsync();
            var stderr = DrainStandardErrorAsync(process);
            var exit = process.WaitForExitAsync();
            var cancelSignal = Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            var timeoutSignal = Task.Delay(timeout < TimeSpan.Zero ? TimeSpan.Zero : timeout);
            var completed = await Task.WhenAny(exit, cancelSignal, timeoutSignal);
            if (completed != exit)
            {
                var cleanup = await StopAsync(process, processTree, exit);
                await IgnoreFailureAsync(stdout);
                await stderr;
                var timedOut = completed == timeoutSignal;
                return new(timedOut ? AdapterStopReason.TimedOut : AdapterStopReason.Cancelled, TryGetExitCode(process), null,
                    cleanup.Confirmed, true, cleanup.KillUsed,
                    timedOut
                        ? new WorkerError("TIMEOUT", "VALIDATION_TIMEOUT", "Model validation timed out.", true)
                        : new WorkerError("CANCELLATION", "VALIDATION_CANCELLED", "Model validation was cancelled.", false));
            }

            await exit;
            var normalCleanup = await processTree.ConfirmNormalExitAsync(process, TimeSpan.FromSeconds(options.ProcessExitConfirmationSeconds));
            if (!normalCleanup.Confirmed)
            {
                return new(AdapterStopReason.ProtocolFailure, process.ExitCode, null, false, false, normalCleanup.TerminateUsed,
                    new WorkerError("CLEANUP", "PROCESS_TREE_EXIT_UNCONFIRMED", "Python process-tree exit could not be confirmed.", false));
            }

            var output = await stdout;
            await stderr;
            var line = output.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).LastOrDefault();
            if (process.ExitCode != 0 || string.IsNullOrWhiteSpace(line))
            {
                return new(AdapterStopReason.ProtocolFailure, process.ExitCode, null, normalCleanup.Confirmed, false, normalCleanup.TerminateUsed,
                    new WorkerError("PROTOCOL", "INVALID_VALIDATION_PROTOCOL", "The validation adapter returned no valid response.", false));
            }

            try
            {
                using var document = JsonDocument.Parse(line);
                return new(AdapterStopReason.None, process.ExitCode, document.RootElement.Clone(), true, false, normalCleanup.TerminateUsed, null);
            }
            catch (JsonException)
            {
                return new(AdapterStopReason.ProtocolFailure, process.ExitCode, null, true, false, normalCleanup.TerminateUsed,
                    new WorkerError("PROTOCOL", "INVALID_VALIDATION_PROTOCOL", "The validation adapter returned invalid JSON.", false));
            }
        }
        catch (ProcessStartupException exception)
        {
            return new(AdapterStopReason.ProtocolFailure, null, null, exception.CleanupConfirmed, false, true,
                new WorkerError("ENVIRONMENT", "PTK_UNAVAILABLE", "Python or the PIPESIM Toolkit validation adapter could not be started.", true));
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or InvalidOperationException or System.ComponentModel.Win32Exception or PlatformNotSupportedException)
        {
            var confirmed = true;
            var killUsed = false;
            if (process is not null && processTree is not null)
            {
                var cleanup = await StopAsync(process, processTree, process.WaitForExitAsync());
                confirmed = cleanup.Confirmed;
                killUsed = cleanup.KillUsed;
            }
            return new(AdapterStopReason.ProtocolFailure, process is null ? null : TryGetExitCode(process), null, confirmed, false, killUsed,
                new WorkerError("ENVIRONMENT", "PTK_UNAVAILABLE", "Python or the PIPESIM Toolkit validation adapter could not be started.", true));
        }
        finally
        {
            processTree?.Dispose();
            process?.Dispose();
        }
    }

    private (Process Process, WindowsJobObject ProcessTree) StartProcess(string script, IReadOnlyList<string> arguments)
    {
        if (!File.Exists(options.EffectivePythonPath) || !File.Exists(options.EffectivePipesimPtkPath) || !File.Exists(script))
        {
            throw new FileNotFoundException("A required PIPESIM adapter dependency is unavailable.");
        }

        var startInfo = new ProcessStartInfo(options.EffectivePythonPath)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = Path.GetDirectoryName(script)!
        };
        startInfo.ArgumentList.Add("-u");
        startInfo.ArgumentList.Add(script);
        foreach (var argument in arguments) startInfo.ArgumentList.Add(argument);
        startInfo.Environment["PIPESIM_PTK_PATH"] = options.EffectivePipesimPtkPath;
        startInfo.Environment["PYTHONPATH"] = options.EffectivePipesimPtkPath;
        startInfo.Environment["PYTHONDONTWRITEBYTECODE"] = "1";
        startInfo.Environment["GRDP_PTK_START_GATED"] = "1";

        var process = Process.Start(startInfo) ?? throw new InvalidOperationException("Unable to start Python adapter.");
        WindowsJobObject? processTree = null;
        try
        {
            processTree = WindowsJobObject.CreateAndAssign(process);
            process.StandardInput.WriteLine("{\"type\":\"start\"}");
            process.StandardInput.Flush();
            return (process, processTree);
        }
        catch (Exception exception)
        {
            var confirmed = false;
            if (processTree is not null)
            {
                try
                {
                    confirmed = processTree.TerminateAndConfirmAsync(
                        TimeSpan.FromSeconds(options.ProcessExitConfirmationSeconds)).GetAwaiter().GetResult().Confirmed;
                }
                catch
                {
                    confirmed = false;
                }
                processTree.Dispose();
            }
            else if (!process.HasExited)
            {
                try { process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { }
                confirmed = process.WaitForExit(30000) && process.HasExited;
            }
            else
            {
                confirmed = true;
            }
            process.Dispose();
            throw new ProcessStartupException(confirmed, exception);
        }
    }

    private static async Task<JsonElement?> ReadProtocolAsync(Process process, Action<string, string> onEvent)
    {
        JsonElement? envelope = null;
        while (true)
        {
            var line = await process.StandardOutput.ReadLineAsync();
            if (line is null) break;
            using var document = JsonDocument.Parse(line);
            var root = document.RootElement;
            if (!root.TryGetProperty("type", out var typeProperty) || typeProperty.ValueKind != JsonValueKind.String)
            {
                throw new JsonException("Missing protocol type.");
            }

            switch (typeProperty.GetString())
            {
                case "event":
                    if (!root.TryGetProperty("state", out var state) || state.ValueKind != JsonValueKind.String ||
                        !root.TryGetProperty("message", out var message) || message.ValueKind != JsonValueKind.String)
                    {
                        throw new JsonException("Invalid event envelope.");
                    }
                    onEvent(state.GetString()!, message.GetString()!);
                    break;
                case "result":
                    if (envelope is not null) throw new JsonException("Duplicate result envelope.");
                    envelope = root.Clone();
                    break;
                default:
                    throw new JsonException("Unknown protocol envelope.");
            }
        }

        return envelope;
    }

    private static async Task DrainStandardErrorAsync(Process process)
    {
        while (await process.StandardError.ReadLineAsync() is not null)
        {
            // PTK stderr can contain sensitive local paths. It is deliberately drained but not retained.
        }
    }

    private async Task<(bool Confirmed, bool KillUsed)> StopAsync(Process process, WindowsJobObject processTree, Task exitTask)
    {
        var killUsed = false;
        if (!process.HasExited)
        {
            try
            {
                // The adapter has no stdin cancellation reader. This is only a best-effort grace
                // signal; cancellation is enforced by terminating and confirming the process tree.
                await process.StandardInput.WriteLineAsync("{\"type\":\"cancel\"}");
                await process.StandardInput.FlushAsync();
            }
            catch (Exception exception) when (exception is IOException or InvalidOperationException or ObjectDisposedException)
            {
                // The hard-stop path below owns cleanup when the signal cannot be delivered.
            }

            await Task.WhenAny(exitTask, Task.Delay(TimeSpan.FromSeconds(options.GracefulStopSeconds)));
        }

        var cleanup = await processTree.TerminateAndConfirmAsync(TimeSpan.FromSeconds(options.ProcessExitConfirmationSeconds));
        killUsed |= cleanup.TerminateUsed;
        return (cleanup.Confirmed, killUsed);
    }

    private static int? TryGetExitCode(Process process)
    {
        try { return process.HasExited ? process.ExitCode : null; }
        catch (InvalidOperationException) { return null; }
    }

    private static async Task IgnoreFailureAsync(Task task)
    {
        try { await task; }
        catch { }
    }
}
