using System.Diagnostics;
using System.IO.Compression;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class RequestAndProcessCleanupTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-worker-request-tests", Guid.NewGuid().ToString("N"));

    public RequestAndProcessCleanupTests() => Directory.CreateDirectory(root);

    [Fact]
    public async Task WorkerRejectsNonNullParametersBeforeSimulatorAccess()
    {
        using var document = JsonDocument.Parse("{}");
        var options = Options.Create(new WorkerOptions { StorageRoot = root });
        var storage = new StorageResolver(options);
        using var coordinator = new PtkExecutionCoordinator();
        var identity = new WorkerIdentity(options);
        var registry = new PtkRunRegistry(identity);
        var runner = new PtkProcessRunner(options);
        using var service = new PtkRunService(storage, coordinator, registry, runner, options);
        var request = new RunExecuteRequest(1, "models/well.pips", new string('a', 64), "Study 1", "nodal", document.RootElement.Clone(), 600);

        var result = await service.SubmitAsync(request, CancellationToken.None);
        Assert.Equal(400, result.HttpStatus);
        Assert.Equal("PARAMETERS_NOT_NULL", Assert.IsType<WorkerError>(result.Body).Code);
    }

    [Fact]
    public async Task WorkerRejectsSourceHashMismatchBeforeSimulatorAccess()
    {
        var modelDirectory = Path.Combine(root, "models");
        Directory.CreateDirectory(modelDirectory);
        await File.WriteAllTextAsync(Path.Combine(modelDirectory, "well.pips"), "model", TestContext.Current.CancellationToken);
        using var document = JsonDocument.Parse("null");
        var options = Options.Create(new WorkerOptions { StorageRoot = root });
        var storage = new StorageResolver(options);
        using var coordinator = new PtkExecutionCoordinator();
        var identity = new WorkerIdentity(options);
        var registry = new PtkRunRegistry(identity);
        var runner = new PtkProcessRunner(options);
        using var service = new PtkRunService(storage, coordinator, registry, runner, options);
        var request = new RunExecuteRequest(2, "models/well.pips", new string('0', 64), "Study 1", "nodal", document.RootElement.Clone(), 600);

        var result = await service.SubmitAsync(request, TestContext.Current.CancellationToken);
        Assert.Equal(422, result.HttpStatus);
        Assert.Equal("MODEL_SHA256_MISMATCH", Assert.IsType<WorkerError>(result.Body).Code);
    }

    [Theory]
    [InlineData("cancel")]
    [InlineData("timeout")]
    public async Task JobTerminateConfirmsDescendantCleanupForCancelAndTimeoutPaths(string reason)
    {
        var childPidPath = Path.Combine(root, reason + "-child.pid");
        using var process = StartPythonTree(childPidPath, rootLifetimeSeconds: 60);
        using var job = WindowsJobObject.CreateAndAssign(process);
        process.StandardInput.WriteLine("start");
        process.StandardInput.Flush();
        await WaitForFileAsync(childPidPath);
        Assert.True(File.Exists(childPidPath));
        var childPid = int.Parse(await File.ReadAllTextAsync(childPidPath, TestContext.Current.CancellationToken));

        var cleanup = await job.TerminateAndConfirmAsync(TimeSpan.FromSeconds(10));
        Assert.True(cleanup.Confirmed);
        Assert.True(cleanup.TerminateUsed);
        Assert.True(process.HasExited);
        Assert.False(IsRunning(childPid));
    }

    [Fact]
    public async Task JobTerminateCatchesChildWhenRootSpawnsAndImmediatelyExits()
    {
        var childPidPath = Path.Combine(root, "normal-exit-child.pid");
        using var process = StartPythonTree(childPidPath, rootLifetimeSeconds: 0);
        using var job = WindowsJobObject.CreateAndAssign(process);
        process.StandardInput.WriteLine("start");
        process.StandardInput.Flush();
        await WaitForFileAsync(childPidPath);
        await process.WaitForExitAsync(TestContext.Current.CancellationToken);
        Assert.True(File.Exists(childPidPath));
        var childPid = int.Parse(await File.ReadAllTextAsync(childPidPath, TestContext.Current.CancellationToken));

        var cleanup = await job.ConfirmNormalExitAsync(process, TimeSpan.FromSeconds(10));
        Assert.True(cleanup.Confirmed);
        Assert.True(cleanup.TerminateUsed);
        Assert.False(IsRunning(childPid));
    }

    [Fact]
    public async Task ClosingJobKillsChildWhenRootSpawnsAndImmediatelyExits()
    {
        var childPidPath = Path.Combine(root, "job-close-child.pid");
        using var process = StartPythonTree(childPidPath, rootLifetimeSeconds: 0);
        var job = WindowsJobObject.CreateAndAssign(process);
        process.StandardInput.WriteLine("start");
        process.StandardInput.Flush();
        await WaitForFileAsync(childPidPath);
        await process.WaitForExitAsync(TestContext.Current.CancellationToken);
        var childPid = int.Parse(await File.ReadAllTextAsync(childPidPath, TestContext.Current.CancellationToken));

        job.Dispose();
        var deadline = DateTimeOffset.UtcNow.AddSeconds(10);
        while (IsRunning(childPid) && DateTimeOffset.UtcNow < deadline)
        {
            await Task.Delay(25, TestContext.Current.CancellationToken);
        }
        Assert.False(IsRunning(childPid));
    }

    [Fact]
    public async Task JobDoesNotAllowAssignedRootToSpawnBreakawayChild()
    {
        var resultPath = Path.Combine(root, "breakaway-result.txt");
        using var process = StartBreakawayProbe(resultPath);
        using var job = WindowsJobObject.CreateAndAssign(process);
        process.StandardInput.WriteLine("start");
        process.StandardInput.Flush();
        await WaitForFileAsync(resultPath);
        await process.WaitForExitAsync(TestContext.Current.CancellationToken);

        var result = await File.ReadAllTextAsync(resultPath, TestContext.Current.CancellationToken);
        if (result.StartsWith("escaped:", StringComparison.Ordinal) && int.TryParse(result[8..], out var escapedPid))
        {
            using var escaped = Process.GetProcessById(escapedPid);
            escaped.Kill(entireProcessTree: true);
            await escaped.WaitForExitAsync(TestContext.Current.CancellationToken);
        }
        Assert.Equal("blocked", result);
        var cleanup = await job.ConfirmNormalExitAsync(process, TimeSpan.FromSeconds(10));
        Assert.True(cleanup.Confirmed);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task PtkRunnerStartsModelOpenWithoutStdinReaderAndExternallyKillsProcessTree(bool cancel)
    {
        var zipPath = Path.Combine(root, cancel ? "cancel-ptk.zip" : "timeout-ptk.zip");
        var childPidPath = Path.Combine(root, cancel ? "cancel-adapter-child.pid" : "timeout-adapter-child.pid");
        var threadSnapshotPath = Path.Combine(root, cancel ? "cancel-adapter-threads.json" : "timeout-adapter-threads.json");
        CreateBlockingFakePtk(zipPath, childPidPath, threadSnapshotPath);
        var modelPath = Path.Combine(root, "fake.pips");
        await File.WriteAllTextAsync(modelPath, "fake", TestContext.Current.CancellationToken);
        var requestPath = Path.Combine(root, cancel ? "cancel-request.json" : "timeout-request.json");
        await File.WriteAllTextAsync(requestPath, JsonSerializer.Serialize(new
        {
            modelPath,
            study = "Study 1",
            runTask = "nodal",
            parameters = (object?)null
        }), TestContext.Current.CancellationToken);

        var options = Options.Create(new WorkerOptions
        {
            StorageRoot = root,
            PipesimPtkPath = zipPath,
            GracefulStopSeconds = 1,
            ProcessExitConfirmationSeconds = 10
        });
        var runner = new PtkProcessRunner(options);
        using var cancellation = new CancellationTokenSource();
        if (cancel) cancellation.CancelAfter(TimeSpan.FromSeconds(1));
        var result = await runner.RunAsync(
            requestPath,
            cancel ? TimeSpan.FromSeconds(30) : TimeSpan.FromSeconds(1),
            cancellation.Token,
            (_, _) => { });

        Assert.Equal(cancel ? AdapterStopReason.Cancelled : AdapterStopReason.TimedOut, result.StopReason);
        Assert.True(result.GracefulStopRequested);
        Assert.True(result.KillUsed);
        Assert.True(result.ProcessTreeExitConfirmed);
        Assert.True(File.Exists(childPidPath));
        Assert.False(IsRunning(int.Parse(await File.ReadAllTextAsync(childPidPath, TestContext.Current.CancellationToken))));
        var adapterThreads = JsonSerializer.Deserialize<string[]>(
            await File.ReadAllTextAsync(threadSnapshotPath, TestContext.Current.CancellationToken))
            ?? throw new InvalidOperationException("The adapter thread snapshot is invalid.");
        Assert.Equal(["MainThread"], adapterThreads);
    }

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }

    private static bool IsRunning(int processId)
    {
        try
        {
            using var process = Process.GetProcessById(processId);
            return !process.HasExited;
        }
        catch (ArgumentException)
        {
            return false;
        }
    }

    private static void CreateBlockingFakePtk(string zipPath, string childPidPath, string threadSnapshotPath)
    {
        using var archive = ZipFile.Open(zipPath, ZipArchiveMode.Create);
        var init = archive.CreateEntry("sixgill/__init__.py");
        using (var writer = new StreamWriter(init.Open())) writer.Write(string.Empty);
        var pipesim = archive.CreateEntry("sixgill/pipesim.py");
        using var source = new StreamWriter(pipesim.Open());
        var escaped = childPidPath.Replace("\\", "\\\\").Replace("'", "\\'");
        var escapedThreadSnapshot = threadSnapshotPath.Replace("\\", "\\\\").Replace("'", "\\'");
        source.Write(
            "import json, subprocess, sys, threading, time\n" +
            "class Model:\n" +
            "    @staticmethod\n" +
            "    def open(path):\n" +
            $"        open('{escapedThreadSnapshot}', 'w').write(json.dumps([thread.name for thread in threading.enumerate()]))\n" +
            "        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], creationflags=subprocess.CREATE_NO_WINDOW)\n" +
            $"        open('{escaped}', 'w').write(str(child.pid))\n" +
            "        time.sleep(60)\n");
    }

    private Process StartPythonTree(string childPidPath, int rootLifetimeSeconds)
    {
        var scriptPath = Path.Combine(root, Guid.NewGuid().ToString("N") + "-tree.py");
        var escaped = childPidPath.Replace("\\", "\\\\").Replace("'", "\\'");
        File.WriteAllText(
            scriptPath,
            "import subprocess, sys, time\n" +
            "sys.stdin.readline()\n" +
            "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], creationflags=subprocess.CREATE_NO_WINDOW)\n" +
            $"open('{escaped}', 'w').write(str(child.pid))\n" +
            $"time.sleep({rootLifetimeSeconds})\n");
        var python = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Programs",
            "Python",
            "Python39",
            "python.exe");
        return Process.Start(new ProcessStartInfo(python)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            ArgumentList = { "-u", scriptPath }
        })!;
    }

    private Process StartBreakawayProbe(string resultPath)
    {
        var scriptPath = Path.Combine(root, Guid.NewGuid().ToString("N") + "-breakaway.py");
        var escaped = resultPath.Replace("\\", "\\\\").Replace("'", "\\'");
        File.WriteAllText(
            scriptPath,
            "import subprocess, sys, time\n" +
            "sys.stdin.readline()\n" +
            "try:\n" +
            "    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], creationflags=subprocess.CREATE_BREAKAWAY_FROM_JOB)\n" +
            $"    open('{escaped}', 'w').write('escaped:' + str(child.pid))\n" +
            "except OSError:\n" +
            $"    open('{escaped}', 'w').write('blocked')\n");
        var python = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Programs",
            "Python",
            "Python39",
            "python.exe");
        return Process.Start(new ProcessStartInfo(python)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            ArgumentList = { "-u", scriptPath }
        })!;
    }

    private static async Task WaitForFileAsync(string path)
    {
        var deadline = DateTimeOffset.UtcNow.AddSeconds(10);
        while (!File.Exists(path) && DateTimeOffset.UtcNow < deadline)
        {
            await Task.Delay(25, TestContext.Current.CancellationToken);
        }
    }
}
