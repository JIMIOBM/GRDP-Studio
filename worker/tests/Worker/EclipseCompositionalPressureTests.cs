using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalPressureTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-pressure", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalPressureTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseDeckPackage Package()
    {
        var file = Path.Combine(root, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        return new EclipseDeckPackageResolver().Resolve(file, root);
    }
    private static EclipseCompositionalPressureParameters Parameters(EclipseCompositionalProductionReader.Snapshot s)
    {
        var r = Assert.Single(s.Targets).Record;
        return new(1, r.Well, s.Context.SourceFile, s.Context.SourceSha256, r.BhpLineNumber, "psia", r.BhpLimit, 1500);
    }

    [Fact]
    public async Task OfficialPressureInputExactlyMatchesIndependentNativeAndRetainsGasTargetAndPhysics()
    {
        var p = Package(); var s = EclipseCompositionalProductionReader.Read(p)!;
        var target = Parameters(s);
        Assert.True(EclipseCompositionalPressureEditor.Matches(s, target));
        Assert.Equal(433, target.LineNumber);
        await EclipseCompositionalPressureEditor.ApplyAsync(p, target, TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-PRESSURE-OFFICIAL.DATA")), File.ReadAllBytes(p.MainFile));
        var after = EclipseCompositionalProductionReader.Read(p)!;
        Assert.Equal(1500, Assert.Single(after.Context.Records).BhpLimit);
        Assert.Equal(6200, Assert.Single(after.Context.Records).OriginalGasRate);
        Assert.Equal(s.Context.ComponentNames, after.Context.ComponentNames);
        Assert.Equal("PR", after.Context.EquationOfState);
    }

    [Fact]
    public void APressureRecordBeforeItsProducerControlDoesNotPublishAnEditableBinding()
    {
        var package = Package(); var text = File.ReadAllText(package.MainFile);
        var pattern = "(?m)^WELTARG[ \t]*\r?\nP BHP 500 /\r?\n/\r?\n";
        var block = System.Text.RegularExpressions.Regex.Match(text, pattern).Value;
        Assert.NotEmpty(block);
        text = text.Replace(block, "", StringComparison.Ordinal);
        text = System.Text.RegularExpressions.Regex.Replace(text, "(?m)^WCONPROD", _ => block + "WCONPROD");
        File.WriteAllText(package.MainFile, text);
        Assert.Null(EclipseCompositionalProductionReader.Read(package));
    }

    [Theory]
    [InlineData("hash")] [InlineData("source")] [InlineData("well")] [InlineData("gas-row")]
    [InlineData("unit")] [InlineData("original")] [InlineData("same")] [InlineData("zero")]
    public async Task InvalidSourceOrPressureBindingCannotChangeAnyInputBytes(string fault)
    {
        var p = Package(); var s = EclipseCompositionalProductionReader.Read(p)!; var target = Parameters(s);
        target = fault switch {
            "hash" => target with { SourceSha256 = new('0', 64) }, "source" => target with { SourceFile = "OTHER.DATA" },
            "well" => target with { Well = "I" }, "gas-row" => target with { LineNumber = Assert.Single(s.Targets).Record.LineNumber },
            "unit" => target with { Unit = "barsa" }, "original" => target with { OriginalBhpLowerLimit = 499 },
            "same" => target with { TargetBhpLowerLimit = 500 }, _ => target with { TargetBhpLowerLimit = 0 }
        };
        var before = File.ReadAllBytes(p.MainFile);
        Assert.False(EclipseCompositionalPressureEditor.Matches(s, target));
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseCompositionalPressureEditor.ApplyAsync(p, target, TestContext.Current.CancellationToken));
        Assert.Equal(before, File.ReadAllBytes(p.MainFile));
    }

    [Fact]
    public void DedicatedPressureProtocolNeverWidensTheOriginalGasProtocol()
    {
        var p = Parameters(EclipseCompositionalProductionReader.Read(Package())!);
        var json = new JsonObject {
            ["schemaVersion"] = EclipseCompositionalPressureParameters.Schema, ["baselineRunId"] = p.BaselineRunId,
            ["well"] = p.Well, ["sourceFile"] = p.SourceFile, ["sourceSha256"] = p.SourceSha256,
            ["lineNumber"] = p.LineNumber, ["unit"] = p.Unit,
            ["originalBhpLowerLimit"] = p.OriginalBhpLowerLimit, ["targetBhpLowerLimit"] = p.TargetBhpLowerLimit
        };
        Assert.True(EclipseCompositionalPressureParameters.TryParse(JsonSerializer.SerializeToElement(json), out var parsed));
        Assert.Equal(p, parsed);
        Assert.False(EclipseCompositionalProductionParameters.TryParse(JsonSerializer.SerializeToElement(json), out _));
        foreach (var fault in new[] { "schema", "extra", "string", "unit", "same", "zero" })
        {
            var copy = (JsonObject)json.DeepClone();
            if (fault == "schema") copy["schemaVersion"] = EclipseCompositionalProductionParameters.Schema;
            if (fault == "extra") copy["targetGasRate"] = 6200;
            if (fault == "string") copy["targetBhpLowerLimit"] = "1500";
            if (fault == "unit") copy["unit"] = "Mscf/day";
            if (fault == "same") copy["targetBhpLowerLimit"] = 500;
            if (fault == "zero") copy["targetBhpLowerLimit"] = 0;
            Assert.False(EclipseCompositionalPressureParameters.TryParse(JsonSerializer.SerializeToElement(copy), out _));
        }
    }
}
