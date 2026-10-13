using System.Diagnostics;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.EnvironmentSetup;

public sealed record EnvironmentPaths(string? PipesimHome = null, string? PythonPath = null,
    string? PipesimPtkPath = null, string? EclrunPath = null);
public sealed record EnvironmentChange(string Simulator, EnvironmentPaths Paths);
public sealed record EnvironmentCheck(string Status, string Message);
public sealed record EnvironmentSnapshot(string Host, bool Configured, string? ConfigurationWarning,
    string[] ConfiguredSimulators,
    EnvironmentPaths Paths, Dictionary<string, string[]> Candidates,
    EnvironmentCheck Pipesim, EnvironmentCheck Eclipse, string LicenseNotice);

// Paths belong to the execution host. This file is never stored in the repository.
public sealed class SimulatorEnvironment
{
    private readonly WorkerOptions options;
    private readonly PtkExecutionCoordinator pipesim;
    private readonly EclipseExecutionCoordinator eclipse;
    private readonly SemaphoreSlim gate = new(1);
    private readonly Func<string, IReadOnlyList<string>, CancellationToken, Task<(bool Ok, string Output)>> probe;
    private EnvironmentPaths saved = new();
    private string? warning;
    private (EnvironmentPaths Paths, EnvironmentCheck Check, DateTimeOffset Time)? cachedPipesim;
    public string ConfigurationFile => Path.Combine(options.StorageRoot, "configuration", "simulator-environment.json");

    public SimulatorEnvironment(IOptions<WorkerOptions> options, PtkExecutionCoordinator pipesim,
        EclipseExecutionCoordinator eclipse) : this(options.Value, pipesim, eclipse, ProbeAsync) { }

    internal SimulatorEnvironment(WorkerOptions options, PtkExecutionCoordinator pipesim,
        EclipseExecutionCoordinator eclipse,
        Func<string, IReadOnlyList<string>, CancellationToken, Task<(bool Ok, string Output)>> probe)
        => (this.options, this.pipesim, this.eclipse, this.probe) = (options, pipesim, eclipse, probe);

    public void Initialize()
    {
        if (!File.Exists(ConfigurationFile)) return;
        try
        {
            saved = JsonSerializer.Deserialize<EnvironmentPaths>(File.ReadAllText(ConfigurationFile))
                ?? throw new InvalidDataException();
            ValidatePaths(saved, false);
            Apply(saved);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException or ArgumentException or InvalidDataException)
        {
            saved = new();
            warning = "本机环境配置无法读取，请重新配置；安装文件及项目数据未修改。";
        }
    }

    public async Task<EnvironmentSnapshot> InspectAsync(CancellationToken token)
    {
        await gate.WaitAsync(token);
        try { cachedPipesim = null; return await SnapshotAsync(token); }
        finally { gate.Release(); }
    }

    public async Task<EnvironmentSnapshot> SaveAsync(EnvironmentChange change, CancellationToken token)
    {
        if (change.Paths is null || change.Simulator is not ("pipesim" or "eclipse"))
            throw new ArgumentException("请选择 PIPESIM 或 ECLIPSE。");
        await gate.WaitAsync(token);
        try
        {
            // Reserve both engines, not just an IsBusy check: no run can start during a change.
            var p = pipesim.TryAcquire("environment-configuration");
            using var pLease = p.Lease;
            if (!p.Acquired) throw new InvalidOperationException("计算或验证正在进行，请结束后再配置环境。");
            var e = eclipse.TryAcquire("environment-configuration");
            using var eLease = e.Lease;
            if (!e.Acquired) throw new InvalidOperationException("计算或验证正在进行，请结束后再配置环境。");

            var next = change.Simulator == "pipesim"
                ? saved with { PipesimHome = Clean(change.Paths.PipesimHome), PythonPath = Clean(change.Paths.PythonPath), PipesimPtkPath = Clean(change.Paths.PipesimPtkPath) }
                : saved with { EclrunPath = Clean(change.Paths.EclrunPath) };
            ValidatePaths(change.Simulator == "pipesim"
                ? new(next.PipesimHome, next.PythonPath, next.PipesimPtkPath)
                : new(EclrunPath: next.EclrunPath), true);
            if (change.Simulator == "pipesim" && (next.PipesimHome is null || next.PythonPath is null || next.PipesimPtkPath is null)
                || change.Simulator == "eclipse" && next.EclrunPath is null)
                throw new ArgumentException("请完整填写所选软件的路径。另一软件可以暂不配置。");
            var check = change.Simulator == "pipesim"
                ? await CheckPipesimAsync(next, token) : await CheckEclipseAsync(next.EclrunPath, token);
            if (check.Status != "READY") throw new ArgumentException(check.Message);

            var directory = Path.GetDirectoryName(ConfigurationFile)!;
            Directory.CreateDirectory(directory);
            var temporary = Path.Combine(directory, $"environment-{Guid.NewGuid():N}.tmp");
            try
            {
                await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(next), token);
                File.Move(temporary, ConfigurationFile, true);
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
            saved = next;
            warning = null;
            Apply(next);
            cachedPipesim = null;
            return await SnapshotAsync(token);
        }
        finally { gate.Release(); }
    }

    private async Task<EnvironmentSnapshot> SnapshotAsync(CancellationToken token)
    {
        var paths = new EnvironmentPaths(options.PipesimHome, options.EffectivePythonPath,
            options.EffectivePipesimPtkPath, ResolveLauncher(options.EclrunPath));
        var candidates = Discover(paths);
        var p = await CachedPipesimAsync(paths, token);
        var e = await CheckEclipseAsync(paths.EclrunPath, token);
        return new(System.Environment.MachineName, saved.PipesimHome is not null || saved.EclrunPath is not null,
            warning, new[] { saved.PipesimHome is not null ? "pipesim" : null, saved.EclrunPath is not null ? "eclipse" : null }.OfType<string>().ToArray(), paths, candidates, p, e,
            "检测只确认安装、Toolkit 导入和支持版本；许可证、模型兼容性及计算成功需实际运行验证。当前支持 PIPESIM 2022.1、ECLIPSE 2024.1。");
    }

    public async Task<EnvironmentCheck> CheckCurrentPipesimAsync(CancellationToken token)
    {
        await gate.WaitAsync(token);
        try { return await CachedPipesimAsync(new(options.PipesimHome, options.EffectivePythonPath, options.EffectivePipesimPtkPath), token); }
        finally { gate.Release(); }
    }

    private async Task<EnvironmentCheck> CachedPipesimAsync(EnvironmentPaths paths, CancellationToken token)
    {
        var key = paths with { EclrunPath = null };
        if (cachedPipesim is { } cached && cached.Paths == key && DateTimeOffset.UtcNow - cached.Time < TimeSpan.FromSeconds(30)) return cached.Check;
        var check = await CheckPipesimAsync(paths, token);
        cachedPipesim = (key, check, DateTimeOffset.UtcNow);
        return check;
    }

    private void Apply(EnvironmentPaths paths)
    {
        if (paths.PipesimHome is not null) options.PipesimHome = paths.PipesimHome;
        if (paths.PythonPath is not null) options.PythonPath = paths.PythonPath;
        if (paths.PipesimPtkPath is not null) options.PipesimPtkPath = paths.PipesimPtkPath;
        if (paths.EclrunPath is not null) options.EclrunPath = paths.EclrunPath;
    }

    private static string? Clean(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();

    internal static void ValidatePaths(EnvironmentPaths paths, bool requireExists)
    {
        foreach (var value in new[] { paths.PipesimHome, paths.PythonPath, paths.PipesimPtkPath, paths.EclrunPath })
            if (value is not null && (value.Length > 1024 || !Path.IsPathFullyQualified(value)
                || value.StartsWith(@"\\") || value.Any(char.IsControl)))
                throw new ArgumentException("路径必须是计算主机上的本地绝对路径，不支持网络共享或命令。");
        if (paths.PythonPath is not null && !Path.GetFileName(paths.PythonPath).Equals("python.exe", StringComparison.OrdinalIgnoreCase)
            || paths.EclrunPath is not null && !Path.GetFileName(paths.EclrunPath).Equals("eclrun.exe", StringComparison.OrdinalIgnoreCase)
            || paths.PipesimPtkPath is not null && !Path.GetFileName(paths.PipesimPtkPath).Equals("PythonToolkitModules.zip", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("请选择 python.exe、eclrun.exe 或 PythonToolkitModules.zip，而不是命令或其他程序。");
        if (!requireExists) return;
        if (paths.PipesimHome is not null && !Directory.Exists(paths.PipesimHome)
            || paths.PythonPath is not null && !File.Exists(paths.PythonPath)
            || paths.PipesimPtkPath is not null && !File.Exists(paths.PipesimPtkPath)
            || paths.EclrunPath is not null && !File.Exists(paths.EclrunPath))
            throw new ArgumentException("填写的路径不存在或当前计算服务无权访问。");
    }

    private async Task<EnvironmentCheck> CheckPipesimAsync(EnvironmentPaths paths, CancellationToken token)
    {
        if (!Directory.Exists(paths.PipesimHome)) return new("MISSING", "未找到 PIPESIM 安装目录。");
        // Do not silently claim compatibility with other installed releases.
        if (!SupportedPipesimInstallation(paths.PipesimHome!))
            return new("UNSUPPORTED", "当前适配 PIPESIM 2022.1，请选择该版本的安装目录。");
        if (!File.Exists(paths.PipesimPtkPath)) return new("MISSING", "未找到 Python Toolkit 压缩包。");
        if (!File.Exists(paths.PythonPath)) return new("MISSING", "未找到 Python 解释器。");
        if (paths.PythonPath!.Contains(@"\Microsoft\WindowsApps\", StringComparison.OrdinalIgnoreCase))
            return new("FAILED", "请选择实际安装的 Python，而不是 Windows 应用执行别名。");
        var script = "import sys; sys.path.insert(0, sys.argv[1]); from sixgill.pipesim import Model; from sixgill.definitions import Parameters; print('GRDP_PTK_OK')";
        var result = await probe(paths.PythonPath!, ["-I", "-c", script, paths.PipesimPtkPath!], token);
        return result.Ok && result.Output.Contains("GRDP_PTK_OK", StringComparison.Ordinal)
            ? new("READY", "Python Toolkit 可导入；单井和管网共用此配置。")
            : new("FAILED", "Toolkit 导入失败或超时，请检查 Python 版本及 PIPESIM 依赖。未保存新配置。");
    }

    private static bool SupportedPipesimInstallation(string home)
    {
        var desktop = Path.Combine(home, "Programs", "Slb.Production.Engineering.Desktop.exe");
        if (File.Exists(desktop))
        {
            try { return FileVersionInfo.GetVersionInfo(desktop).ProductVersion?.StartsWith("2022.1.", StringComparison.Ordinal) == true; }
            catch (Exception ex) when (ex is IOException or System.ComponentModel.Win32Exception) { return false; }
        }
        return Path.GetFileName(Path.TrimEndingDirectorySeparator(home)).Equals("PIPESIM2022.1", StringComparison.OrdinalIgnoreCase);
    }

    private async Task<EnvironmentCheck> CheckEclipseAsync(string? path, CancellationToken token)
    {
        if (!File.Exists(path)) return new("MISSING", "未找到 eclrun.exe 启动器。");
        var blackOil = probe(path!, ["--report-versions", "eclipse"], token);
        var compositional = probe(path!, ["--report-versions", "e300"], token);
        var results = await Task.WhenAll(blackOil, compositional);
        var solvers = results.Select((result, index) => result.Ok && EclipseRunRules.IsSupportedVersion(result.Output) ? (index == 0 ? "100" : "300") : null).OfType<string>().ToArray();
        return solvers.Length > 0
            ? new("READY", $"已检测到 ECLIPSE {string.Join(" / ", solvers)} 2024.1。")
            : new("UNSUPPORTED", "启动器不可运行或未报告 ECLIPSE 2024.1；当前适配不支持其他版本。");
    }

    private static string? ResolveLauncher(string? configured)
    {
        if (!string.IsNullOrWhiteSpace(configured)) return configured;
        return (System.Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator)
            .Select(directory => Path.Combine(directory.Trim('"'), "eclrun.exe")).FirstOrDefault(File.Exists);
    }

    private static Dictionary<string, string[]> Discover(EnvironmentPaths current)
    {
        var homes = new List<string>();
        var python = new List<string>();
        var launchers = new List<string>();
        Add(homes, current.PipesimHome, Directory.Exists);
        Add(python, current.PythonPath, File.Exists);
        Add(launchers, current.EclrunPath, File.Exists);
        foreach (var root in new[] { System.Environment.GetFolderPath(System.Environment.SpecialFolder.ProgramFiles),
                     System.Environment.GetFolderPath(System.Environment.SpecialFolder.ProgramFilesX86) })
        {
            foreach (var directory in Subdirectories(root, "Python*")) Add(python, Path.Combine(directory, "python.exe"), File.Exists);
            foreach (var vendor in new[] { "Schlumberger", "SLB" })
                foreach (var directory in Subdirectories(Path.Combine(root, vendor), "PIPESIM*")) Add(homes, directory, Directory.Exists);
        }
        foreach (var directory in (System.Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator).Where(dir => !dir.Contains(@"\Microsoft\WindowsApps", StringComparison.OrdinalIgnoreCase)))
            Add(python, Path.Combine(directory.Trim('"'), "python.exe"), File.Exists);
        if (OperatingSystem.IsWindows()) DiscoverRegistry(homes, python, launchers);
        foreach (var directory in Subdirectories(Path.Combine(System.Environment.GetFolderPath(System.Environment.SpecialFolder.LocalApplicationData), "Programs", "Python"), "Python*"))
            Add(python, Path.Combine(directory, "python.exe"), File.Exists);
        foreach (var drive in DriveInfo.GetDrives().Where(d => d.DriveType == DriveType.Fixed))
        {
            Add(launchers, Path.Combine(drive.RootDirectory.FullName, "ecl", "macros", "eclrun.exe"), File.Exists);
            foreach (var directory in Subdirectories(drive.RootDirectory.FullName, "Python*")) Add(python, Path.Combine(directory, "python.exe"), File.Exists);
        }
        var toolkits = homes.Select(home => Path.Combine(home, "Developer Tools", "Python Toolkit", "Modules", "PythonToolkitModules.zip")).ToList();
        Add(toolkits, current.PipesimPtkPath, File.Exists);
        return new() { ["pipesimHome"] = homes.Select(Path.TrimEndingDirectorySeparator).Where(home => File.Exists(Path.Combine(home, "Developer Tools", "Python Toolkit", "Modules", "PythonToolkitModules.zip"))).Distinct(StringComparer.OrdinalIgnoreCase).ToArray(),
            ["pythonPath"] = python.Distinct(StringComparer.OrdinalIgnoreCase).ToArray(),
            ["pipesimPtkPath"] = toolkits.Where(File.Exists).Distinct(StringComparer.OrdinalIgnoreCase).ToArray(),
            ["eclrunPath"] = launchers.Distinct(StringComparer.OrdinalIgnoreCase).ToArray() };
    }

    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    private static void DiscoverRegistry(List<string> homes, List<string> python, List<string> launchers)
    {
        foreach (var hive in new[] { Microsoft.Win32.RegistryHive.LocalMachine, Microsoft.Win32.RegistryHive.CurrentUser })
        foreach (var view in new[] { Microsoft.Win32.RegistryView.Registry64, Microsoft.Win32.RegistryView.Registry32 })
        {
            try
            {
                using var root = Microsoft.Win32.RegistryKey.OpenBaseKey(hive, view);
                using var uninstall = root.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall");
                foreach (var key in uninstall?.GetSubKeyNames() ?? [])
                {
                    using var entry = uninstall!.OpenSubKey(key);
                    var name = entry?.GetValue("DisplayName") as string ?? "";
                    var location = entry?.GetValue("InstallLocation") as string;
                    if (string.IsNullOrWhiteSpace(location) || !Path.IsPathFullyQualified(location) || location.StartsWith(@"\\")) continue;
                    if (name.Contains("PIPESIM", StringComparison.OrdinalIgnoreCase)) Add(homes, location, Directory.Exists);
                    if (name.Contains("Python", StringComparison.OrdinalIgnoreCase)) Add(python, Path.Combine(location, "python.exe"), File.Exists);
                    if (name.Contains("ECLIPSE", StringComparison.OrdinalIgnoreCase))
                    {
                        Add(launchers, Path.Combine(location, "macros", "eclrun.exe"), File.Exists);
                        var parent = Directory.GetParent(Path.TrimEndingDirectorySeparator(location))?.FullName;
                        if (parent is not null) Add(launchers, Path.Combine(parent, "macros", "eclrun.exe"), File.Exists);
                    }
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or System.Security.SecurityException or ArgumentException) { }
        }
    }

    private static IEnumerable<string> Subdirectories(string root, string pattern)
    {
        try { return Directory.Exists(root) ? Directory.GetDirectories(root, pattern).Take(32).ToArray() : []; }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return []; }
    }
    private static void Add(List<string> list, string? value, Func<string, bool> exists)
    { if (!string.IsNullOrWhiteSpace(value) && exists(value)) list.Add(value); }

    private static async Task<(bool, string)> ProbeAsync(string executable, IReadOnlyList<string> args, CancellationToken token)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(TimeSpan.FromSeconds(12));
        using var process = new Process { StartInfo = new(executable) {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true } };
        foreach (var arg in args) process.StartInfo.ArgumentList.Add(arg);
        // Never forward proprietary diagnostics to the browser. Cap retained probe output.
        var output = new System.Text.StringBuilder();
        process.OutputDataReceived += (_, e) => { lock (output) if (e.Data is not null && output.Length < 65536) output.AppendLine(e.Data); };
        process.ErrorDataReceived += (_, _) => { };
        try
        {
            process.Start();
            process.BeginOutputReadLine(); process.BeginErrorReadLine();
            await process.WaitForExitAsync(timeout.Token);
            lock (output) return (process.ExitCode == 0, output.ToString());
        }
        catch (OperationCanceledException)
        {
            if (!process.HasExited) process.Kill(true);
            await process.WaitForExitAsync(CancellationToken.None);
            token.ThrowIfCancellationRequested();
            return (false, "");
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or IOException or InvalidOperationException)
        { return (false, ""); }
    }
}
