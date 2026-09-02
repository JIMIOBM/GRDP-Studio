using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed class WorkerOptions
{
    public string WorkerId { get; init; } = "grdp-pipesim-worker";
    public string StorageRoot { get; init; } = @"C:\GRDP-Data";
    public string PipesimHome { get; init; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Schlumberger", "PIPESIM2022.1");
    public string? PipesimPtkPath { get; init; }
    public string? PythonPath { get; init; }
    public int MaxRunTimeoutSeconds { get; init; } = 600;
    public int ValidationTimeoutSeconds { get; init; } = 120;
    public int GracefulStopSeconds { get; init; } = 2;
    public int ProcessExitConfirmationSeconds { get; init; } = 30;

    public string EffectivePipesimPtkPath => PipesimPtkPath ?? Path.Combine(PipesimHome, "Developer Tools", "Python Toolkit", "Modules", "PythonToolkitModules.zip");

    public string EffectivePythonPath => PythonPath ?? Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Programs",
        "Python",
        "Python39",
        "python.exe");
}

public sealed class WorkerIdentity
{
    public WorkerIdentity(IOptions<WorkerOptions> options)
    {
        WorkerId = string.IsNullOrWhiteSpace(options.Value.WorkerId) ? "grdp-pipesim-worker" : options.Value.WorkerId.Trim();
        GenerationId = Guid.NewGuid().ToString("N");
    }

    public string WorkerId { get; }
    public string GenerationId { get; }
}
