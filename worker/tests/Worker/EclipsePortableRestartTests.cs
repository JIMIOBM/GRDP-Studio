using System.IO.Compression;
using System.Text;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipsePortableRestartTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-portable-restart", Guid.NewGuid().ToString("N"));
    public EclipsePortableRestartTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private async Task<(EclipseDeckPackage Package, byte[] State)> WritePortable(bool binary = false)
    {
        var work = Path.Combine(root, "work"); var output = Path.Combine(root, "output"); Directory.CreateDirectory(work); Directory.CreateDirectory(output);
        var main = Path.Combine(work, "FORE.DATA"); var grid = Path.Combine(work, "GRID.INC"); var statePath = Path.Combine(work, binary ? "CASE.UNRST" : "CASE.FUNRST");
        var text = "RUNSPEC\nDIMENS\n 3 1 1 /\nOIL\nWATER\nGAS\nFIELD\nSTART\n 1 'JAN' 1970 /\nFMTIN\nUNIFIN\nGRID\nINCLUDE\n 'GRID.INC' /\nPROPS\nDENSITY\n 50 60 0.01 /\nSOLUTION\nRESTART\n 'CASE' 80 /\nSUMMARY\nFOPT\nSCHEDULE\nDATES\n 31 'DEC' 1976 /\n/\nEND\n";
        if (binary) text = text.Replace("FMTIN\n", "");
        await File.WriteAllTextAsync(main, text, TestContext.Current.CancellationToken);
        await File.WriteAllTextAsync(grid, "PERMX\n 3*100 /\n", TestContext.Current.CancellationToken);
        var state = binary ? EclipseBinaryRestartTests.Report(80, 2190) : Encoding.ASCII.GetBytes(EclipseFormattedRestartTests.Report(80, 2190)); await File.WriteAllBytesAsync(statePath, state, TestContext.Current.CancellationToken);
        var package = new EclipseDeckPackageResolver().Resolve(main, work);
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));
        var descriptor = await store.WriteEclipseForecastPackageAsync(output, package, statePath, TestContext.Current.CancellationToken);
        Assert.Equal("application/zip", descriptor.ContentType);
        using (var zip = ZipFile.OpenRead(Path.Combine(output, "eclipse-forecast-applied-package.zip")))
        {
            Assert.Equal(new[] { binary ? "CASE.UNRST" : "CASE.FUNRST", "FORE.DATA", "GRID.INC", EclipsePortableRestartBinding.Filename }.Order(), zip.Entries.Select(e => e.FullName).Order());
            using var original = zip.GetEntry(binary ? "CASE.UNRST" : "CASE.FUNRST")!.Open(); using var memory = new MemoryStream(); await original.CopyToAsync(memory, TestContext.Current.CancellationToken);
            Assert.Equal(state, memory.ToArray());
        }
        var imported = Path.Combine(root, "reimport"); ZipFile.ExtractToDirectory(Path.Combine(output, "eclipse-forecast-applied-package.zip"), imported);
        return (new EclipseDeckPackageResolver().Resolve(Path.Combine(imported, "FORE.DATA"), imported), state);
    }
    [Fact]
    public async Task CompleteForecastPackageKeepsExactSelectedStateAndValidatesAfterReupload()
    {
        var (package, state) = await WritePortable();
        var restart = EclipsePortableRestartBinding.Validate(package); Assert.NotNull(restart);
        Assert.Equal(state, File.ReadAllBytes(restart));
        Assert.DoesNotContain(root, File.ReadAllText(Path.Combine(package.Root, EclipsePortableRestartBinding.Filename)));
    }
    [Theory]
    [InlineData("grid")]
    [InlineData("state")]
    [InlineData("report")]
    [InlineData("time")]
    [InlineData("date")]
    [InlineData("extra")]
    [InlineData("duplicate")]
    [InlineData("missingState")]
    public async Task DamagedPortableSourcesRejectBeforeRunningSolver(string fault)
    {
        var (package, _) = await WritePortable(); var binding = Path.Combine(package.Root, EclipsePortableRestartBinding.Filename);
        if (fault == "grid") File.WriteAllText(Path.Combine(package.Root, "GRID.INC"), "PERMX\n 3*101 /\n");
        else if (fault == "state") File.AppendAllText(Path.Combine(package.Root, "CASE.FUNRST"), "unexpected");
        else if (fault == "missingState") File.Delete(Path.Combine(package.Root, "CASE.FUNRST"));
        else if (fault == "duplicate") File.WriteAllText(binding, File.ReadAllText(binding).Replace("\"physicsSha256\":", "\"physicsSha256\": \"bad\", \"physicsSha256\":"));
        else
        {
            var node = JsonNode.Parse(File.ReadAllText(binding))!; var report = node["catalog"]!["reports"]![0]!;
            switch (fault) { case "report": report["reportNumber"] = 81; break; case "time": report["timeDays"] = 2191; break;
                case "date": report["date"] = "1975-12-30"; break; default: node["extra"] = true; break; }
            File.WriteAllText(binding, node.ToJsonString());
        }
        Assert.Throws<InvalidDataException>(() => EclipsePortableRestartBinding.Validate(package));
    }
    [Fact]
    public async Task BinaryPortableUsesExactRawStateAndStrictSecondVersionBinding()
    {
        var (package, state) = await WritePortable(true);
        var path = EclipsePortableRestartBinding.Validate(package); Assert.NotNull(path); Assert.EndsWith(".UNRST", path);
        Assert.Equal(state, File.ReadAllBytes(path));
        var bindingPath = Path.Combine(package.Root, EclipsePortableRestartBinding.Filename);
        var original = File.ReadAllText(bindingPath); var binding = JsonNode.Parse(original)!;
        Assert.Equal("eclipse-portable-restart/2", binding["schemaVersion"]!.GetValue<string>());
        Assert.Equal("eclipse-restart-catalog/2", binding["catalog"]!["schemaVersion"]!.GetValue<string>());
        binding["schemaVersion"] = "eclipse-portable-restart/1"; File.WriteAllText(bindingPath, binding.ToJsonString());
        Assert.Throws<InvalidDataException>(() => EclipsePortableRestartBinding.Validate(package));
        File.WriteAllText(bindingPath, original);
        File.WriteAllText(package.MainFile, File.ReadAllText(package.MainFile).Replace("UNIFIN", "FMTIN\nUNIFIN"));
        Assert.Throws<InvalidDataException>(() => EclipsePortableRestartBinding.Validate(package));
    }
    [Theory]
    [InlineData("state")] [InlineData("missingState")] [InlineData("physics")] [InlineData("report")] [InlineData("catalog")] [InlineData("extra")]
    public async Task BinaryPortableTamperingCannotRun(string fault)
    {
        var (package, _) = await WritePortable(true); var bindingPath = Path.Combine(package.Root, EclipsePortableRestartBinding.Filename);
        var state = Path.Combine(package.Root, "CASE.UNRST");
        if (fault == "state") { var bytes = File.ReadAllBytes(state); bytes[^1] ^= 1; File.WriteAllBytes(state, bytes); }
        else if (fault == "missingState") File.Delete(state);
        else if (fault == "physics") File.AppendAllText(Path.Combine(package.Root, "GRID.INC"), "PORO\n 3*0.2 /\n");
        else {
            var binding = JsonNode.Parse(File.ReadAllText(bindingPath))!;
            if (fault == "report") binding["catalog"]!["reports"]![0]!["timeDays"] = 2191;
            else if (fault == "catalog") binding["catalog"]!["schemaVersion"] = "eclipse-restart-catalog/1";
            else binding["extra"] = true;
            File.WriteAllText(bindingPath, binding.ToJsonString());
        }
        Assert.Throws<InvalidDataException>(() => EclipsePortableRestartBinding.Validate(package));
    }
}
