using System.IO.Compression;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipsePackageControlPlanTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-include-control", Guid.NewGuid().ToString("N"));
    public EclipsePackageControlPlanTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseDeckPackage Package()
    {
        ZipFile.ExtractToDirectory(Path.Combine(AppContext.BaseDirectory, "Fixtures/INCLUDE-CONTROL-BASE.zip"), root);
        return new EclipseDeckPackageResolver().Resolve(Path.Combine(root, "MODEL.DATA"), root);
    }
    private static EclipsePackageControlPlanEdit Edit(EclipsePackageControlPlanReader.Target t, double value) => new(t.Record.Well, t.Record.SourceFile,
        t.SourceSha256, t.Record.LineNumber, t.Record.Date, t.Record.TimeDays, t.Record.Unit, t.Record.OriginalOilRate, value);
    private static EclipsePackageControlPlanParameters Parameters(EclipsePackageControlPlanReader.Snapshot snapshot) => new(1, [
        Edit(snapshot.Targets.Single(t => t.Record.Well == "PRODUCR1" && t.Record.TimeDays == 365), 250),
        Edit(snapshot.Targets.Single(t => t.Record.Well == "PRODUCR2" && t.Record.TimeDays == 730), 350),
        Edit(snapshot.Targets.Single(t => t.Record.Well == "PRODUCR1" && t.Record.TimeDays == 1095), 450)]);
    [Fact]
    public async Task NativeTwoWellThreeDatePlanKeepsPhysicalSourcesAndExactGoldenLeafBytes()
    {
        var package = Package(); var snapshot = EclipsePackageControlPlanReader.Read(package); Assert.NotNull(snapshot);
        Assert.Equal("1982-10-19", snapshot.StartDate); Assert.Equal(3650, snapshot.EndTimeDays); Assert.Equal(10, snapshot.Targets.Count);
        Assert.Equal(new[] {0, 0, 0, 0, 365, 365, 730, 730, 1095, 1095}, snapshot.Targets.Select(t => t.Record.TimeDays));
        var parameters = Parameters(snapshot); Assert.Equal(3, parameters.Edits.Count);
        Assert.Equal(new[] {"controls/YEAR1.INC", "controls/YEAR2.INC", "controls/YEAR3.INC"}, parameters.Edits.Select(e => e.SourceFile));
        // Physical row numbers restart in each file; execution order is the date chain, not sorted physical line numbers.
        Assert.Equal(new[] {2, 3, 2}, parameters.Edits.Select(e => e.LineNumber));
        var before = package.Files.ToDictionary(f => f, File.ReadAllBytes);
        await EclipsePackageControlPlanEditor.ApplyAsync(package, parameters, TestContext.Current.CancellationToken);
        foreach (var file in package.Files)
        {
            if (Path.GetFileName(file).StartsWith("YEAR", StringComparison.Ordinal))
                Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures/INCLUDE-CONTROL-" + Path.GetFileName(file))), File.ReadAllBytes(file));
            else Assert.Equal(before[file], File.ReadAllBytes(file));
        }
    }
    [Theory]
    [InlineData("hash")] [InlineData("source")] [InlineData("line")] [InlineData("date")] [InlineData("time")] [InlineData("unit")]
    [InlineData("original")] [InlineData("target")] [InlineData("same")] [InlineData("order")] [InlineData("duplicate")]
    public async Task AnyInvalidEditRejectsBeforeChangingAnyPhysicalFile(string fault)
    {
        var package = Package(); var parameters = Parameters(EclipsePackageControlPlanReader.Read(package)!);
        var edits = parameters.Edits.ToArray(); var e = edits[1];
        edits[1] = fault switch { "hash" => e with { SourceSha256 = new('0', 64) }, "source" => e with { SourceFile = "controls/YEAR1.INC" },
            "line" => e with { LineNumber = e.LineNumber + 1 }, "date" => e with { Date = "1984-10-19" }, "time" => e with { TimeDays = 731 },
            "unit" => e with { Unit = "sm3/day" }, "original" => e with { OriginalOilRate = 499 }, "target" => e with { TargetOilRate = -1 },
            "same" => e with { TargetOilRate = 500 }, _ => e };
        if (fault == "order") Array.Reverse(edits);
        if (fault == "duplicate") edits[1] = edits[0];
        var before = package.Files.ToDictionary(f => f, File.ReadAllBytes);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipsePackageControlPlanEditor.ApplyAsync(package, new(1, edits), TestContext.Current.CancellationToken));
        foreach (var file in package.Files) Assert.Equal(before[file], File.ReadAllBytes(file));
    }
    [Theory]
    [InlineData("ACTIONX")] [InlineData("PATHS")] [InlineData("ENDINC")] [InlineData("SKIP")] [InlineData("TSTEP")]
    [InlineData("GCONPROD")] [InlineData("WELTARG")]
    public void UnsupportedExecutionChangesCannotProduceEditablePlan(string keyword)
    {
        var package = Package(); var plan = Path.Combine(root, "controls/PLAN.INC");
        File.AppendAllText(plan, "\n" + keyword + "\n 1 /\n/\n");
        Assert.Null(EclipsePackageControlPlanReader.Read(package));
    }
    [Fact]
    public void ReusedPhysicalFileCannotAffectSeveralExecutionPositions()
    {
        var package = Package(); var plan = Path.Combine(root, "controls/PLAN.INC");
        File.AppendAllText(plan, "\nINCLUDE\n 'controls/YEAR1.INC' /\n");
        Assert.Null(EclipsePackageControlPlanReader.Read(package));
    }
    [Fact]
    public async Task Utf8BomAndChineseCommentsRemainExactWhilePhysicalRowsStayBound()
    {
        var package = Package(); var path = Path.Combine(root, "controls/YEAR1.INC");
        var original = "\ufeff-- 原注释保持不变\r\n" + File.ReadAllText(path); File.WriteAllBytes(path, Encoding.UTF8.GetBytes(original));
        var target = EclipsePackageControlPlanReader.Read(package)!.Targets.Single(t => t.Record.Well == "PRODUCR1" && t.Record.TimeDays == 365);
        Assert.Equal(3, target.Record.LineNumber);
        await EclipsePackageControlPlanEditor.ApplyAsync(package, new(1, [Edit(target, 250)]), TestContext.Current.CancellationToken);
        Assert.Equal(Encoding.UTF8.GetBytes(original.Replace("'ORAT' 500", "'ORAT' 250", StringComparison.Ordinal).Replace("'PRODUCR2' 'OPEN' 'ORAT' 250", "'PRODUCR2' 'OPEN' 'ORAT' 500", StringComparison.Ordinal)), File.ReadAllBytes(path));
    }
    [Fact]
    public void PackageParameterContractRetainsStrictTypesAndDoesNotLoosenSingleFileContract()
    {
        var p = Parameters(EclipsePackageControlPlanReader.Read(Package())!);
        var value = JsonSerializer.SerializeToElement(new { schemaVersion = EclipsePackageControlPlanParameters.Schema, baselineRunId = p.BaselineRunId,
            edits = p.Edits }, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
        Assert.True(EclipsePackageControlPlanParameters.TryParse(value, out var parsed)); Assert.Equal(p.Edits.ToArray(), parsed!.Edits.ToArray());
        Assert.False(EclipseControlPlanParameters.TryParse(value, out _));
        var node = System.Text.Json.Nodes.JsonNode.Parse(value.GetRawText())!;
        node["edits"]![0]!["targetOilRate"] = "250";
        Assert.False(EclipsePackageControlPlanParameters.TryParse(JsonSerializer.SerializeToElement(node), out _));
        node["edits"]![0]!["targetOilRate"] = 250; node["edits"]![0]!["sourceFile"] = "../YEAR1.INC";
        Assert.False(EclipsePackageControlPlanParameters.TryParse(JsonSerializer.SerializeToElement(node), out _));
    }
    [Fact]
    public void ActualPackageInspectionPublishesCompletePhysicalControlChainWithHashes()
    {
        var package = Package();
        var inspection = Grdp.SoftwareIntegration.Worker.Inspection.EclipseDataInspector.InspectPackage(package.Files, "MODEL.DATA", root);
        Assert.Equal("eclipse-data-inspection/4", inspection.SchemaVersion);
        var context = inspection.PackageProductionControlPlanContext; Assert.NotNull(context); Assert.Equal(10, context.Records.Count);
        Assert.Equal(3650, context.EndTimeDays); Assert.Equal("1982-10-19", context.StartDate);
        foreach (var record in context.Records) {
            var bytes = File.ReadAllBytes(Path.Combine(root, record.SourceFile));
            Assert.Equal(Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes)), record.SourceSha256);
            Assert.Contains(inspection.ScheduleMetadata!.Records, r => r.Keyword == "WCONPROD" && r.SourceFile == record.SourceFile && r.LineNumber == record.LineNumber);
        }
    }
}
