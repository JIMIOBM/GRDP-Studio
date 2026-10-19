using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalReinjectionTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-reinjection", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalReinjectionTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseDeckPackage Package()
    {
        var file = Path.Combine(root, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        return new EclipseDeckPackageResolver().Resolve(file, root);
    }
    private static EclipseCompositionalReinjectionParameters Parameters(EclipseCompositionalReinjectionReader.Snapshot s)
    {
        var r = Assert.Single(s.Context.Records);
        return new(1, r.Group, r.Phase, s.Context.SourceFile, s.Context.SourceSha256, r.LineNumber, "fraction", r.OriginalFraction, .5);
    }
    [Fact]
    public void OfficialSourcePublishesNetAvailableGasControlsAndExactOriginalShutTime()
    {
        var package = Package(); var s = EclipseCompositionalReinjectionReader.Read(package); Assert.NotNull(s);
        var r = Assert.Single(s.Context.Records);
        Assert.Equal("FIELD", r.Group); Assert.Equal("GAS", r.Phase); Assert.Equal("REIN", r.ControlMode);
        Assert.Equal(450, r.LineNumber); Assert.Equal(1, r.OriginalFraction); Assert.Equal(1500, r.SalesTarget);
        Assert.Equal("GV", r.GasSourceKind); Assert.Equal("FIELD", r.GasSourceGroup);
        Assert.Equal("I", r.InjectionWell); Assert.Equal(4000, r.InjectionBhpUpperLimit);
        Assert.Equal("P", r.ProductionWell); Assert.Equal(6200, r.ProductionGasTarget); Assert.Equal(500, r.ProductionBhpLowerLimit);
        Assert.Equal("Mscf/day", r.RateUnit); Assert.Equal("psia", r.PressureUnit); Assert.Equal(3650, r.ShutAfterDays);
        Assert.True(r.ShutLineNumber > r.InjectionLineNumber);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(package.MainFile))), s.Context.SourceSha256);
        Assert.Equal(9, s.Context.ComponentCount); Assert.Equal("PR", s.Context.EquationOfState);
    }
    [Fact]
    public async Task SingleFractionEditExactlyMatchesIndependentNativeAndKeepsSalesStreamPressuresAndShut()
    {
        var package = Package(); var before = EclipseCompositionalReinjectionReader.Read(package)!;
        await EclipseCompositionalReinjectionEditor.ApplyAsync(package, Parameters(before), TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-REIN-OFFICIAL.DATA")), File.ReadAllBytes(package.MainFile));
        var after = EclipseCompositionalReinjectionReader.Read(package); Assert.NotNull(after);
        Assert.Equal(Assert.Single(before.Context.Records) with { OriginalFraction = .5 }, Assert.Single(after.Context.Records));
    }
    [Fact]
    public async Task HalfInputCanBindItsOwnNewSourceAndReturnExactlyToTheOfficialOriginal()
    {
        var package = Package(); var original = File.ReadAllBytes(package.MainFile);
        await EclipseCompositionalReinjectionEditor.ApplyAsync(package, Parameters(EclipseCompositionalReinjectionReader.Read(package)!), TestContext.Current.CancellationToken);
        var half = EclipseCompositionalReinjectionReader.Read(package)!;
        await EclipseCompositionalReinjectionEditor.ApplyAsync(package, Parameters(half) with { TargetFraction = 1 }, TestContext.Current.CancellationToken);
        Assert.Equal(original, File.ReadAllBytes(package.MainFile));
    }
    [Fact]
    public async Task ActualInspectionPublishesVersion16AndPreservesBothPhysicalSources()
    {
        var folder = Path.Combine(root, "models", "1"); Directory.CreateDirectory(folder);
        var file = Path.Combine(folder, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        var storage = new Grdp.SoftwareIntegration.Worker.Storage.StorageResolver(Microsoft.Extensions.Options.Options.Create(new WorkerOptions { StorageRoot = root }));
        var service = new Grdp.SoftwareIntegration.Worker.Inspection.EclipseDataInspectionService(storage);
        var sha = Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file)));
        var response = await service.InspectAsync(new("models/1/SPE3_GASCOND.DATA", sha), TestContext.Current.CancellationToken);
        Assert.Equal(200, response.HttpStatus);
        var body = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.ModelValidationResponse>(response.Body);
        var inspection = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.EclipseDataInspection>(body.Inspection);
        Assert.Equal("eclipse-data-inspection/22", inspection.SchemaVersion);
        Assert.NotNull(inspection.CompositionalFluidPropertyContext);
        Assert.NotNull(inspection.CompositionalProductionContext); Assert.NotNull(inspection.CompositionalReinjectionContext);
        Assert.Equal(inspection.CompositionalProductionContext.SourceSha256, inspection.CompositionalReinjectionContext.SourceSha256);
        Assert.Equal(sha, inspection.CompositionalReinjectionContext.SourceSha256);
        Assert.Null(inspection.ThermalInjectionContext); Assert.Null(inspection.ScheduleMetadata);
        var json = JsonSerializer.Serialize(inspection, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        await File.WriteAllTextAsync(Path.Combine(AppContext.BaseDirectory, "eclipse-compositional-reinjection-inspection.json"), json, TestContext.Current.CancellationToken);
        File.AppendAllText(file, "\nGRUPFUEL\nFIELD 100 /\n/\n");
        sha = Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file)));
        response = await service.InspectAsync(new("models/1/SPE3_GASCOND.DATA", sha), TestContext.Current.CancellationToken);
        body = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.ModelValidationResponse>(response.Body);
        inspection = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.EclipseDataInspection>(body.Inspection);
        Assert.Equal("eclipse-data-inspection/14", inspection.SchemaVersion); Assert.Null(inspection.CompositionalReinjectionContext);
    }
    [Theory]
    [InlineData("hash")] [InlineData("source")] [InlineData("group")] [InlineData("phase")] [InlineData("line")]
    [InlineData("unit")] [InlineData("original")] [InlineData("same")] [InlineData("negative")] [InlineData("too-high")]
    public async Task StaleOrForeignFractionCannotWriteAnySourceBytes(string fault)
    {
        var package = Package(); var p = Parameters(EclipseCompositionalReinjectionReader.Read(package)!);
        p = fault switch {
            "hash" => p with { SourceSha256 = new('0', 64) }, "source" => p with { SourceFile = "OTHER.DATA" }, "group" => p with { Group = "OTHER" },
            "phase" => p with { Phase = "WATER" }, "line" => p with { LineNumber = p.LineNumber + 1 }, "unit" => p with { Unit = "Mscf/day" },
            "original" => p with { OriginalFraction = .9 }, "same" => p with { TargetFraction = 1 }, "negative" => p with { TargetFraction = -.1 }, _ => p with { TargetFraction = 1.01 }
        };
        var bytes = File.ReadAllBytes(package.MainFile);
        Assert.False(EclipseCompositionalReinjectionEditor.Matches(EclipseCompositionalReinjectionReader.Read(package), p));
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseCompositionalReinjectionEditor.ApplyAsync(package, p, TestContext.Current.CancellationToken));
        Assert.Equal(bytes, File.ReadAllBytes(package.MainFile));
    }
    [Theory]
    [InlineData("makeup")] [InlineData("stream")] [InlineData("source-group")] [InlineData("sale-group")]
    [InlineData("rate")] [InlineData("later-group")] [InlineData("open")] [InlineData("completion-shut")]
    [InlineData("dates")] [InlineData("metric")] [InlineData("fuel")]
    public void UnsupportedGasAccountingOrTimeStructureDoesNotOfferAnEditor(string fault)
    {
        var package = Package(); var text = File.ReadAllText(package.MainFile);
        text = fault switch {
            "makeup" => text.Replace("Field GV Field /", "Field GV Field MAKEUP /", StringComparison.Ordinal),
            "stream" => text.Replace("Field GV Field /", "Field MIX MIXTURE /", StringComparison.Ordinal),
            "source-group" => text.Replace("Field GV Field /", "Field GV OTHER /", StringComparison.Ordinal),
            "sale-group" => text.Replace("Field 1500 /", "OTHER 1500 /", StringComparison.Ordinal),
            "rate" => text.Replace("I GAS OPEN GRUP", "I GAS OPEN RATE", StringComparison.Ordinal),
            "later-group" => text.Replace("\r\nSAVE", "\r\nGCONINJE\r\nField GAS REIN 2* 0.8 /\r\n/\r\nSAVE", StringComparison.Ordinal),
            "open" => text.Replace("I SHUT/", "I OPEN/", StringComparison.Ordinal),
            "completion-shut" => text.Replace("I SHUT/", "I SHUT 1 1 4 4 /", StringComparison.Ordinal),
            "dates" => text.Replace("\r\nEND", "\r\nDATES\r\n1 JAN 2020 /\r\n/\r\nEND", StringComparison.Ordinal),
            "metric" => text.Replace("\r\nFIELD\r\n", "\r\nMETRIC\r\n", StringComparison.Ordinal),
            _ => text.Replace("\r\nEND", "\r\nGRUPFUEL\r\nFIELD 100 /\r\n/\r\nEND", StringComparison.Ordinal)
        };
        Assert.NotEqual(File.ReadAllText(package.MainFile), text);
        File.WriteAllText(package.MainFile, text);
        Assert.Null(EclipseCompositionalReinjectionReader.Read(package));
    }
    [Fact]
    public void FractionProtocolIsStrictAndZeroIsARealNumericFraction()
    {
        var p = Parameters(EclipseCompositionalReinjectionReader.Read(Package())!);
        var json = new JsonObject { ["schemaVersion"] = EclipseCompositionalReinjectionParameters.Schema, ["baselineRunId"] = p.BaselineRunId,
            ["group"] = p.Group, ["phase"] = p.Phase, ["sourceFile"] = p.SourceFile, ["sourceSha256"] = p.SourceSha256,
            ["lineNumber"] = p.LineNumber, ["unit"] = p.Unit, ["originalFraction"] = p.OriginalFraction, ["targetFraction"] = p.TargetFraction };
        Assert.True(EclipseCompositionalReinjectionParameters.TryParse(JsonSerializer.SerializeToElement(json), out var actual)); Assert.Equal(p, actual);
        json["targetFraction"] = 0;
        Assert.True(EclipseCompositionalReinjectionParameters.TryParse(JsonSerializer.SerializeToElement(json), out actual)); Assert.Equal(0, actual!.TargetFraction);
        foreach (var fault in new[] { "string", "extra", "legacy", "unit", "group", "phase", "range" })
        {
            var copy = (JsonObject)json.DeepClone();
            if (fault == "string") copy["targetFraction"] = "0.5";
            if (fault == "extra") copy["targetGasRate"] = 2350;
            if (fault == "legacy") copy["schemaVersion"] = EclipseCompositionalProductionParameters.Schema;
            if (fault == "unit") copy["unit"] = "Mscf/day";
            if (fault == "group") copy["group"] = "OTHER";
            if (fault == "phase") copy["phase"] = "WATER";
            if (fault == "range") copy["targetFraction"] = 1.01;
            Assert.False(EclipseCompositionalReinjectionParameters.TryParse(JsonSerializer.SerializeToElement(copy), out _));
        }
    }
}
