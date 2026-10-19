using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalTemperatureTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-temperature", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalTemperatureTests() => Directory.CreateDirectory(root);
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
        return new(1, s.Context.SourceFile, s.Context.SourceSha256, r.LineNumber, r.Field, r.ComponentIndex, r.ComponentName, r.Unit, r.OriginalValue, 350);
    }
    [Fact]
    public async Task SelectedC1RankineTemperatureMatchesIndependentNativeInputAndRetainsEveryPressure()
    {
        var package = Package(); var before = EclipseCompositionalFluidPropertyReader.Read(package, "TCRIT"); Assert.NotNull(before);
        var pressure = EclipseCompositionalFluidPropertyReader.Read(package); Assert.NotNull(pressure);
        Assert.Equal("eclipse-compositional-temperature-context/1", before.Context.SchemaVersion);
        Assert.Equal(9, before.Context.Records.Count); Assert.Equal(before.Context.ComponentNames, before.Context.Records.Select(r => r.ComponentName));
        var p = Parameters(before); Assert.Equal("C1", p.ComponentName); Assert.Equal(116, p.LineNumber); Assert.Equal(343.08, p.OriginalValue); Assert.Equal("degR", p.Unit);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(before.SourceBytes)), before.Context.SourceSha256);
        await EclipseCompositionalFluidPropertyEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TCRIT-OFFICIAL.DATA")), File.ReadAllBytes(package.MainFile));
        var after = EclipseCompositionalFluidPropertyReader.Read(package, "TCRIT"); Assert.NotNull(after);
        Assert.Equal(before.Context.Records.Select(r => r.ComponentIndex == 3 ? r with { OriginalValue = 350 } : r), after.Context.Records);
        Assert.Equal(pressure.Context.Records, EclipseCompositionalFluidPropertyReader.Read(package)!.Context.Records);
    }
    [Theory]
    [InlineData("default")] [InlineData("short")] [InlineData("extra")] [InlineData("duplicate")] [InlineData("region")]
    [InlineData("co2store")] [InlineData("negative")] [InlineData("nonfinite")] [InlineData("computed")]
    public void UnverifiedTemperaturesCannotOfferAnEditor(string fault)
    {
        var package = Package(); var text = File.ReadAllText(package.MainFile);
        var changed = fault switch {
            "default" => text.Replace("343.08000", "1*"), "short" => text.Replace("343.08000", ""),
            "extra" => text.Replace("343.08000", "343.08000 350"), "duplicate" => text.Replace("\r\nTCRIT", "\r\nTCRIT\r\n9*350 /\r\nTCRIT"),
            "region" => text.Replace("\r\nSOLUTION", "\r\nEOSNUM\r\n324*1 /\r\nSOLUTION"),
            "co2store" => text.Replace("\r\nFIELD", "\r\nCO2STORE\r\nFIELD"),
            "computed" => text.Replace("EQUALS\r\nPORO", "EQUALS\r\nTCRIT 350 /\r\nPORO"),
            "negative" => text.Replace("343.08000", "-343.08000"), _ => text.Replace("343.08000", "NaN")
        };
        Assert.NotEqual(text, changed); File.WriteAllText(package.MainFile, changed);
        Assert.Null(EclipseCompositionalFluidPropertyReader.Read(package, "TCRIT"));
    }
    [Theory]
    [InlineData("hash")] [InlineData("line")] [InlineData("component")] [InlineData("unit")] [InlineData("original")] [InlineData("field")]
    public async Task ForeignUnitsOrSourceCannotChangeInput(string fault)
    {
        var package = Package(); var s = EclipseCompositionalFluidPropertyReader.Read(package, "TCRIT"); Assert.NotNull(s);
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
    public void TemperatureProtocolCannotBeUsedAsPressureOrCoerceUnknownFields()
    {
        var s = EclipseCompositionalFluidPropertyReader.Read(Package(), "TCRIT"); Assert.NotNull(s); var p = Parameters(s);
        var j = new JsonObject { ["schemaVersion"] = EclipseCompositionalFluidPropertyParameters.TemperatureSchema, ["baselineRunId"] = 1,
            ["sourceFile"] = p.SourceFile, ["sourceSha256"] = p.SourceSha256, ["lineNumber"] = p.LineNumber, ["field"] = p.Field,
            ["componentIndex"] = 3, ["componentName"] = "C1", ["unit"] = "degR", ["originalValue"] = 343.08, ["targetValue"] = 350 };
        Assert.True(EclipseCompositionalFluidPropertyParameters.TryParse(JsonSerializer.SerializeToElement(j), out var actual)); Assert.Equal(p, actual);
        foreach (var (key, value) in new (string, JsonNode?)[] { ("schemaVersion", JsonValue.Create(EclipseCompositionalFluidPropertyParameters.Schema)),
            ("unit", JsonValue.Create("degF")), ("unit", JsonValue.Create("K")), ("unit", JsonValue.Create("psia")), ("field", JsonValue.Create("PCRIT")),
            ("targetValue", JsonValue.Create("350")), ("targetValue", JsonValue.Create(0)), ("targetValue", JsonValue.Create(343.08)), ("extra", JsonValue.Create(1)) })
        {
            var bad = (JsonObject)j.DeepClone(); bad[key] = value;
            Assert.False(EclipseCompositionalFluidPropertyParameters.TryParse(JsonSerializer.SerializeToElement(bad), out _));
        }
    }
}
