using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseLauncherTests
{
    [Theory]
    [InlineData(true, true, false)]
    [InlineData(false, true, true)]
    [InlineData(true, false, true)]
    [InlineData(false, false, true)]
    public void CancellationCleanupRequiresSuccessfulKillAndCheck(bool killSucceeds, bool checkSucceeds, bool expectedFailure)
    {
        var kill = Result(killSucceeds, "kill diagnostics");
        var check = Result(checkSucceeds, "check diagnostics");

        var combined = EclipseLauncher.CombineCleanupResults(kill, check);

        Assert.Equal(expectedFailure, combined.Error is not null);
        Assert.Contains("kill diagnostics", combined.Diagnostics);
        Assert.Contains("check diagnostics", combined.Diagnostics);
    }

    [Theory]
    [InlineData(AdapterStopReason.Cancelled, "kill", "check")]
    [InlineData(AdapterStopReason.TimedOut, "check")]
    [InlineData(AdapterStopReason.None, "check")]
    public async Task CleanupUsesOriginalDataFilenameAndFrozenCommands(AdapterStopReason reason, params string[] commands)
    {
        var calls = new List<string[]>();
        var launcher = new EclipseLauncher(new WorkerOptions { EclipseCleanupTimeoutSeconds = 1 }, (arguments, _, _, _) =>
        {
            calls.Add(arguments.ToArray());
            return Task.FromResult(Result(true, string.Empty));
        });

        await launcher.CleanupAsync("CASE.DATA", "work", reason == AdapterStopReason.Cancelled, CancellationToken.None);

        Assert.Equal(commands, calls.Select(call => call[0]));
        Assert.All(calls, call => Assert.Equal("CASE.DATA", call[1]));
    }

    [Theory]
    [InlineData(false, true)]
    [InlineData(true, false)]
    public void FailedKillOrCheckDoesNotReportCleanupCompleted(bool killSucceeds, bool checkSucceeds)
    {
        var combined = EclipseLauncher.CombineCleanupResults(Result(killSucceeds, "kill"), Result(checkSucceeds, "check"));
        var cleanup = EclipseRunService.CreateCleanup(true, true, true, false, combined.Error is not null);

        Assert.True(cleanup.ProcessTreeExitConfirmed);
        Assert.NotEqual("ECLIPSE cleanup completed.", cleanup.Message);
        Assert.Contains("failed", cleanup.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void UnconfirmedProcessTreeDoesNotReportCleanupCompleted()
    {
        var cleanup = EclipseRunService.CreateCleanup(false, true, true, true, false);

        Assert.False(cleanup.ProcessTreeExitConfirmed);
        Assert.NotEqual("ECLIPSE cleanup completed.", cleanup.Message);
        Assert.Contains("unconfirmed", cleanup.Message, StringComparison.OrdinalIgnoreCase);
    }

    private static EclipseLaunchResult Result(bool succeeds, string diagnostics) => new(
        succeeds ? 0 : 1, succeeds, false, diagnostics, AdapterStopReason.None,
        succeeds ? null : new WorkerError("CLEANUP", "FAILED", "failed", false));
}
