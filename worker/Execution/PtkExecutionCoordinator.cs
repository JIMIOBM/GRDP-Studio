using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record CoordinatorAcquireResult(PtkExecutionCoordinator.CoordinatorLease? Lease, WorkerError? Error)
{
    public bool Acquired => Lease is not null;
}

public sealed class PtkExecutionCoordinator : IDisposable
{
    public const string MutexName = @"Global\GRDP-Pipesim-Golden-Capture";
    private readonly object gate = new();
    private bool reserved;
    private bool disposed;

    public bool IsBusy
    {
        get { lock (gate) return reserved; }
    }

    public CoordinatorAcquireResult TryAcquire(string operationKind)
    {
        lock (gate)
        {
            if (disposed)
            {
                return new(null, WorkerApiError.Coordination("COORDINATOR_STOPPED", "PIPESIM execution coordinator is stopping.", false));
            }

            if (reserved)
            {
                return new(null, WorkerApiError.Coordination("WORKER_BUSY", "Another PIPESIM validation or run is active."));
            }

            reserved = true;
        }

        var machineLease = MachineMutexLease.TryAcquire(MutexName);
        if (machineLease.Lease is null)
        {
            ReleaseReservation();
            return new(null, machineLease.Error);
        }

        return new(new CoordinatorLease(this, machineLease.Lease, operationKind), null);
    }

    public void Dispose()
    {
        lock (gate) disposed = true;
    }

    private void ReleaseReservation()
    {
        lock (gate) reserved = false;
    }

    public sealed class CoordinatorLease : IDisposable
    {
        private readonly PtkExecutionCoordinator owner;
        private readonly MachineMutexLease mutexLease;
        private int disposed;

        internal CoordinatorLease(PtkExecutionCoordinator owner, MachineMutexLease mutexLease, string operationKind)
        {
            this.owner = owner;
            this.mutexLease = mutexLease;
            OperationKind = operationKind;
            AcquiredAtUtc = DateTimeOffset.UtcNow;
        }

        public string OperationKind { get; }
        public DateTimeOffset AcquiredAtUtc { get; }
        public bool ReleaseBlocked { get; private set; }

        public void BlockRelease() => ReleaseBlocked = true;

        public void Dispose()
        {
            if (ReleaseBlocked || Interlocked.Exchange(ref disposed, 1) != 0) return;
            mutexLease.Dispose();
            owner.ReleaseReservation();
        }
    }

    internal sealed record MachineMutexAcquire(MachineMutexLease? Lease, WorkerError? Error);

    internal sealed class MachineMutexLease : IDisposable
    {
        private readonly ManualResetEventSlim releaseSignal = new(false);
        private readonly Thread ownerThread;
        private readonly TaskCompletionSource<(bool Acquired, WorkerError? Error)> acquired = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private int disposed;

        private MachineMutexLease(string name)
        {
            ownerThread = new Thread(() => OwnMutex(name))
            {
                IsBackground = true,
                Name = "GRDP PIPESIM machine mutex owner"
            };
            ownerThread.Start();
        }

        public static MachineMutexAcquire TryAcquire(string name)
        {
            var lease = new MachineMutexLease(name);
            var result = lease.acquired.Task.GetAwaiter().GetResult();
            if (result.Acquired) return new(lease, null);
            lease.Dispose();
            return new(null, result.Error);
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref disposed, 1) != 0) return;
            releaseSignal.Set();
            if (!ownerThread.Join(TimeSpan.FromSeconds(30)))
            {
                throw new InvalidOperationException("The PIPESIM machine mutex owner thread did not terminate.");
            }
            releaseSignal.Dispose();
        }

        private void OwnMutex(string name)
        {
            Mutex? mutex = null;
            var held = false;
            try
            {
                mutex = new Mutex(false, name);
                try
                {
                    held = mutex.WaitOne(0);
                }
                catch (AbandonedMutexException)
                {
                    held = true;
                }

                if (!held)
                {
                    acquired.TrySetResult((false, WorkerApiError.Coordination("PIPESIM_GLOBAL_BUSY", "Another machine-wide PIPESIM operation is active.")));
                    return;
                }

                acquired.TrySetResult((true, null));
                releaseSignal.Wait();
            }
            catch (Exception exception) when (exception is UnauthorizedAccessException or IOException or PlatformNotSupportedException)
            {
                acquired.TrySetResult((false, WorkerApiError.Coordination("MACHINE_LOCK_UNAVAILABLE", "The machine-wide PIPESIM lock is unavailable.", false)));
            }
            finally
            {
                if (held) mutex!.ReleaseMutex();
                mutex?.Dispose();
                acquired.TrySetResult((false, WorkerApiError.Coordination("MACHINE_LOCK_UNAVAILABLE", "The machine-wide PIPESIM lock is unavailable.", false)));
            }
        }
    }
}
