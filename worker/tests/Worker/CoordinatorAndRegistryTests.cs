using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class CoordinatorAndRegistryTests
{
    [Fact]
    public void TwoRunsAndValidationRunUseTheSameExclusiveCoordinator()
    {
        using var coordinator = new PtkExecutionCoordinator();
        var run = coordinator.TryAcquire("run");
        Assert.True(run.Acquired);
        Assert.Equal("WORKER_BUSY", coordinator.TryAcquire("run").Error!.Code);
        Assert.Equal("WORKER_BUSY", coordinator.TryAcquire("validation").Error!.Code);
        run.Lease!.Dispose();

        var validation = coordinator.TryAcquire("validation");
        Assert.True(validation.Acquired);
        Assert.Equal("WORKER_BUSY", coordinator.TryAcquire("run").Error!.Code);
        validation.Lease!.Dispose();
        Assert.False(coordinator.IsBusy);
    }

    [Fact]
    public void MachineWideGoldenMutexBlocksWorkerAcquisition()
    {
        using var mutex = new Mutex(false, PtkExecutionCoordinator.MutexName);
        Assert.True(mutex.WaitOne(0));
        try
        {
            using var coordinator = new PtkExecutionCoordinator();
            var blocked = coordinator.TryAcquire("run");
            Assert.False(blocked.Acquired);
            Assert.Equal("PIPESIM_GLOBAL_BUSY", blocked.Error!.Code);
        }
        finally
        {
            mutex.ReleaseMutex();
        }
    }

    [Fact]
    public void RegistryPreservesOrderedIncrementalEventsIdempotencyAndCancellation()
    {
        var identity = new WorkerIdentity(Options.Create(new WorkerOptions { WorkerId = "test-worker" }));
        var registry = new PtkRunRegistry(identity);
        var request = Request(7);
        var claim = registry.TryClaim(request, "same");
        Assert.Equal(ClaimStatus.Created, claim.Status);
        Assert.Equal(ClaimStatus.Idempotent, registry.TryClaim(request, "same").Status);
        Assert.Equal(ClaimStatus.RunIdConflict, registry.TryClaim(request, "different").Status);

        registry.Transition(7, "PREPARING", "prepare");
        registry.Transition(7, "RUNNING_NODAL", "nodal");
        Assert.Throws<InvalidOperationException>(() => registry.Transition(7, "RUNNING_PROFILE", "invalid for nodal-only"));
        var first = registry.GetSnapshot(7, 0)!;
        Assert.Equal(new[] { "CLAIMED", "PREPARING", "RUNNING_NODAL" }, first.Events.Select(item => item.State));
        var incremental = registry.GetSnapshot(7, 2)!;
        Assert.Single(incremental.Events);
        Assert.Equal(3, incremental.LastSequence);
        Assert.Equal(identity.GenerationId, incremental.GenerationId);

        var cancel = registry.RequestCancel(7);
        Assert.Equal(CancelRequestStatus.Accepted, cancel.Status);
        Assert.True(registry.GetCancellationToken(7).IsCancellationRequested);
        registry.Complete(7, "CANCELLED", null, null, [], new RunCleanup(true, true, true, false, "confirmed"), "cancelled");
        Assert.Equal(CancelRequestStatus.AlreadyCancelled, registry.RequestCancel(7).Status);
    }

    [Fact]
    public void NewWorkerIdentityChangesGeneration()
    {
        var options = Options.Create(new WorkerOptions { WorkerId = "same-worker" });
        var first = new WorkerIdentity(options);
        var second = new WorkerIdentity(options);
        Assert.Equal(first.WorkerId, second.WorkerId);
        Assert.NotEqual(first.GenerationId, second.GenerationId);
    }

    [Theory]
    [InlineData("SUCCEEDED")]
    [InlineData("PARTIAL_SUCCEEDED")]
    public void CancelLinearizedInCollectingWinsOverSuccessAndReleasesBothLocks(string proposedState)
    {
        using var coordinator = new PtkExecutionCoordinator();
        var acquired = coordinator.TryAcquire("run");
        Assert.True(acquired.Acquired);
        var registry = Registry();
        var request = Request(20, "combined");
        Assert.Equal(ClaimStatus.Created, registry.TryClaim(request, "cancel-race").Status);
        registry.Transition(20, "PREPARING", "prepare");
        registry.Transition(20, "RUNNING_NODAL", "nodal");
        registry.Transition(20, "RUNNING_PROFILE", "profile");
        registry.Transition(20, "COLLECTING", "collect");
        Assert.Equal(CancelRequestStatus.Accepted, registry.RequestCancel(20).Status);
        using var resultDocument = JsonDocument.Parse("{\"schemaVersion\":\"pipesim-well-result/1\"}");
        var cleanup = new RunCleanup(true, true, true, false, "confirmed");

        var completion = PtkRunService.PublishCompletionAndRelease(
            registry,
            20,
            proposedState,
            resultDocument.RootElement.Clone(),
            proposedState == "PARTIAL_SUCCEEDED"
                ? new WorkerError("EXECUTION", "PROFILE_RUN_FAILED", "profile failed", false)
                : null,
            [],
            cleanup,
            "success",
            acquired.Lease!,
            blockRelease: false);

        Assert.Equal(RunCompletionDisposition.CancelledByRequest, completion.Disposition);
        var snapshot = registry.GetSnapshot(20, 0)!;
        Assert.Equal("CANCELLED", snapshot.State);
        Assert.Null(snapshot.Result);
        Assert.Equal("CANCELLATION", snapshot.Error!.Category);
        Assert.Equal("RUN_CANCELLED", snapshot.Error.Code);
        Assert.Equal(cleanup, snapshot.Cleanup);
        Assert.Equal("CANCELLED", snapshot.Events[^1].State);
        Assert.False(coordinator.IsBusy);
        var next = coordinator.TryAcquire("validation");
        Assert.True(next.Acquired);
        next.Lease!.Dispose();
    }

    [Fact]
    public void CompletionExceptionPublishesCleanupFailureAndReleasesBothLocks()
    {
        using var coordinator = new PtkExecutionCoordinator();
        var acquired = coordinator.TryAcquire("run");
        Assert.True(acquired.Acquired);
        var registry = Registry();
        Assert.Equal(ClaimStatus.Created, registry.TryClaim(Request(21), "completion-failure").Status);
        registry.Transition(21, "PREPARING", "prepare");
        using var resultDocument = JsonDocument.Parse("{\"schemaVersion\":\"pipesim-well-result/1\"}");
        var cleanup = new RunCleanup(true, true, true, false, "confirmed");

        var completion = PtkRunService.PublishCompletionAndRelease(
            registry,
            21,
            "SUCCEEDED",
            resultDocument.RootElement.Clone(),
            null,
            [],
            cleanup,
            "invalid success",
            acquired.Lease!,
            blockRelease: false);

        Assert.Equal(RunCompletionDisposition.PublicationFailed, completion.Disposition);
        var snapshot = registry.GetSnapshot(21, 0)!;
        Assert.Equal("FAILED", snapshot.State);
        Assert.Null(snapshot.Result);
        Assert.Equal("CLEANUP", snapshot.Error!.Category);
        Assert.Equal("RUN_COMPLETION_FAILED", snapshot.Error.Code);
        Assert.Equal(cleanup, snapshot.Cleanup);
        Assert.False(coordinator.IsBusy);
        var next = coordinator.TryAcquire("run");
        Assert.True(next.Acquired);
        next.Lease!.Dispose();
    }

    [Fact]
    public void TimeoutCompletionIsNotRewrittenAsCancellation()
    {
        using var coordinator = new PtkExecutionCoordinator();
        var acquired = coordinator.TryAcquire("run");
        var registry = Registry();
        Assert.Equal(ClaimStatus.Created, registry.TryClaim(Request(22), "timeout-race").Status);
        registry.Transition(22, "PREPARING", "prepare");
        registry.Transition(22, "RUNNING_NODAL", "nodal");
        registry.Transition(22, "COLLECTING", "collect");
        Assert.Equal(CancelRequestStatus.Accepted, registry.RequestCancel(22).Status);
        var timeout = new WorkerError("TIMEOUT", "RUN_TIMEOUT", "timed out", false);

        var completion = PtkRunService.PublishCompletionAndRelease(
            registry,
            22,
            "TIMED_OUT",
            null,
            timeout,
            [],
            new RunCleanup(true, true, true, true, "confirmed"),
            "timed out",
            acquired.Lease!,
            blockRelease: false);

        Assert.Equal(RunCompletionDisposition.Published, completion.Disposition);
        var snapshot = registry.GetSnapshot(22, 0)!;
        Assert.Equal("TIMED_OUT", snapshot.State);
        Assert.Equal("TIMEOUT", snapshot.Error!.Category);
        Assert.Equal("RUN_TIMEOUT", snapshot.Error.Code);
        Assert.False(coordinator.IsBusy);
    }

    private static PtkRunRegistry Registry() => new(
        new WorkerIdentity(Options.Create(new WorkerOptions { WorkerId = "test-worker" })));

    private static RunExecuteRequest Request(long id, string runTask = "nodal")
    {
        using var document = JsonDocument.Parse("null");
        return new RunExecuteRequest(id, "models/well.pips", new string('a', 64), "Study 1", runTask, document.RootElement.Clone(), 600);
    }
}
