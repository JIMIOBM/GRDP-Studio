using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed class WorkerOptions
{
    public string WorkerId { get; init; } = "grdp-pipesim-worker";
    public string StorageRoot { get; init; } = @"C:\GRDP-Data";
    public string PipesimHome { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Schlumberger", "PIPESIM2022.1");
    public string? PipesimPtkPath { get; set; }
    public string? PythonPath { get; set; }
    public int MaxRunTimeoutSeconds { get; init; } = 600;
    public int ValidationTimeoutSeconds { get; init; } = 120;
    public int GracefulStopSeconds { get; init; } = 2;
    public int ProcessExitConfirmationSeconds { get; init; } = 30;
    public string? EclrunPath { get; set; }
    public int EclipseMaxRunTimeoutSeconds { get; init; } = 1800;
    public int EclipseCleanupTimeoutSeconds { get; init; } = 30;
    public string? IamDemoSourceModelPath { get; init; }
    public string? IamDemoEngineInputPath { get; init; }
    public string? IamDemoInstallationPath { get; init; }
    public string? IamDemoPipesimProgramsPath { get; init; }
    public string? IamDemoProbePath { get; init; }
    public int IamDemoTimeoutSeconds { get; init; } = 300;

    public string EffectivePipesimPtkPath => PipesimPtkPath ?? Path.Combine(PipesimHome, "Developer Tools", "Python Toolkit", "Modules", "PythonToolkitModules.zip");

    public string EffectivePythonPath => PythonPath ?? Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Programs",
        "Python",
        "Python39",
        "python.exe");

    public string EffectiveIamDemoSourceModelPath => IamDemoSourceModelPath ?? Path.Combine(
        StorageRoot, "iam-pipesim-demo", "iam-rest-activation", "SIS2.pips");

    public string EffectiveIamDemoEngineInputPath => IamDemoEngineInputPath ?? Path.Combine(
        StorageRoot, "iam-probe-20260927-pipesim", "enginefiles", "SIS2.tnt");

    public string EffectiveIamDemoInstallationPath => IamDemoInstallationPath ?? Path.Combine(
        StorageRoot, "iam-pipesim-demo", "iam-runtime");

    public string EffectiveIamDemoPipesimProgramsPath => IamDemoPipesimProgramsPath ?? Path.Combine(
        StorageRoot, "iam-pipesim-demo", "PIPESIM2021Compat", "Programs");

    public string EffectiveIamDemoProbePath => IamDemoProbePath ?? Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "GRDP", "IamRestHost", "PipesimAgentProbe.exe");
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
