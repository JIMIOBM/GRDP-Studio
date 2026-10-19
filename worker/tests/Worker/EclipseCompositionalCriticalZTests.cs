using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalCriticalZTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-critical-z", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalCriticalZTests() => Directory.CreateDirectory(root);
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
        return new(1, s.Context.SourceFile, s.Context.SourceSha256, r.LineNumber, r.Field, r.ComponentIndex, r.ComponentName, r.Unit, r.OriginalValue, 0.3);
    }
    [Fact]
    public async Task SelectedC1DimensionlessCriticalZMatchesIndependentNativeInputAndRetainsEveryPressure()
    {
        var package = Package(); var before = EclipseCompositionalFluidPropertyReader.Read(package, "ZCRIT"); Assert.NotNull(before);
        var pressure = EclipseCompositionalFluidPropertyReader.Read(package); Assert.NotNull(pressure);
        Assert.Equal("eclipse-compositional-critical-z-context/1", before.Context.SchemaVersion);
        Assert.Equal(9, before.Context.Records.Count); Assert.Equal(before.Context.ComponentNames, before.Context.Records.Select(r => r.ComponentName));
        var p = Parameters(before); Assert.Equal("C1", p.ComponentName); Assert.Equal(128, p.LineNumber); Assert.Equal(0.28473, p.OriginalValue); Assert.Equal("dimensionless", p.Unit);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(before.SourceBytes)), before.Context.SourceSha256);
        await EclipseCompositionalFluidPropertyEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-ZCRIT-OFFICIAL.DATA")), File.ReadAllBytes(package.MainFile));
        var after = EclipseCompositionalFluidPropertyReader.Read(package, "ZCRIT"); Assert.NotNull(after);
        Assert.Equal(before.Context.Records.Select(r => r.ComponentIndex == 3 ? r with { OriginalValue = 0.3 } : r), after.Context.Records);
        Assert.Equal(pressure.Context.Records, EclipseCompositionalFluidPropertyReader.Read(package)!.Context.Records);
    }
    [Theory]
    [InlineData("default")] [InlineData("short")] [InlineData("extra")] [InlineData("duplicate")] [InlineData("region")]
    [InlineData("co2store")] [InlineData("negative")] [InlineData("nonfinite")] [InlineData("computed")]
    [InlineData("volume-override")] [InlineData("viscosity-volume")] [InlineData("viscosity-z")] [InlineData("computed-viscosity")]
    public void UnverifiedCriticalZFactorsCannotOfferAnEditor(string fault)
    {
        var package = Package(); var text = File.ReadAllText(package.MainFile);
        var changed = fault switch {
            "volume-override" => text.Replace("\r\nZCRIT", "\r\nVCRIT\r\n9*1 /\r\nZCRIT"),
            "viscosity-volume" => text.Replace("\r\nZCRIT", "\r\nVCRITVIS\r\n9*1 /\r\nZCRIT"),
            "viscosity-z" => text.Replace("\r\nZCRIT", "\r\nZCRITVIS\r\n9*1 /\r\nZCRIT"),
            "computed-viscosity" => text.Replace("EQUALS\r\nPORO", "EQUALS\r\nZCRITVIS 1 /\r\nPORO"),
            "default" => text.Replace(".28473", "1*"), "short" => text.Replace(".28473", ""),
            "extra" => text.Replace(".28473", ".28473 0.3"), "duplicate" => text.Replace("\r\nZCRIT", "\r\nZCRIT\r\n9*0.3 /\r\nZCRIT"),
            "region" => text.Replace("\r\nSOLUTION", "\r\nEOSNUM\r\n324*1 /\r\nSOLUTION"),
            "co2store" => text.Replace("\r\nFIELD", "\r\nCO2STORE\r\nFIELD"),
            "computed" => text.Replace("EQUALS\r\nPORO", "EQUALS\r\nZCRIT 0.3 /\r\nPORO"),
            "negative" => text.Replace(".28473", "-.28473"), _ => text.Replace(".28473", "NaN")
        };
        Assert.NotEqual(text, changed); File.WriteAllText(package.MainFile, changed);
        Assert.Null(EclipseCompositionalFluidPropertyReader.Read(package, "ZCRIT"));
    }
    [Theory]
    [InlineData("hash")] [InlineData("line")] [InlineData("component")] [InlineData("unit")] [InlineData("original")] [InlineData("field")]
    public async Task ForeignUnitsOrSourceCannotChangeInput(string fault)
    {
        var package = Package(); var s = EclipseCompositionalFluidPropertyReader.Read(package, "ZCRIT"); Assert.NotNull(s);
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
    public void CriticalZProtocolCannotBeUsedAsPressureOrCoerceUnknownFields()
    {
        var s = EclipseCompositionalFluidPropertyReader.Read(Package(), "ZCRIT"); Assert.NotNull(s); var p = Parameters(s);
        var j = new JsonObject { ["schemaVersion"] = EclipseCompositionalFluidPropertyParameters.CriticalZSchema, ["baselineRunId"] = 1,
            ["sourceFile"] = p.SourceFile, ["sourceSha256"] = p.SourceSha256, ["lineNumber"] = p.LineNumber, ["field"] = p.Field,
            ["componentIndex"] = 3, ["componentName"] = "C1", ["unit"] = "dimensionless", ["originalValue"] = 0.28473, ["targetValue"] = 0.3 };
        Assert.True(EclipseCompositionalFluidPropertyParameters.TryParse(JsonSerializer.SerializeToElement(j), out var actual)); Assert.Equal(p, actual);
        foreach (var (key, value) in new (string, JsonNode?)[] { ("schemaVersion", JsonValue.Create(EclipseCompositionalFluidPropertyParameters.Schema)),
            ("unit", JsonValue.Create("degF")), ("unit", JsonValue.Create("K")), ("unit", JsonValue.Create("psia")), ("field", JsonValue.Create("PCRIT")),
            ("targetValue", JsonValue.Create("0.3")), ("targetValue", JsonValue.Create(0)), ("targetValue", JsonValue.Create(0.28473)), ("extra", JsonValue.Create(1)) })
        {
            var bad = (JsonObject)j.DeepClone(); bad[key] = value;
            Assert.False(EclipseCompositionalFluidPropertyParameters.TryParse(JsonSerializer.SerializeToElement(bad), out _));
        }
    }
}
