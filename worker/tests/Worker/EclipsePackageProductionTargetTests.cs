using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.IO.Compression;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipsePackageProductionTargetTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-include-target", Guid.NewGuid().ToString("N"));
    public EclipsePackageProductionTargetTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Main = "RUNSPEC\r\nFIELD\r\nSCHEDULE\r\nWELSPECS\r\n 'P1' 'G' 1 1 0 'OIL' /\r\n/\r\nINCLUDE\r\n 'INITIAL.INC' /\r\nTSTEP\r\n 10 /\r\nWCONPROD\r\n 'P1' 'OPEN' 'ORAT' 30000 4* 2500 /\r\n/\r\nEND\r\n";
    private const string Wrapper = "-- nested initial controls\r\nINCLUDE 'PRODUCERS.INC' /\r\n";
    private const string Leaf = "WCONPROD\r\n-- original pressure limit\r\n 'P1' 'OPEN' 'ORAT' 20000 4* 2500 /\r\n/\r\n";

    private EclipseDeckPackage Package(string main = Main, string wrapper = Wrapper, string leaf = Leaf)
    {
        foreach (var (name, content) in new[] { ("MODEL.DATA", main), ("INITIAL.INC", wrapper), ("PRODUCERS.INC", leaf) })
            File.WriteAllBytes(Path.Combine(root, name), Encoding.UTF8.GetBytes(content));
        return new EclipseDeckPackageResolver().Resolve(Path.Combine(root, "MODEL.DATA"), root);
    }

    [Fact]
    public void NestedIncludeReturnsToParentBeforeItsTimeStepAndLaterOverride()
    {
        var package = Package();
        var before = package.Files.ToDictionary(path => Path.GetFileName(path)!, File.ReadAllBytes);
        var snapshot = EclipsePackageProductionTargetReader.Read(package);
        var target = Assert.Single(Assert.IsType<EclipsePackageProductionTargetReader.Snapshot>(snapshot).Targets);
        Assert.Equal("P1", target.Well);
        Assert.Equal("PRODUCERS.INC", target.SourceFile);
        Assert.Equal(3, target.LineNumber);
        Assert.Equal(20000, target.OriginalOilRate);
        Assert.Equal("20000", Leaf.Substring(target.ValueStart, target.ValueLength));
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(before[target.SourceFile])), target.SourceSha256);
        foreach (var path in package.Files) Assert.Equal(before[Path.GetFileName(path)], File.ReadAllBytes(path));
    }

    [Fact]
    public void TimeStepInsideIncludePreventsLaterParentControlBeingOfferedAsInitial()
    {
        var main = Main.Replace("TSTEP\r\n 10 /\r\n", "", StringComparison.Ordinal);
        Assert.Null(EclipsePackageProductionTargetReader.Read(Package(main, Wrapper, "TSTEP\n 10 /\n" + Leaf)));
    }

    [Fact]
    public void ParentControlBeforeNestedTimeStepRetainsItsPhysicalMainFileOffset()
    {
        var main = Main.Replace("INCLUDE\r\n", Leaf + "INCLUDE\r\n", StringComparison.Ordinal);
        var snapshot = EclipsePackageProductionTargetReader.Read(Package(main, Wrapper, "TSTEP\n 10 /\n"));
        var target = Assert.Single(Assert.IsType<EclipsePackageProductionTargetReader.Snapshot>(snapshot).Targets);
        Assert.Equal("MODEL.DATA", target.SourceFile);
        Assert.Equal("20000", main.Substring(target.ValueStart, target.ValueLength));
    }

    [Theory]
    [InlineData("\n")]
    [InlineData("\r\n")]
    [InlineData("\r\r\n")]
    public void PhysicalLineAndNumericTokenRemainExactWithDifferentLineEndings(string newline)
    {
        var leaf = Leaf.Replace("\r\n", newline, StringComparison.Ordinal);
        var snapshot = EclipsePackageProductionTargetReader.Read(Package(leaf: leaf));
        var target = Assert.Single(Assert.IsType<EclipsePackageProductionTargetReader.Snapshot>(snapshot).Targets);
        Assert.Equal(newline == "\r\r\n" ? 5 : 3, target.LineNumber);
        Assert.Equal("20000", leaf.Substring(target.ValueStart, target.ValueLength));
    }

    [Theory]
    [InlineData("reused")]
    [InlineData("override")]
    [InlineData("wildcard")]
    [InlineData("group")]
    [InlineData("default")]
    [InlineData("endinc")]
    [InlineData("paths")]
    [InlineData("action")]
    [InlineData("unknown-well")]
    public void AmbiguousOrUnsupportedControlsAreNotOffered(string kind)
    {
        var main = kind switch {
            "group" => Main.Replace("SCHEDULE\r\n", "SCHEDULE\r\nGCONPROD\r\n 'G' 'ORAT' 10000 /\r\n/\r\n"),
            "paths" => "PATHS\n 'X' 'Y' /\n/\n" + Main,
            "action" => Main.Replace("SCHEDULE\r\n", "SCHEDULE\r\nACTIONX\r\n"),
            _ => Main
        };
        var leaf = kind switch {
            "reused" => Leaf + "INCLUDE 'PRODUCERS.INC' /\n",
            "override" => Leaf + Leaf,
            "wildcard" => Leaf.Replace("'P1'", "'P*'"),
            "default" => Leaf.Replace("20000", "1*"),
            "endinc" => Leaf + "ENDINC\n",
            "unknown-well" => Leaf.Replace("'P1'", "'P2'"),
            _ => Leaf
        };
        if (kind == "reused")
        {
            var package = Package();
            File.AppendAllText(package.MainFile, "INCLUDE 'PRODUCERS.INC' /\n");
            Assert.Null(EclipsePackageProductionTargetReader.Read(package));
        }
        else Assert.Null(EclipsePackageProductionTargetReader.Read(Package(main, Wrapper, leaf)));
    }

    [Fact]
    public void NestedSubdirectoryPathsMapToTheActualLeaf()
    {
        Directory.CreateDirectory(Path.Combine(root, "controls"));
        File.WriteAllText(Path.Combine(root, "MODEL.DATA"), Main.Replace("'INITIAL.INC'", "'controls/INITIAL.INC'"));
        File.WriteAllText(Path.Combine(root, "controls/INITIAL.INC"), Wrapper.Replace("'PRODUCERS.INC'", "'controls/PRODUCERS.INC'", StringComparison.Ordinal));
        File.WriteAllText(Path.Combine(root, "controls/PRODUCERS.INC"), Leaf);
        var package = new EclipseDeckPackageResolver().Resolve(Path.Combine(root, "MODEL.DATA"), root);
        var target = Assert.Single(Assert.IsType<EclipsePackageProductionTargetReader.Snapshot>(EclipsePackageProductionTargetReader.Read(package)).Targets);
        Assert.Equal("controls/PRODUCERS.INC", target.SourceFile);
    }

    [Fact]
    public async Task OfficialNestedPackageInspectionAndEditMatchIndependentNativeBytes()
    {
        ZipFile.ExtractToDirectory(Path.Combine(AppContext.BaseDirectory, "Fixtures/ECLIPSE_INCLUDE_PRODUCTION.zip"), root);
        var package = new EclipseDeckPackageResolver().Resolve(Path.Combine(root, "MODEL.DATA"), root);
        var before = package.Files.ToDictionary(path => Path.GetRelativePath(root, path), File.ReadAllBytes);
        var inspection = EclipseDataInspector.InspectPackage(package.Files, "MODEL.DATA") with { PackageFiles = package.Files.OrderBy(p => Path.GetRelativePath(root, p), StringComparer.OrdinalIgnoreCase)
            .Select(p => new EclipsePackageFile(Path.GetRelativePath(root, p).Replace(Path.DirectorySeparatorChar, '/'), new FileInfo(p).Length, Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(p))))).ToArray() };
        var context = Assert.IsType<EclipsePackageProductionTargetContext>(inspection.PackageProductionTargetContext);
        Assert.Equal(4, context.Targets.Count);
        var target = Assert.Single(context.Targets, t => t.Well == "PRODUCR1");
        Assert.Contains(inspection.ScheduleMetadata!.Records, r => r.Keyword == "WCONPROD" && r.SourceFile == target.SourceFile && r.LineNumber == target.LineNumber);
        var capture = Environment.GetEnvironmentVariable("GRDP_INCLUDE_PRODUCTION_CAPTURE");
        if (!string.IsNullOrEmpty(capture)) await File.WriteAllTextAsync(capture, JsonSerializer.Serialize(inspection, new JsonSerializerOptions(JsonSerializerDefaults.Web)), TestContext.Current.CancellationToken);
        var request = JsonSerializer.SerializeToElement(new { schemaVersion = EclipseProductionTargetParameters.PackageSchema,
            baselineRunId = 1, well = target.Well, phase = "INITIAL", sourceFile = target.SourceFile, sourceSha256 = target.SourceSha256,
            lineNumber = target.LineNumber, unit = "STB/day", originalOilRate = target.OriginalOilRate, targetOilRate = 10000 });
        Assert.True(EclipseProductionTargetParameters.TryParse(request, out var parameters));
        await EclipseProductionTargetEditor.ApplyAsync(package, parameters!, TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures/INCLUDE-PRODUCTION-TARGET.INC")), File.ReadAllBytes(Path.Combine(root, target.SourceFile)));
        foreach (var (name, bytes) in before) if (name != target.SourceFile) Assert.Equal(bytes, File.ReadAllBytes(Path.Combine(root, name)));
    }

    [Theory]
    [InlineData("hash")]
    [InlineData("line")]
    [InlineData("source")]
    [InlineData("original")]
    [InlineData("negative")]
    public async Task StalePackageTargetsFailBeforeWritingAnySource(string kind)
    {
        var package = Package();
        var target = EclipsePackageProductionTargetReader.Read(package)!.Targets[0];
        var parameters = new EclipseProductionTargetParameters(1, target.Well, target.SourceFile, target.LineNumber, target.OriginalOilRate, 10000, target.SourceSha256);
        parameters = kind switch {
            "hash" => parameters with { SourceSha256 = new string('0', 64) },
            "line" => parameters with { LineNumber = target.LineNumber + 1 },
            "source" => parameters with { SourceFile = "INITIAL.INC" },
            "original" => parameters with { OriginalOilRate = 19999 },
            _ => parameters with { TargetOilRate = -1 }
        };
        var before = package.Files.ToDictionary(path => path, File.ReadAllBytes);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseProductionTargetEditor.ApplyAsync(package, parameters, TestContext.Current.CancellationToken));
        foreach (var (path, bytes) in before) Assert.Equal(bytes, File.ReadAllBytes(path));
    }

    [Fact]
    public async Task Utf8BomAndNonAsciiCommentsRemainByteExactDuringLeafEdit()
    {
        var leaf = "\ufeff" + Leaf.Replace("original pressure limit", "保留井底压力限制", StringComparison.Ordinal);
        var package = Package(leaf: leaf);
        var t = EclipsePackageProductionTargetReader.Read(package)!.Targets[0];
        await EclipseProductionTargetEditor.ApplyAsync(package, new(1, t.Well, t.SourceFile, t.LineNumber, t.OriginalOilRate, 10000, t.SourceSha256), TestContext.Current.CancellationToken);
        Assert.Equal(Encoding.UTF8.GetBytes(leaf.Replace("20000", "10000", StringComparison.Ordinal)), File.ReadAllBytes(Path.Combine(root, "PRODUCERS.INC")));
    }

    [Fact]
    public void DocumentaryTextCannotHideAnInitialControlOverride()
    {
        var leaf = Leaf + "WELTARG      control override\n 'P1' 'ORAT' 5000 /\n/\n";
        Assert.Null(EclipsePackageProductionTargetReader.Read(Package(leaf: leaf)));
    }

    [Fact]
    public void NestedMainUsesItsOwnDirectoryForIncludesAndPackageRootForSourceIdentities()
    {
        Directory.CreateDirectory(Path.Combine(root, "deck"));
        Directory.CreateDirectory(Path.Combine(root, "controls"));
        var main = Path.Combine(root, "deck/MODEL.DATA");
        File.WriteAllText(main, Main.Replace("'INITIAL.INC'", "'../controls/INITIAL.INC'", StringComparison.Ordinal));
        File.WriteAllText(Path.Combine(root, "controls/INITIAL.INC"), Wrapper.Replace("'PRODUCERS.INC'", "'../controls/PRODUCERS.INC'", StringComparison.Ordinal));
        File.WriteAllText(Path.Combine(root, "controls/PRODUCERS.INC"), Leaf);
        var package = new EclipseDeckPackageResolver().Resolve(main, root);
        var inspection = EclipseDataInspector.InspectPackage(package.Files, "MODEL.DATA", root);
        var target = Assert.Single(inspection.PackageProductionTargetContext!.Targets);
        Assert.Equal("controls/PRODUCERS.INC", target.SourceFile);
        Assert.Equal("deck/MODEL.DATA", Assert.Single(inspection.ScheduleMetadata!.Wells).SourceFile);
    }
}
