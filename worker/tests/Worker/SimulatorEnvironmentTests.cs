using Grdp.SoftwareIntegration.Worker.EnvironmentSetup;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class SimulatorEnvironmentTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-environment-" + Guid.NewGuid().ToString("N"));
    private readonly PtkExecutionCoordinator p = new("grdp-environment-test-" + Guid.NewGuid().ToString("N"));
    private readonly EclipseExecutionCoordinator e = new();
    private WorkerOptions Options() => new() { StorageRoot = root, PipesimHome = Path.Combine(root, "absent"), PythonPath = Path.Combine(root, "absent", "python.exe"), EclrunPath = Path.Combine(root, "absent", "eclrun.exe") };
    private EnvironmentPaths Files()
    {
        var home = Path.Combine(root, "PIPESIM2022.1"); Directory.CreateDirectory(home);
        var python = Path.Combine(root, "python.exe"); File.WriteAllText(python, "fixture");
        var toolkit = Path.Combine(home, "PythonToolkitModules.zip"); File.WriteAllText(toolkit, "fixture");
        var launcher = Path.Combine(root, "eclrun.exe"); File.WriteAllText(launcher, "fixture");
        return new(home, python, toolkit, launcher);
    }
    private SimulatorEnvironment Service(WorkerOptions options, bool success = true) => new(options, p, e,
        (_, args, _) => Task.FromResult((success, args.Contains("-c") ? "GRDP_PTK_OK" : "2024.1\n")));

    [Fact]
    public async Task Missing_installations_are_reported_without_executing_a_probe()
    {
        var service = new SimulatorEnvironment(Options(), p, e, (_, _, _) => throw new Exception("must not execute"));
        var result = await service.InspectAsync(TestContext.Current.CancellationToken);
        Assert.False(result.Configured); Assert.Equal("MISSING", result.Pipesim.Status); Assert.Equal("MISSING", result.Eclipse.Status);
        Assert.False(File.Exists(service.ConfigurationFile));
    }
    [Fact]
    public async Task Save_one_engine_applies_immediately_and_survives_restart()
    {
        var paths = Files(); var options = Options(); var service = Service(options);
        var result = await service.SaveAsync(new("pipesim", paths), TestContext.Current.CancellationToken);
        Assert.True(result.Configured); Assert.Equal(new[] { "pipesim" }, result.ConfiguredSimulators);
        Assert.Equal("READY", result.Pipesim.Status); Assert.Equal("MISSING", result.Eclipse.Status);
        Assert.Equal(paths.PythonPath, options.EffectivePythonPath);
        var restarted = Options(); Service(restarted).Initialize();
        Assert.Equal(paths.PipesimHome, restarted.PipesimHome); Assert.Equal(paths.PipesimPtkPath, restarted.EffectivePipesimPtkPath);
    }
    [Fact]
    public async Task Failed_probe_preserves_original_options_and_configuration()
    {
        var paths = Files(); var options = Options(); var service = Service(options, false);
        await Assert.ThrowsAsync<ArgumentException>(() => service.SaveAsync(new("pipesim", paths), TestContext.Current.CancellationToken));
        Assert.Contains("absent", options.PipesimHome); Assert.False(File.Exists(service.ConfigurationFile));
    }
    [Fact]
    public async Task Busy_worker_rejects_configuration()
    {
        var paths = Files(); using var lease = p.TryAcquire("test-run").Lease;
        Assert.NotNull(lease);
        var service = Service(Options());
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.SaveAsync(new("pipesim", paths), TestContext.Current.CancellationToken));
        Assert.False(File.Exists(service.ConfigurationFile));
    }
    [Fact]
    public async Task Broken_other_engine_does_not_prevent_saving_pipesim()
    {
        var paths = Files(); var service = Service(Options());
        await service.SaveAsync(new("eclipse", paths), TestContext.Current.CancellationToken);
        File.Delete(paths.EclrunPath!);
        var result = await service.SaveAsync(new("pipesim", paths), TestContext.Current.CancellationToken);
        Assert.Equal("READY", result.Pipesim.Status); Assert.Equal("MISSING", result.Eclipse.Status);
    }
    [Fact]
    public async Task Corrupt_configuration_reports_a_recoverable_warning()
    {
        var service = Service(Options()); Directory.CreateDirectory(Path.GetDirectoryName(service.ConfigurationFile)!);
        File.WriteAllText(service.ConfigurationFile, "not JSON"); service.Initialize();
        var snapshot = await service.InspectAsync(TestContext.Current.CancellationToken);
        Assert.NotNull(snapshot.ConfigurationWarning); Assert.False(snapshot.Configured);
    }
    [Theory]
    [InlineData("python.exe")]
    [InlineData(@"\\server\share\python.exe")]
    [InlineData(@"C:\tools\cmd.exe")]
    [InlineData("C:\\tools\\python.exe\n")]
    public void Unsafe_paths_are_rejected(string path) => Assert.Throws<ArgumentException>(() => SimulatorEnvironment.ValidatePaths(new(PythonPath: path), false));

    public void Dispose() { p.Dispose(); e.Dispose(); if (Directory.Exists(root)) Directory.Delete(root, true); }
}
