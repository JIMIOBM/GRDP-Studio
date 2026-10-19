using System.IO.Compression;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseWaterInjectionPlanTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-injection-plan", Guid.NewGuid().ToString("N"));
    public EclipseWaterInjectionPlanTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseDeckPackage Package()
    {
        ZipFile.ExtractToDirectory(Path.Combine(AppContext.BaseDirectory, "Fixtures/INJECTION-PLAN-BASE.zip"), root);
        return new EclipseDeckPackageResolver().Resolve(Path.Combine(root, "MODEL.DATA"), root);
    }
    private static EclipseWaterInjectionPlanEdit Edit(EclipsePackageWaterInjectionPlanReader.Target t, double value) => new(t.Record.Well, t.Record.SourceFile,
        t.SourceSha256, t.Record.LineNumber, t.Record.Date, t.Record.TimeDays, t.Record.Unit, t.Record.OriginalInjectionRate, value);
    private static EclipseWaterInjectionPlanParameters Parameters(EclipsePackageWaterInjectionPlanReader.Snapshot snapshot) => new(1, [
        Edit(snapshot.Targets.Single(t => t.Record.Well == "INJECTR5" && t.Record.TimeDays == 365), .5),
        Edit(snapshot.Targets.Single(t => t.Record.Well == "INJECTR6" && t.Record.TimeDays == 730), .7),
        Edit(snapshot.Targets.Single(t => t.Record.Well == "INJECTR5" && t.Record.TimeDays == 1095), .9)]);
    [Fact]
    public async Task NativeTwoWellThreeDatePlanKeepsPhysicalSourcesAndExactGoldenLeafBytes()
    {
        var package = Package(); var snapshot = EclipsePackageWaterInjectionPlanReader.Read(package); Assert.NotNull(snapshot);
        Assert.Equal("1982-10-19", snapshot.StartDate); Assert.Equal(3650, snapshot.EndTimeDays); Assert.Equal(8, snapshot.Targets.Count);
        Assert.Equal(new[] {0, 0, 365, 365, 730, 730, 1095, 1095}, snapshot.Targets.Select(t => t.Record.TimeDays));
        var parameters = Parameters(snapshot); Assert.Equal(3, parameters.Edits.Count);
        Assert.Equal(new[] {"controls/YEAR1.INC", "controls/YEAR2.INC", "controls/YEAR3.INC"}, parameters.Edits.Select(e => e.SourceFile));
        // Physical row numbers restart in each file; execution order is the date chain, not sorted physical line numbers.
        Assert.Equal(new[] {2, 3, 2}, parameters.Edits.Select(e => e.LineNumber));
        var before = package.Files.ToDictionary(f => f, File.ReadAllBytes);
        await EclipseWaterInjectionPlanEditor.ApplyAsync(package, parameters, TestContext.Current.CancellationToken);
        foreach (var file in package.Files)
        {
            if (Path.GetFileName(file).StartsWith("YEAR", StringComparison.Ordinal))
                Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures/INJECTION-PLAN-" + Path.GetFileName(file))), File.ReadAllBytes(file));
            else Assert.Equal(before[file], File.ReadAllBytes(file));
        }
    }
    [Theory]
    [InlineData("hash")] [InlineData("source")] [InlineData("line")] [InlineData("date")] [InlineData("time")] [InlineData("unit")]
    [InlineData("original")] [InlineData("target")] [InlineData("same")] [InlineData("order")] [InlineData("duplicate")]
    public async Task AnyInvalidEditRejectsBeforeChangingAnyPhysicalFile(string fault)
    {
        var package = Package(); var parameters = Parameters(EclipsePackageWaterInjectionPlanReader.Read(package)!);
        var edits = parameters.Edits.ToArray(); var e = edits[1];
        edits[1] = fault switch { "hash" => e with { SourceSha256 = new('0', 64) }, "source" => e with { SourceFile = "controls/YEAR1.INC" },
            "line" => e with { LineNumber = e.LineNumber + 1 }, "date" => e with { Date = "1984-10-19" }, "time" => e with { TimeDays = 731 },
            "unit" => e with { Unit = "sm3/day" }, "original" => e with { OriginalInjectionRate = .99 }, "target" => e with { TargetInjectionRate = -1 },
            "same" => e with { TargetInjectionRate = 1 }, _ => e };
        if (fault == "order") Array.Reverse(edits);
        if (fault == "duplicate") edits[1] = edits[0];
        var before = package.Files.ToDictionary(f => f, File.ReadAllBytes);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseWaterInjectionPlanEditor.ApplyAsync(package, new(1, edits), TestContext.Current.CancellationToken));
        foreach (var file in package.Files) Assert.Equal(before[file], File.ReadAllBytes(file));
    }
    [Theory]
    [InlineData("ACTIONX")] [InlineData("PATHS")] [InlineData("ENDINC")] [InlineData("SKIP")] [InlineData("TSTEP")]
    [InlineData("GCONPROD")] [InlineData("WELTARG")]
    public void UnsupportedExecutionChangesCannotProduceEditablePlan(string keyword)
    {
        var package = Package(); var plan = Path.Combine(root, "controls/PLAN.INC");
        File.AppendAllText(plan, "\n" + keyword + "\n 1 /\n/\n");
        Assert.Null(EclipsePackageWaterInjectionPlanReader.Read(package));
    }
    [Fact]
    public void ReusedPhysicalFileCannotAffectSeveralExecutionPositions()
    {
        var package = Package(); var plan = Path.Combine(root, "controls/PLAN.INC");
        File.AppendAllText(plan, "\nINCLUDE\n 'controls/YEAR1.INC' /\n");
        Assert.Null(EclipsePackageWaterInjectionPlanReader.Read(package));
    }
    [Fact]
    public async Task Utf8BomAndChineseCommentsRemainExactWhilePhysicalRowsStayBound()
    {
        var package = Package(); var path = Path.Combine(root, "controls/YEAR1.INC");
        var original = "\ufeff-- 原注释保持不变\r\n" + File.ReadAllText(path); File.WriteAllBytes(path, Encoding.UTF8.GetBytes(original));
        var target = EclipsePackageWaterInjectionPlanReader.Read(package)!.Targets.Single(t => t.Record.Well == "INJECTR5" && t.Record.TimeDays == 365);
        Assert.Equal(3, target.Record.LineNumber);
        await EclipseWaterInjectionPlanEditor.ApplyAsync(package, new(1, [Edit(target, .5)]), TestContext.Current.CancellationToken);
        Assert.Equal(Encoding.UTF8.GetBytes(original.Replace("'INJECTR5' 'WATER' 'OPEN' 'RATE' 1 ", "'INJECTR5' 'WATER' 'OPEN' 'RATE' 0.5 ", StringComparison.Ordinal)), File.ReadAllBytes(path));
    }
    [Fact]
    public void PackageParameterContractRetainsStrictTypesAndDoesNotLoosenSingleFileContract()
    {
        var p = Parameters(EclipsePackageWaterInjectionPlanReader.Read(Package())!);
        var value = JsonSerializer.SerializeToElement(new { schemaVersion = EclipseWaterInjectionPlanParameters.Schema, baselineRunId = p.BaselineRunId,
            edits = p.Edits }, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
        Assert.True(EclipseWaterInjectionPlanParameters.TryParse(value, out var parsed)); Assert.Equal(p.Edits.ToArray(), parsed!.Edits.ToArray());
        Assert.False(EclipseControlPlanParameters.TryParse(value, out _));
        var node = System.Text.Json.Nodes.JsonNode.Parse(value.GetRawText())!;
        node["edits"]![0]!["targetInjectionRate"] = "250";
        Assert.False(EclipseWaterInjectionPlanParameters.TryParse(JsonSerializer.SerializeToElement(node), out _));
        node["edits"]![0]!["targetInjectionRate"] = 250; node["edits"]![0]!["sourceFile"] = "../YEAR1.INC";
        Assert.False(EclipseWaterInjectionPlanParameters.TryParse(JsonSerializer.SerializeToElement(node), out _));
    }
    [Fact]
    public void WaterPlanContextCarriesAllEightOriginalRecordsAndSourceIdentities()
    {
        var package = Package(); var context = EclipsePackageWaterInjectionPlanReader.Read(package)!.Context;
        Assert.Equal("eclipse-water-injection-control-plan-context/1", context.SchemaVersion);
        Assert.Equal(8, context.Records.Count);
        Assert.All(context.Records, r => { Assert.Equal(1, r.OriginalInjectionRate); Assert.Equal(6000, r.BhpLimit);
            Assert.Equal(Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(File.ReadAllBytes(Path.Combine(root, r.SourceFile)))), r.SourceSha256); });
    }
    [Fact]
    public void ActualInspectionCarriesWaterPlanAndOriginalRawInjectionRows()
    {
        var p = Package();
        var inspection = Grdp.SoftwareIntegration.Worker.Inspection.EclipseDataInspector.InspectPackage(p.Files, "MODEL.DATA", root);
        Assert.Equal("eclipse-data-inspection/4", inspection.SchemaVersion);
        var c = inspection.WaterInjectionControlPlanContext; Assert.NotNull(c); Assert.Equal(8, c.Records.Count);
        Assert.Null(inspection.PackageProductionControlPlanContext);
        foreach (var r in c.Records)
            Assert.Contains(inspection.ScheduleMetadata!.Records, row => row.Keyword == "WCONINJE" && row.SourceFile == r.SourceFile && row.LineNumber == r.LineNumber);
    }

    [Fact]
    public void ExcessiveCalendarSpanCannotPublishAContextTheParameterContractCannotRepresent()
    {
        var p = Package();
        var main = File.ReadAllText(p.MainFile).Replace("1982", "1000", StringComparison.Ordinal);
        File.WriteAllText(p.MainFile, main);
        var plan = Path.Combine(root, "controls/PLAN.INC");
        File.WriteAllText(plan, File.ReadAllText(plan).Replace("1992", "9999", StringComparison.Ordinal));
        Assert.Null(EclipsePackageWaterInjectionPlanReader.Read(p));
    }

}
