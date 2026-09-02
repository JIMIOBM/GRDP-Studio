using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public enum ClaimStatus { Created, Idempotent, RunIdConflict, WorkerBusy }
public enum CancelRequestStatus { Accepted, AlreadyCancelled, TerminalConflict, NotFound }
public enum RunCompletionDisposition { Published, CancelledByRequest, AlreadyTerminal, PublicationFailed }
public sealed record ClaimOutcome(ClaimStatus Status, RunAcceptedResponse? Accepted, WorkerError? Error);
public sealed record CancelOutcome(CancelRequestStatus Status, object Body);
public sealed record RunCompletionResult(RunCompletionDisposition Disposition, string State);

public sealed class PtkRunRegistry
{
    private static readonly HashSet<string> TerminalStates = ["SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"];
    private readonly object gate = new();
    private readonly Dictionary<long, Entry> entries = [];
    private readonly WorkerIdentity identity;

    public PtkRunRegistry(WorkerIdentity identity) => this.identity = identity;

    public long? ActiveRunId
    {
        get
        {
            lock (gate)
            {
                return entries.Values.FirstOrDefault(entry => !TerminalStates.Contains(entry.State))?.Request.RunId;
            }
        }
    }

    public ClaimOutcome TryClaim(RunExecuteRequest request, string fingerprint)
    {
        lock (gate)
        {
            if (entries.TryGetValue(request.RunId, out var existing))
            {
                return existing.Fingerprint == fingerprint
                    ? new(ClaimStatus.Idempotent, existing.Accepted, null)
                    : new(ClaimStatus.RunIdConflict, null, WorkerApiError.Request("RUN_ID_CONFLICT", "runId is already associated with a different request."));
            }

            if (entries.Values.Any(entry => !TerminalStates.Contains(entry.State)))
            {
                return new(ClaimStatus.WorkerBusy, null, WorkerApiError.Coordination("WORKER_BUSY", "Another run is active."));
            }

            var acceptedAt = DateTimeOffset.UtcNow;
            var accepted = new RunAcceptedResponse(request.RunId, "CLAIMED", identity.WorkerId, identity.GenerationId, acceptedAt);
            var entry = new Entry(request, fingerprint, accepted);
            entry.AddEvent("CLAIMED", acceptedAt, "Run claimed by Worker generation.");
            entries.Add(request.RunId, entry);
            return new(ClaimStatus.Created, accepted, null);
        }
    }

    public bool ExistsWithFingerprint(long runId, string fingerprint, out RunAcceptedResponse? response, out bool conflict)
    {
        lock (gate)
        {
            if (!entries.TryGetValue(runId, out var entry))
            {
                response = null;
                conflict = false;
                return false;
            }

            conflict = entry.Fingerprint != fingerprint;
            response = conflict ? null : entry.Accepted;
            return true;
        }
    }

    public CancellationToken GetCancellationToken(long runId)
    {
        lock (gate) return entries[runId].Cancellation.Token;
    }

    public string GetState(long runId)
    {
        lock (gate) return entries[runId].State;
    }

    public void Transition(long runId, string state, string message, DateTimeOffset? occurredAt = null)
    {
        lock (gate)
        {
            var entry = entries[runId];
            if (TerminalStates.Contains(entry.State)) return;
            if (entry.State == "CANCEL_REQUESTED" && state is not ("CANCELLED" or "FAILED")) return;
            if (!IsAllowedTransition(entry, state))
            {
                throw new InvalidOperationException($"Invalid Worker run transition {entry.State} -> {state}.");
            }
            var timestamp = occurredAt ?? DateTimeOffset.UtcNow;
            entry.State = state;
            if (state == "PREPARING") entry.StartedAtUtc = timestamp;
            if (TerminalStates.Contains(state)) entry.CompletedAtUtc = timestamp;
            entry.AddEvent(state, timestamp, message);
        }
    }

    private static bool IsAllowedTransition(Entry entry, string next) => entry.State switch
    {
        "CLAIMED" => next == "PREPARING",
        "PREPARING" => entry.Request.RunTask == "profile" ? next == "RUNNING_PROFILE" : next == "RUNNING_NODAL",
        "RUNNING_NODAL" => entry.Request.RunTask == "combined" ? next == "RUNNING_PROFILE" : next == "COLLECTING",
        "RUNNING_PROFILE" => next == "COLLECTING",
        _ => false
    };

    public RunCompletionResult Complete(
        long runId,
        string state,
        JsonElement? result,
        WorkerError? error,
        IReadOnlyList<ArtifactDescriptor> artifacts,
        RunCleanup cleanup,
        string message)
    {
        lock (gate)
        {
            var entry = entries[runId];
            if (TerminalStates.Contains(entry.State))
            {
                return new(RunCompletionDisposition.AlreadyTerminal, entry.State);
            }
            var disposition = RunCompletionDisposition.Published;
            if (entry.State == "CANCEL_REQUESTED" && state is "SUCCEEDED" or "PARTIAL_SUCCEEDED")
            {
                disposition = RunCompletionDisposition.CancelledByRequest;
                state = "CANCELLED";
                result = null;
                error = new WorkerError("CANCELLATION", "RUN_CANCELLED", "Run cancellation was requested.", false);
                message = "Run cancellation completed after process-tree exit confirmation.";
            }
            if (!TerminalStates.Contains(state) ||
                (state is "SUCCEEDED" or "PARTIAL_SUCCEEDED" && entry.State != "COLLECTING"))
            {
                throw new InvalidOperationException($"Invalid terminal Worker run transition {entry.State} -> {state}.");
            }
            var timestamp = DateTimeOffset.UtcNow;
            entry.Result = result?.Clone();
            entry.Error = error;
            entry.Artifacts = artifacts.ToArray();
            entry.Cleanup = cleanup;
            entry.State = state;
            entry.CompletedAtUtc = timestamp;
            entry.AddEvent(state, timestamp, message);
            return new(disposition, state);
        }
    }

    public bool TryFailCompletion(
        long runId,
        IReadOnlyList<ArtifactDescriptor> artifacts,
        RunCleanup cleanup)
    {
        lock (gate)
        {
            if (!entries.TryGetValue(runId, out var entry) || TerminalStates.Contains(entry.State)) return false;
            var timestamp = DateTimeOffset.UtcNow;
            entry.Result = null;
            entry.Error = new WorkerError(
                "CLEANUP",
                "RUN_COMPLETION_FAILED",
                "Run terminal publication failed; Worker coordination was recovered.",
                false);
            entry.Artifacts = artifacts.ToArray();
            entry.Cleanup = cleanup;
            entry.State = "FAILED";
            entry.CompletedAtUtc = timestamp;
            entry.AddEvent("FAILED", timestamp, entry.Error.Message);
            return true;
        }
    }

    public RunSnapshot? GetSnapshot(long runId, long afterSequence)
    {
        lock (gate)
        {
            if (!entries.TryGetValue(runId, out var entry)) return null;
            var end = entry.CompletedAtUtc ?? DateTimeOffset.UtcNow;
            var elapsedStart = entry.StartedAtUtc ?? entry.Accepted.AcceptedAtUtc;
            var elapsed = Math.Max(0, (long)(end - elapsedStart).TotalMilliseconds);
            return new RunSnapshot(
                runId,
                entry.State,
                entry.Sequence,
                identity.WorkerId,
                identity.GenerationId,
                entry.Accepted.AcceptedAtUtc,
                entry.StartedAtUtc,
                entry.CompletedAtUtc,
                elapsed,
                entry.Events.Where(item => item.Sequence > afterSequence).ToArray(),
                entry.Result?.Clone(),
                entry.Error,
                entry.Artifacts,
                entry.Cleanup);
        }
    }

    public CancelOutcome RequestCancel(long runId)
    {
        lock (gate)
        {
            if (!entries.TryGetValue(runId, out var entry))
            {
                return new(CancelRequestStatus.NotFound, WorkerApiError.Request("RUN_NOT_FOUND", "Run was not found in this Worker generation."));
            }

            if (entry.State == "CANCELLED")
            {
                return new(CancelRequestStatus.AlreadyCancelled, new CancelResponse(runId, entry.State, entry.CancelRequestedAtUtc ?? entry.CompletedAtUtc ?? DateTimeOffset.UtcNow));
            }

            if (TerminalStates.Contains(entry.State))
            {
                return new(CancelRequestStatus.TerminalConflict, WorkerApiError.Request("RUN_TERMINAL", "A terminal run cannot be cancelled."));
            }

            if (entry.State != "CANCEL_REQUESTED")
            {
                var now = DateTimeOffset.UtcNow;
                entry.CancelRequestedAtUtc = now;
                entry.State = "CANCEL_REQUESTED";
                entry.AddEvent("CANCEL_REQUESTED", now, "Cancellation requested.");
                entry.Cancellation.Cancel();
            }

            return new(CancelRequestStatus.Accepted, new CancelResponse(runId, "CANCEL_REQUESTED", entry.CancelRequestedAtUtc!.Value));
        }
    }

    private sealed class Entry
    {
        public Entry(RunExecuteRequest request, string fingerprint, RunAcceptedResponse accepted)
        {
            Request = request;
            Fingerprint = fingerprint;
            Accepted = accepted;
        }

        public RunExecuteRequest Request { get; }
        public string Fingerprint { get; }
        public RunAcceptedResponse Accepted { get; }
        public string State { get; set; } = "CLAIMED";
        public long Sequence { get; private set; }
        public List<RunEvent> Events { get; } = [];
        public DateTimeOffset? StartedAtUtc { get; set; }
        public DateTimeOffset? CompletedAtUtc { get; set; }
        public DateTimeOffset? CancelRequestedAtUtc { get; set; }
        public JsonElement? Result { get; set; }
        public WorkerError? Error { get; set; }
        public IReadOnlyList<ArtifactDescriptor> Artifacts { get; set; } = [];
        public RunCleanup? Cleanup { get; set; }
        public CancellationTokenSource Cancellation { get; } = new();

        public void AddEvent(string state, DateTimeOffset timestamp, string message)
        {
            Sequence++;
            Events.Add(new RunEvent(Sequence, state, timestamp, message));
        }
    }
}
