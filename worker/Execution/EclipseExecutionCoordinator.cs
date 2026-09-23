using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed class EclipseExecutionCoordinator : IDisposable
{
    public const string MutexName = @"Global\GRDP-Eclipse-100";
    private readonly PtkExecutionCoordinator inner = new(MutexName);
    public bool IsBusy => inner.IsBusy;
    public CoordinatorAcquireResult TryAcquire(string operationKind) => inner.TryAcquire(operationKind);
    public void Dispose() => inner.Dispose();
}
