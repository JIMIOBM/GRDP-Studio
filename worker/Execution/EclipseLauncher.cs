using System.Diagnostics;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record EclipseCapability(bool LauncherFound, bool Available);
public sealed record EclipseLaunchResult(int? ExitCode, bool ProcessTreeExitConfirmed, bool KillUsed, string Diagnostics, AdapterStopReason StopReason, WorkerError? Error);

public sealed class EclipseLauncher
{
    private readonly WorkerOptions options;
    private readonly Func<IReadOnlyList<string>, string, TimeSpan, CancellationToken, Task<EclipseLaunchResult>>? invoke;
    public EclipseLauncher(IOptions<WorkerOptions> options) => this.options = options.Value;
    internal EclipseLauncher(WorkerOptions options, Func<IReadOnlyList<string>, string, TimeSpan, CancellationToken, Task<EclipseLaunchResult>> invoke)
        => (this.options, this.invoke) = (options, invoke);
    private string Launcher => string.IsNullOrWhiteSpace(options.EclrunPath) ? "eclrun.exe" : options.EclrunPath;

    public async Task<EclipseCapability> GetCapabilityAsync(CancellationToken cancellationToken)
    {
        var query = await InvokeAsync(["--report-versions", "eclipse"], Environment.CurrentDirectory, TimeSpan.FromSeconds(10), cancellationToken);
        return new(query.Error is null, query.Error is null && query.ExitCode == 0 && EclipseRunRules.IsSupportedVersion(query.Diagnostics));
    }

    public Task<EclipseLaunchResult> RunAsync(string caseFile, string workDirectory, TimeSpan timeout, CancellationToken cancellationToken) =>
        InvokeAsync(["-v", "2024.1", "eclipse", caseFile], workDirectory, timeout, cancellationToken);

    public async Task<EclipseLaunchResult> CleanupAsync(string caseFile, string workDirectory, bool cancelled, CancellationToken cancellationToken)
    {
        EclipseLaunchResult? kill = null;
        if (cancelled)
        {
            kill = await InvokeAsync(["kill", caseFile], workDirectory, TimeSpan.FromSeconds(options.EclipseCleanupTimeoutSeconds), cancellationToken);
        }
        var check = await InvokeAsync(["check", caseFile], workDirectory, TimeSpan.FromSeconds(options.EclipseCleanupTimeoutSeconds), cancellationToken);
        return CombineCleanupResults(kill, check);
    }

    internal static EclipseLaunchResult CombineCleanupResults(EclipseLaunchResult? kill, EclipseLaunchResult check)
    {
        var killFailed = kill is not null && (kill.Error is not null || kill.ExitCode != 0 || !kill.ProcessTreeExitConfirmed);
        var checkFailed = check.Error is not null || check.ExitCode != 0 || !check.ProcessTreeExitConfirmed;
        var diagnostics = string.Join("\n", new[] { kill?.Diagnostics, check.Diagnostics }.Where(value => !string.IsNullOrWhiteSpace(value)));
        return check with
        {
            KillUsed = (kill?.KillUsed ?? false) || check.KillUsed,
            Diagnostics = diagnostics,
            Error = killFailed || checkFailed
                ? new WorkerError("CLEANUP", "ECLIPSE_CLEANUP_FAILED", "ECLIPSE cleanup could not be confirmed.", false)
                : null
        };
    }

    private async Task<EclipseLaunchResult> InvokeAsync(IReadOnlyList<string> arguments, string workDirectory, TimeSpan timeout, CancellationToken cancellationToken)
    {
        if (invoke is not null) return await invoke(arguments, workDirectory, timeout, cancellationToken);
        Process? process = null; WindowsJobObject? job = null;
        try
        {
            var start = new ProcessStartInfo(Launcher) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = workDirectory };
            foreach (var argument in arguments) start.ArgumentList.Add(argument);
            process = Process.Start(start) ?? throw new InvalidOperationException();
            job = WindowsJobObject.CreateAndAssign(process);
            var output = process.StandardOutput.ReadToEndAsync(); var error = process.StandardError.ReadToEndAsync(); var exit = process.WaitForExitAsync();
            var completed = await Task.WhenAny(exit, Task.Delay(timeout), Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken));
            if (completed != exit)
            {
                var stopped = await job.TerminateAndConfirmAsync(TimeSpan.FromSeconds(options.ProcessExitConfirmationSeconds));
                await Task.WhenAll(Ignore(output), Ignore(error));
                return new(null, stopped.Confirmed, stopped.TerminateUsed, string.Empty, cancellationToken.IsCancellationRequested ? AdapterStopReason.Cancelled : AdapterStopReason.TimedOut,
                    new WorkerError(cancellationToken.IsCancellationRequested ? "CANCELLATION" : "TIMEOUT", cancellationToken.IsCancellationRequested ? "RUN_CANCELLED" : "RUN_TIMEOUT", cancellationToken.IsCancellationRequested ? "Run cancellation was requested." : "The configured run timeout elapsed.", false));
            }
            await exit;
            var confirmed = await job.ConfirmNormalExitAsync(process, TimeSpan.FromSeconds(options.ProcessExitConfirmationSeconds));
            var diagnostics = (await output) + "\n" + (await error);
            return new(process.ExitCode, confirmed.Confirmed, confirmed.TerminateUsed, diagnostics, AdapterStopReason.None,
                confirmed.Confirmed ? null : new WorkerError("CLEANUP", "PROCESS_TREE_EXIT_UNCONFIRMED", "ECLIPSE process-tree exit could not be confirmed.", false));
        }
        catch (Exception exception) when (exception is System.ComponentModel.Win32Exception or InvalidOperationException or PlatformNotSupportedException)
        { return new(null, true, false, string.Empty, AdapterStopReason.ProtocolFailure, new WorkerError("ENVIRONMENT", "ECLIPSE_UNAVAILABLE", "The controlled ECLIPSE launcher is unavailable.", true)); }
        finally { job?.Dispose(); process?.Dispose(); }
    }
    private static async Task Ignore(Task task) { try { await task; } catch { } }
}
