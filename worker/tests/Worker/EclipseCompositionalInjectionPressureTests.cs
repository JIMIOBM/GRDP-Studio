using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalInjectionPressureTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-injection-pressure", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalInjectionPressureTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseDeckPackage Package()
    {
        var file = Path.Combine(root, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        return new EclipseDeckPackageResolver().Resolve(file, root);
    }
    private static EclipseCompositionalInjectionPressureParameters Parameters(EclipseCompositionalReinjectionReader.Snapshot s)
    {
        var r = Assert.Single(s.Context.Records);
        return new(1, r.InjectionWell, s.Context.SourceFile, s.Context.SourceSha256, r.InjectionLineNumber, r.PressureUnit, r.InjectionBhpUpperLimit, 3550);
    }
    [Theory] [InlineData(3550, "OFFICIAL")] [InlineData(3900, "INACTIVE")]
    public async Task InjectorUpperLimitMatchesExactIndependentNativeBytesAndRetainsOriginalGasControls(double target, string golden)
    {
        var package = Package(); var before = EclipseCompositionalReinjectionReader.Read(package)!;
        var p = Parameters(before) with { TargetBhpUpperLimit = target };
        Assert.Equal("I", p.Well); Assert.Equal(457, p.LineNumber); Assert.Equal(4000, p.OriginalBhpUpperLimit);
        await EclipseCompositionalInjectionPressureEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, $"Fixtures/COMPOSITIONAL-INJECTION-PRESSURE-{golden}.DATA")), File.ReadAllBytes(package.MainFile));
        var after = EclipseCompositionalReinjectionReader.Read(package)!;
        Assert.Equal(Assert.Single(before.Context.Records) with { InjectionBhpUpperLimit = target }, Assert.Single(after.Context.Records));
    }
    [Fact]
    public async Task PortablePressureInputCanReturnExactlyToOriginalOfficialBytes()
    {
        var package = Package(); var bytes = File.ReadAllBytes(package.MainFile);
        await EclipseCompositionalInjectionPressureEditor.ApplyAsync(package, Parameters(EclipseCompositionalReinjectionReader.Read(package)!), TestContext.Current.CancellationToken);
        await EclipseCompositionalInjectionPressureEditor.ApplyAsync(package, Parameters(EclipseCompositionalReinjectionReader.Read(package)!) with { TargetBhpUpperLimit = 4000 }, TestContext.Current.CancellationToken);
        Assert.Equal(bytes, File.ReadAllBytes(package.MainFile));
    }
    [Theory]
    [InlineData("hash")] [InlineData("source")] [InlineData("well")] [InlineData("producer-row")] [InlineData("sales-row")] [InlineData("fraction-row")]
    [InlineData("unit")] [InlineData("original")] [InlineData("same")] [InlineData("zero")] [InlineData("negative")] [InlineData("high")]
    public async Task ForeignControlRowsOrInvalidPressureCannotChangeAnySourceBytes(string fault)
    {
        var package = Package(); var p = Parameters(EclipseCompositionalReinjectionReader.Read(package)!);
        p = fault switch {
            "hash" => p with { SourceSha256 = new('0', 64) }, "source" => p with { SourceFile = "OTHER.DATA" }, "well" => p with { Well = "P" },
            "producer-row" => p with { LineNumber = 433 }, "sales-row" => p with { LineNumber = 439 }, "fraction-row" => p with { LineNumber = 450 },
            "unit" => p with { Unit = "barsa" }, "original" => p with { OriginalBhpUpperLimit = 3999 }, "same" => p with { TargetBhpUpperLimit = 4000 },
            "zero" => p with { TargetBhpUpperLimit = 0 }, "negative" => p with { TargetBhpUpperLimit = -1 }, _ => p with { TargetBhpUpperLimit = 100_000_001 }
        };
        var bytes = File.ReadAllBytes(package.MainFile);
        Assert.False(EclipseCompositionalInjectionPressureEditor.Matches(EclipseCompositionalReinjectionReader.Read(package), p));
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseCompositionalInjectionPressureEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken));
        Assert.Equal(bytes, File.ReadAllBytes(package.MainFile));
    }
    [Theory] [InlineData("1*4000")] [InlineData("'4000'")]
    public async Task OnlyPressureNumberChangesInsideAnExistingLiteralWrapper(string literal)
    {
        var package = Package(); var text = File.ReadAllText(package.MainFile).Replace("2* 4000 /", "2* " + literal + " /", StringComparison.Ordinal);
        File.WriteAllText(package.MainFile, text); var s = EclipseCompositionalReinjectionReader.Read(package); Assert.NotNull(s);
        await EclipseCompositionalInjectionPressureEditor.ApplyAsync(package, Parameters(s), TestContext.Current.CancellationToken);
        Assert.Equal(text.Replace("2* " + literal + " /", "2* " + literal.Replace("4000", "3550", StringComparison.Ordinal) + " /", StringComparison.Ordinal), File.ReadAllText(package.MainFile));
    }
    [Fact]
    public void PressureProtocolIsSeparateFromProducerLowerLimitAndSalesOrFractionChanges()
    {
        var p = Parameters(EclipseCompositionalReinjectionReader.Read(Package())!);
        var json = new JsonObject { ["schemaVersion"] = EclipseCompositionalInjectionPressureParameters.Schema, ["baselineRunId"] = p.BaselineRunId,
            ["well"] = p.Well, ["sourceFile"] = p.SourceFile, ["sourceSha256"] = p.SourceSha256, ["lineNumber"] = p.LineNumber,
            ["unit"] = p.Unit, ["originalBhpUpperLimit"] = p.OriginalBhpUpperLimit, ["targetBhpUpperLimit"] = p.TargetBhpUpperLimit };
        Assert.True(EclipseCompositionalInjectionPressureParameters.TryParse(JsonSerializer.SerializeToElement(json), out var actual)); Assert.Equal(p, actual);
        foreach (var fault in new[] { "string", "extra", "producer", "unit", "zero", "negative", "high", "baseline" })
        {
            var copy = (JsonObject)json.DeepClone();
            if (fault == "string") copy["targetBhpUpperLimit"] = "3550";
            if (fault == "extra") copy["targetFraction"] = 1;
            if (fault == "producer") copy["schemaVersion"] = EclipseCompositionalPressureParameters.Schema;
            if (fault == "unit") copy["unit"] = "barsa";
            if (fault == "zero") copy["targetBhpUpperLimit"] = 0;
            if (fault == "negative") copy["targetBhpUpperLimit"] = -1;
            if (fault == "high") copy["targetBhpUpperLimit"] = 100_000_001;
            if (fault == "baseline") copy["baselineRunId"] = "1";
            Assert.False(EclipseCompositionalInjectionPressureParameters.TryParse(JsonSerializer.SerializeToElement(copy), out _));
        }
    }
}
