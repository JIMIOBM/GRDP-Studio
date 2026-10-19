using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalFluidPropertyTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-fluid-properties", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalFluidPropertyTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseDeckPackage Package()
    {
        var file = Path.Combine(root, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        return new EclipseDeckPackageResolver().Resolve(file, root);
    }
    private static EclipseCompositionalFluidPropertyParameters Parameters(EclipseCompositionalFluidPropertyReader.Snapshot s)
    {
        var r = s.Context.Records.Single(r => r.ComponentIndex == 3);
        return new(1, s.Context.SourceFile, s.Context.SourceSha256, r.LineNumber, r.Field, r.ComponentIndex, r.ComponentName, r.Unit, r.OriginalValue, 700);
    }
    [Fact]
    public void OfficialSourceBindsAllNineCriticalPressuresToOriginalComponentOrderAndPhysicalUnits()
    {
        var s = EclipseCompositionalFluidPropertyReader.Read(Package()); Assert.NotNull(s);
        Assert.Equal(9, s.Context.Records.Count); Assert.Equal(9, s.Targets.Count); Assert.Equal("PR", s.Context.EquationOfState);
        Assert.Equal(Enumerable.Range(1, 9), s.Context.Records.Select(r => r.ComponentIndex));
        Assert.Equal(s.Context.ComponentNames, s.Context.Records.Select(r => r.ComponentName));
        var p = Parameters(s); Assert.Equal("C1", p.ComponentName); Assert.Equal("PCRIT", p.Field); Assert.Equal(122, p.LineNumber);
        Assert.Equal(667.7817, p.OriginalValue); Assert.Equal("psia", p.Unit);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(s.SourceBytes)), s.Context.SourceSha256);
    }
    [Fact]
    public async Task OnlySelectedMethanePressureChangesAndInputMatchesIndependentNativeByteForByte()
    {
        var package = Package(); var before = EclipseCompositionalFluidPropertyReader.Read(package); Assert.NotNull(before);
        await EclipseCompositionalFluidPropertyEditor.ApplyAsync(package, Parameters(before), TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-PCRIT-OFFICIAL.DATA")), File.ReadAllBytes(package.MainFile));
        var after = EclipseCompositionalFluidPropertyReader.Read(package); Assert.NotNull(after);
        Assert.Equal(before.Context.Records.Select(r => r.ComponentIndex == 3 ? r with { OriginalValue = 700 } : r), after.Context.Records);
        Assert.Equal(before.Context.SourceSizeBytes - 6, after.Context.SourceSizeBytes);
    }
    [Theory]
    [InlineData("hash")] [InlineData("source")] [InlineData("line")] [InlineData("field")] [InlineData("index")] [InlineData("name")]
    [InlineData("unit")] [InlineData("original")] [InlineData("same")] [InlineData("zero")] [InlineData("negative")] [InlineData("high")]
    public async Task SourceOrComponentMismatchCannotChangeAnyModelBytes(string fault)
    {
        var package = Package(); var s = EclipseCompositionalFluidPropertyReader.Read(package); Assert.NotNull(s); var p = Parameters(s);
        p = fault switch {
            "hash" => p with { SourceSha256 = new('0', 64) }, "source" => p with { SourceFile = "OTHER.DATA" }, "line" => p with { LineNumber = 123 },
            "field" => p with { Field = "TCRIT" }, "index" => p with { ComponentIndex = 2 }, "name" => p with { ComponentName = "C2" },
            "unit" => p with { Unit = "barsa" }, "original" => p with { OriginalValue = 667 }, "same" => p with { TargetValue = p.OriginalValue },
            "zero" => p with { TargetValue = 0 }, "negative" => p with { TargetValue = -1 }, _ => p with { TargetValue = 100_000_001 }
        };
        var bytes = File.ReadAllBytes(package.MainFile); Assert.False(EclipseCompositionalFluidPropertyEditor.Matches(s, p));
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseCompositionalFluidPropertyEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken));
        Assert.Equal(bytes, File.ReadAllBytes(package.MainFile));
    }
    [Theory]
    [InlineData("default")] [InlineData("short")] [InlineData("extra")] [InlineData("duplicate")] [InlineData("table")]
    [InlineData("region")] [InlineData("co2store")] [InlineData("negative")] [InlineData("nonfinite")] [InlineData("computed-property")]
    public void UnsupportedOrUnorderedCriticalPropertySourcesDoNotOfferThisEditor(string fault)
    {
        var package = Package(); var text = File.ReadAllText(package.MainFile);
        var changed = fault switch {
            "default" => text.Replace("667.78170", "1*", StringComparison.Ordinal), "short" => text.Replace("667.78170", "", StringComparison.Ordinal),
            "extra" => text.Replace("667.78170", "667.78170 600", StringComparison.Ordinal),
            "duplicate" => text.Replace("\r\nTCRIT", "\r\nPCRIT\r\n9*600 /\r\nTCRIT", StringComparison.Ordinal),
            "table" => text.Replace("1 1 40 40 /", "1 1 40 40 1* /", StringComparison.Ordinal),
            "region" => text.Replace("\r\nSOLUTION", "\r\nEOSNUM\r\n324*1 /\r\nSOLUTION", StringComparison.Ordinal),
            "co2store" => text.Replace("\r\nFIELD", "\r\nCO2STORE\r\nFIELD", StringComparison.Ordinal),
            "computed-property" => text.Replace("EQUALS\r\nPORO", "EQUALS\r\nPCRIT 600 /\r\nPORO", StringComparison.Ordinal),
            "negative" => text.Replace("667.78170", "-667.78170", StringComparison.Ordinal), _ => text.Replace("667.78170", "NaN", StringComparison.Ordinal)
        };
        Assert.NotEqual(text, changed); File.WriteAllText(package.MainFile, changed);
        Assert.Null(EclipseCompositionalFluidPropertyReader.Read(package));
    }
    [Fact]
    public async Task ActualVersion22InspectionRetainsPressureProductionAndReinjectionSources()
    {
        var folder = Path.Combine(root, "models", "1"); Directory.CreateDirectory(folder); var file = Path.Combine(folder, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        var storage = new Grdp.SoftwareIntegration.Worker.Storage.StorageResolver(Microsoft.Extensions.Options.Options.Create(new WorkerOptions { StorageRoot = root }));
        var service = new Grdp.SoftwareIntegration.Worker.Inspection.EclipseDataInspectionService(storage);
        var sha = Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file)));
        var outcome = await service.InspectAsync(new("models/1/SPE3_GASCOND.DATA", sha), TestContext.Current.CancellationToken);
        Assert.Equal(200, outcome.HttpStatus); var body = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.ModelValidationResponse>(outcome.Body);
        var i = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.EclipseDataInspection>(body.Inspection);
        Assert.Equal("eclipse-data-inspection/22", i.SchemaVersion); Assert.NotNull(i.CompositionalProductionContext); Assert.NotNull(i.CompositionalReinjectionContext); Assert.NotNull(i.CompositionalFluidPropertyContext); Assert.NotNull(i.CompositionalAcentricFactorContext); Assert.NotNull(i.CompositionalEosPropertyContexts); Assert.Equal(3, i.CompositionalEosPropertyContexts.Count);
        Assert.Equal(sha, i.CompositionalFluidPropertyContext.SourceSha256); Assert.Equal(9, i.CompositionalFluidPropertyContext.Records.Count);
        await File.WriteAllTextAsync(Path.Combine(AppContext.BaseDirectory, "eclipse-compositional-eos-inspection.json"), JsonSerializer.Serialize(i, new JsonSerializerOptions(JsonSerializerDefaults.Web)), TestContext.Current.CancellationToken);
    }
    [Fact]
    public void TypedPropertyProtocolRejectsOtherFieldsAndNonPositiveOrUnboundValues()
    {
        var s = EclipseCompositionalFluidPropertyReader.Read(Package()); Assert.NotNull(s); var p = Parameters(s);
        var j = new JsonObject { ["schemaVersion"] = EclipseCompositionalFluidPropertyParameters.Schema, ["baselineRunId"] = p.BaselineRunId,
            ["sourceFile"] = p.SourceFile, ["sourceSha256"] = p.SourceSha256, ["lineNumber"] = p.LineNumber, ["field"] = p.Field,
            ["componentIndex"] = p.ComponentIndex, ["componentName"] = p.ComponentName, ["unit"] = p.Unit, ["originalValue"] = p.OriginalValue, ["targetValue"] = p.TargetValue };
        Assert.True(EclipseCompositionalFluidPropertyParameters.TryParse(JsonSerializer.SerializeToElement(j), out var actual)); Assert.Equal(p, actual);
        foreach (var fault in new[] { "string", "index", "field", "zero", "extra", "baseline", "unit" })
        {
            var bad = (JsonObject)j.DeepClone();
            if (fault == "string") bad["targetValue"] = "700"; if (fault == "index") bad["componentIndex"] = 10;
            if (fault == "field") bad["field"] = "TCRIT"; if (fault == "zero") bad["targetValue"] = 0;
            if (fault == "extra") bad["targetBhpUpperLimit"] = 700; if (fault == "baseline") bad["baselineRunId"] = "1"; if (fault == "unit") bad["unit"] = "barsa";
            Assert.False(EclipseCompositionalFluidPropertyParameters.TryParse(JsonSerializer.SerializeToElement(bad), out _));
        }
    }
}
