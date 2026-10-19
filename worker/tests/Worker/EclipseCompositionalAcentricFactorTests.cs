using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalAcentricFactorTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-acentric-factor", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalAcentricFactorTests() => Directory.CreateDirectory(root);
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
        return new(1, s.Context.SourceFile, s.Context.SourceSha256, r.LineNumber, r.Field, r.ComponentIndex, r.ComponentName, r.Unit, r.OriginalValue, 0.03);
    }
    [Fact]
    public async Task SelectedC1DimensionlessAcentricFactorMatchesIndependentNativeInputAndRetainsEveryPressure()
    {
        var package = Package(); var before = EclipseCompositionalFluidPropertyReader.Read(package, "ACF"); Assert.NotNull(before);
        var pressure = EclipseCompositionalFluidPropertyReader.Read(package); Assert.NotNull(pressure);
        Assert.Equal("eclipse-compositional-acentric-factor-context/1", before.Context.SchemaVersion);
        Assert.Equal(9, before.Context.Records.Count); Assert.Equal(before.Context.ComponentNames, before.Context.Records.Select(r => r.ComponentName));
        var p = Parameters(before); Assert.Equal("C1", p.ComponentName); Assert.Equal(134, p.LineNumber); Assert.Equal(0.013, p.OriginalValue); Assert.Equal("dimensionless", p.Unit);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(before.SourceBytes)), before.Context.SourceSha256);
        await EclipseCompositionalFluidPropertyEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-ACF-OFFICIAL.DATA")), File.ReadAllBytes(package.MainFile));
        var after = EclipseCompositionalFluidPropertyReader.Read(package, "ACF"); Assert.NotNull(after);
        Assert.Equal(before.Context.Records.Select(r => r.ComponentIndex == 3 ? r with { OriginalValue = 0.03 } : r), after.Context.Records);
        Assert.Equal(pressure.Context.Records, EclipseCompositionalFluidPropertyReader.Read(package)!.Context.Records);
    }
    [Theory]
    [InlineData("default")] [InlineData("short")] [InlineData("extra")] [InlineData("duplicate")] [InlineData("region")]
    [InlineData("co2store")] [InlineData("high")] [InlineData("nonfinite")] [InlineData("computed")]
    public void UnverifiedAcentricFactorFactorsCannotOfferAnEditor(string fault)
    {
        var package = Package(); var text = File.ReadAllText(package.MainFile);
        var changed = fault switch {
            "default" => text.Replace(".01300", "1*"), "short" => text.Replace(".01300", ""),
            "extra" => text.Replace(".01300", ".01300 0.03"), "duplicate" => text.Replace("\r\nACF", "\r\nACF\r\n9*0.03 /\r\nACF"),
            "region" => text.Replace("\r\nSOLUTION", "\r\nEOSNUM\r\n324*1 /\r\nSOLUTION"),
            "co2store" => text.Replace("\r\nFIELD", "\r\nCO2STORE\r\nFIELD"),
            "computed" => text.Replace("EQUALS\r\nPORO", "EQUALS\r\nACF 0.03 /\r\nPORO"),
            "high" => text.Replace(".01300", "1e9"), _ => text.Replace(".01300", "NaN")
        };
        Assert.NotEqual(text, changed); File.WriteAllText(package.MainFile, changed);
        Assert.Null(EclipseCompositionalFluidPropertyReader.Read(package, "ACF"));
    }
    [Theory]
    [InlineData("hash")] [InlineData("line")] [InlineData("component")] [InlineData("unit")] [InlineData("original")] [InlineData("field")]
    public async Task ForeignUnitsOrSourceCannotChangeInput(string fault)
    {
        var package = Package(); var s = EclipseCompositionalFluidPropertyReader.Read(package, "ACF"); Assert.NotNull(s);
        var p = Parameters(s); p = fault switch {
            "hash" => p with { SourceSha256 = new('0', 64) }, "line" => p with { LineNumber = 122 },
            "component" => p with { ComponentName = "C2" }, "unit" => p with { Unit = "degF" },
            "original" => p with { OriginalValue = 667.7817 }, _ => p with { Field = "PCRIT" }
        };
        var bytes = File.ReadAllBytes(package.MainFile);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseCompositionalFluidPropertyEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken));
        Assert.Equal(bytes, File.ReadAllBytes(package.MainFile));
    }
    [Fact]
    public void AcentricFactorProtocolCannotBeUsedAsPressureOrCoerceUnknownFields()
    {
        var s = EclipseCompositionalFluidPropertyReader.Read(Package(), "ACF"); Assert.NotNull(s); var p = Parameters(s);
        var j = new JsonObject { ["schemaVersion"] = EclipseCompositionalFluidPropertyParameters.AcentricFactorSchema, ["baselineRunId"] = 1,
            ["sourceFile"] = p.SourceFile, ["sourceSha256"] = p.SourceSha256, ["lineNumber"] = p.LineNumber, ["field"] = p.Field,
            ["componentIndex"] = 3, ["componentName"] = "C1", ["unit"] = "dimensionless", ["originalValue"] = 0.013, ["targetValue"] = 0.03 };
        Assert.True(EclipseCompositionalFluidPropertyParameters.TryParse(JsonSerializer.SerializeToElement(j), out var actual)); Assert.Equal(p, actual);
        foreach (var (key, value) in new (string, JsonNode?)[] { ("schemaVersion", JsonValue.Create(EclipseCompositionalFluidPropertyParameters.Schema)),
            ("unit", JsonValue.Create("degF")), ("unit", JsonValue.Create("K")), ("unit", JsonValue.Create("psia")), ("field", JsonValue.Create("PCRIT")),
            ("targetValue", JsonValue.Create("0.03")), ("targetValue", JsonValue.Create(1e9)), ("targetValue", JsonValue.Create(0.013)), ("extra", JsonValue.Create(1)) })
        {
            var bad = (JsonObject)j.DeepClone(); bad[key] = value;
            Assert.False(EclipseCompositionalFluidPropertyParameters.TryParse(JsonSerializer.SerializeToElement(bad), out _));
        }
    }

    [Theory]
    [InlineData(0.0)] [InlineData(-0.01)]
    public async Task ZeroAndNegativeAcentricFactorsAreValidAndBindTheirExactSource(double value)
    {
        var package = Package(); var before = EclipseCompositionalFluidPropertyReader.Read(package, "ACF"); Assert.NotNull(before);
        var p = Parameters(before) with { TargetValue = value };
        Assert.True(EclipseCompositionalFluidPropertyEditor.Matches(before, p));
        await EclipseCompositionalFluidPropertyEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken);
        var after = EclipseCompositionalFluidPropertyReader.Read(package, "ACF"); Assert.NotNull(after);
        Assert.Equal(value, after.Context.Records[2].OriginalValue);
        Assert.Equal(before.Context.Records.Select(r => r.ComponentIndex == 3 ? r with { OriginalValue = value } : r), after.Context.Records);
        var back = Parameters(after); Assert.True(EclipseCompositionalFluidPropertyEditor.Matches(after, back));
    }
}
