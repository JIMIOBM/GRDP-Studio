using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalGasSalesTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-gas-sales", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalGasSalesTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseDeckPackage Package()
    {
        var file = Path.Combine(root, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        return new EclipseDeckPackageResolver().Resolve(file, root);
    }
    private static EclipseCompositionalGasSalesParameters Parameters(EclipseCompositionalReinjectionReader.Snapshot s)
    {
        var r = Assert.Single(s.Context.Records);
        return new(1, r.Group, r.Phase, s.Context.SourceFile, s.Context.SourceSha256, r.SalesLineNumber, r.RateUnit, r.SalesTarget, 3000);
    }
    [Theory]
    [InlineData(3000, "OFFICIAL")] [InlineData(7000, "UNAVAILABLE")]
    public async Task SalesEditMatchesIndependentNativeBytesAndPreservesAllOtherControls(double target, string golden)
    {
        var package = Package(); var before = EclipseCompositionalReinjectionReader.Read(package)!;
        var p = Parameters(before) with { TargetSalesTarget = target };
        Assert.Equal(439, p.LineNumber); Assert.Equal(1500, p.OriginalSalesTarget);
        await EclipseCompositionalGasSalesEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, $"Fixtures/COMPOSITIONAL-SALES-{golden}.DATA")), File.ReadAllBytes(package.MainFile));
        var after = EclipseCompositionalReinjectionReader.Read(package)!;
        Assert.Equal(Assert.Single(before.Context.Records) with { SalesTarget = target }, Assert.Single(after.Context.Records));
    }
    [Fact]
    public async Task PortableSalesInputCanReturnByteExactlyToOfficialOriginal()
    {
        var package = Package(); var original = File.ReadAllBytes(package.MainFile);
        await EclipseCompositionalGasSalesEditor.ApplyAsync(package, Parameters(EclipseCompositionalReinjectionReader.Read(package)!), TestContext.Current.CancellationToken);
        var changed = Parameters(EclipseCompositionalReinjectionReader.Read(package)!) with { TargetSalesTarget = 1500 };
        await EclipseCompositionalGasSalesEditor.ApplyAsync(package, changed, TestContext.Current.CancellationToken);
        Assert.Equal(original, File.ReadAllBytes(package.MainFile));
    }
    [Theory]
    [InlineData("hash")] [InlineData("source")] [InlineData("group")] [InlineData("phase")] [InlineData("fraction-row")]
    [InlineData("unit")] [InlineData("original")] [InlineData("same")] [InlineData("negative")] [InlineData("too-high")]
    public async Task InvalidSalesBindingCannotWriteAnyInput(string fault)
    {
        var package = Package(); var p = Parameters(EclipseCompositionalReinjectionReader.Read(package)!);
        p = fault switch {
            "hash" => p with { SourceSha256 = new('0', 64) }, "source" => p with { SourceFile = "OTHER.DATA" }, "group" => p with { Group = "OTHER" },
            "phase" => p with { Phase = "WATER" }, "fraction-row" => p with { LineNumber = 450 }, "unit" => p with { Unit = "fraction" },
            "original" => p with { OriginalSalesTarget = 1499 }, "same" => p with { TargetSalesTarget = 1500 },
            "negative" => p with { TargetSalesTarget = -1 }, _ => p with { TargetSalesTarget = 100_000_001 }
        };
        var bytes = File.ReadAllBytes(package.MainFile);
        Assert.False(EclipseCompositionalGasSalesEditor.Matches(EclipseCompositionalReinjectionReader.Read(package), p));
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseCompositionalGasSalesEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken));
        Assert.Equal(bytes, File.ReadAllBytes(package.MainFile));
    }
    [Theory]
    [InlineData("1*1500")] [InlineData("'1500'")] [InlineData("'1*1500'")]
    public async Task LiteralWrapperRemainsIntactWhenOnlySalesNumericValueChanges(string literal)
    {
        var package = Package(); var text = File.ReadAllText(package.MainFile).Replace("Field 1500 /", "Field " + literal + " /", StringComparison.Ordinal);
        File.WriteAllText(package.MainFile, text);
        var s = EclipseCompositionalReinjectionReader.Read(package); Assert.NotNull(s);
        await EclipseCompositionalGasSalesEditor.ApplyAsync(package, Parameters(s), TestContext.Current.CancellationToken);
        Assert.Equal(text.Replace("Field " + literal + " /", "Field " + literal.Replace("1500", "3000", StringComparison.Ordinal) + " /", StringComparison.Ordinal), File.ReadAllText(package.MainFile));
    }
    [Fact]
    public void SalesProtocolIsClosedAndZeroRemainsANumericTarget()
    {
        var p = Parameters(EclipseCompositionalReinjectionReader.Read(Package())!);
        var json = new JsonObject { ["schemaVersion"] = EclipseCompositionalGasSalesParameters.Schema, ["baselineRunId"] = p.BaselineRunId,
            ["group"] = p.Group, ["phase"] = p.Phase, ["sourceFile"] = p.SourceFile, ["sourceSha256"] = p.SourceSha256,
            ["lineNumber"] = p.LineNumber, ["unit"] = p.Unit, ["originalSalesTarget"] = p.OriginalSalesTarget, ["targetSalesTarget"] = p.TargetSalesTarget };
        Assert.True(EclipseCompositionalGasSalesParameters.TryParse(JsonSerializer.SerializeToElement(json), out var actual)); Assert.Equal(p, actual);
        json["targetSalesTarget"] = 0;
        Assert.True(EclipseCompositionalGasSalesParameters.TryParse(JsonSerializer.SerializeToElement(json), out actual)); Assert.Equal(0, actual!.TargetSalesTarget);
        foreach (var fault in new[] { "string", "extra", "reinjection", "unit", "group", "phase", "range" })
        {
            var copy = (JsonObject)json.DeepClone();
            if (fault == "string") copy["targetSalesTarget"] = "3000";
            if (fault == "extra") copy["targetFraction"] = 1;
            if (fault == "reinjection") copy["schemaVersion"] = EclipseCompositionalReinjectionParameters.Schema;
            if (fault == "unit") copy["unit"] = "fraction";
            if (fault == "group") copy["group"] = "OTHER";
            if (fault == "phase") copy["phase"] = "WATER";
            if (fault == "range") copy["targetSalesTarget"] = 100_000_001;
            Assert.False(EclipseCompositionalGasSalesParameters.TryParse(JsonSerializer.SerializeToElement(copy), out _));
        }
    }
}
