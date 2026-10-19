using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalProductionTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-target", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalProductionTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseDeckPackage Package()
    {
        var file = Path.Combine(root, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        return new EclipseDeckPackageResolver().Resolve(file, root);
    }
    private static EclipseCompositionalProductionParameters Parameters(EclipseCompositionalProductionReader.Snapshot s, double target = 4500)
    {
        var r = Assert.Single(s.Targets).Record;
        return new(1, r.Well, s.Context.SourceFile, s.Context.SourceSha256, r.LineNumber, r.Unit, r.OriginalGasRate, target);
    }
    [Fact]
    public async Task InspectionServicePublishesOnlyTheQualifiedE300ContextAndItsExactManifest()
    {
        var modelRoot = Path.Combine(root, "models", "1"); Directory.CreateDirectory(modelRoot);
        var file = Path.Combine(modelRoot, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        var storage = new Grdp.SoftwareIntegration.Worker.Storage.StorageResolver(Microsoft.Extensions.Options.Options.Create(new WorkerOptions { StorageRoot = root }));
        var service = new Grdp.SoftwareIntegration.Worker.Inspection.EclipseDataInspectionService(storage);
        var hash = Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file)));
        var response = await service.InspectAsync(new("models/1/SPE3_GASCOND.DATA", hash), TestContext.Current.CancellationToken);
        Assert.Equal(200, response.HttpStatus);
        var body = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.ModelValidationResponse>(response.Body);
        var inspection = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.EclipseDataInspection>(body.Inspection);
        Assert.Equal("eclipse-data-inspection/22", inspection.SchemaVersion);
        Assert.NotNull(inspection.CompositionalFluidPropertyContext); Assert.Null(inspection.ScheduleMetadata);
        Assert.NotNull(inspection.CompositionalReinjectionContext);
        Assert.NotNull(inspection.CompositionalProductionContext);
        Assert.Equal(hash, inspection.CompositionalProductionContext.SourceSha256);
        Assert.Equal(Assert.Single(inspection.PackageFiles!).SizeBytes, inspection.CompositionalProductionContext.SourceSizeBytes);
        File.AppendAllText(file, "\nACTIONX\n"); hash = Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file)));
        response = await service.InspectAsync(new("models/1/SPE3_GASCOND.DATA", hash), TestContext.Current.CancellationToken);
        body = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.ModelValidationResponse>(response.Body);
        inspection = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.EclipseDataInspection>(body.Inspection);
        Assert.Null(inspection.CompositionalProductionContext);
    }

    [Fact]
    public void RealNineComponentSourceCarriesTheGasTargetAndSeparatePressureLimit()
    {
        var p = Package(); var s = EclipseCompositionalProductionReader.Read(p); Assert.NotNull(s);
        Assert.Equal(300, s.Context.Solver); Assert.Equal(9, s.Context.ComponentCount); Assert.Equal("PR", s.Context.EquationOfState);
        Assert.Equal(new[] {"CO2", "N2", "C1", "C2", "C3", "C4-6", "C7+1", "C7+2", "C7+3"}, s.Context.ComponentNames);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(p.MainFile))), s.Context.SourceSha256);
        var r = Assert.Single(s.Context.Records); Assert.Equal("P", r.Well); Assert.Equal("GRAT", r.ControlMode);
        Assert.Equal(6200, r.OriginalGasRate); Assert.Equal(500, r.BhpLimit); Assert.Equal("Mscf/day", r.Unit);
        Assert.True(r.BhpLineNumber > r.LineNumber);
    }
    [Theory]
    [InlineData(4500, "TARGET")]
    [InlineData(12000, "HIGH")]
    public async Task EditedInputExactlyMatchesTheIndependentlySolvedNativeInput(double target, string golden)
    {
        var p = Package(); var parameters = Parameters(EclipseCompositionalProductionReader.Read(p)!, target);
        await EclipseCompositionalProductionEditor.ApplyAsync(p, parameters, TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-" + golden + ".DATA")), File.ReadAllBytes(p.MainFile));
        var after = EclipseCompositionalProductionReader.Read(p); Assert.NotNull(after);
        Assert.Equal(target, Assert.Single(after.Context.Records).OriginalGasRate);
        Assert.Equal(500, Assert.Single(after.Context.Records).BhpLimit);
    }
    [Theory]
    [InlineData("hash")] [InlineData("source")] [InlineData("well")] [InlineData("line")]
    [InlineData("unit")] [InlineData("original")] [InlineData("same")] [InlineData("target")]
    public async Task MismatchedBindingCannotWriteAnySourceBytes(string fault)
    {
        var p = Package(); var e = Parameters(EclipseCompositionalProductionReader.Read(p)!);
        e = fault switch { "hash" => e with { SourceSha256 = new('0', 64) }, "source" => e with { SourceFile = "OTHER.DATA" },
            "well" => e with { Well = "I" }, "line" => e with { LineNumber = e.LineNumber + 1 }, "unit" => e with { Unit = "sm3/day" },
            "original" => e with { OriginalGasRate = 6199 }, "same" => e with { TargetGasRate = 6200 }, _ => e with { TargetGasRate = -1 } };
        var before = File.ReadAllBytes(p.MainFile);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseCompositionalProductionEditor.ApplyAsync(p, e, TestContext.Current.CancellationToken));
        Assert.Equal(before, File.ReadAllBytes(p.MainFile));
    }
    [Theory]
    [InlineData("ACTIONX")] [InlineData("RESTART")] [InlineData("PATHS")] [InlineData("WELTARG")] [InlineData("WCONPROD")]
    public void DynamicOrLaterProducerControlCannotOfferAnInitialTargetEditor(string keyword)
    {
        var p = Package();
        File.AppendAllText(p.MainFile, "\n" + keyword + "\n P BHP 500 /\n/\n");
        Assert.Null(EclipseCompositionalProductionReader.Read(p));
    }
    [Fact]
    public async Task Utf8BomAndCommentsStayExactWhilePhysicalSourceRowsMoveTogether()
    {
        var p = Package(); var text = "\ufeff-- 保留组分模型原注释\r\n" + File.ReadAllText(p.MainFile);
        File.WriteAllBytes(p.MainFile, Encoding.UTF8.GetBytes(text));
        var s = EclipseCompositionalProductionReader.Read(p); Assert.NotNull(s);
        await EclipseCompositionalProductionEditor.ApplyAsync(p, Parameters(s), TestContext.Current.CancellationToken);
        Assert.Equal(Encoding.UTF8.GetBytes(text.Replace("P OPEN GRAT 1* 1* 6200 1* /", "P OPEN GRAT 1* 1* 4500 1* /", StringComparison.Ordinal)), File.ReadAllBytes(p.MainFile));
    }
    [Fact]
    public void DedicatedCompositionalSchemaKeepsStrictNumbersPathsAndBaselineTypes()
    {
        var p = Parameters(EclipseCompositionalProductionReader.Read(Package())!);
        var value = JsonSerializer.SerializeToElement(new { schemaVersion = EclipseCompositionalProductionParameters.Schema, p.BaselineRunId,
            p.Well, p.SourceFile, p.SourceSha256, p.LineNumber, p.Unit, p.OriginalGasRate, p.TargetGasRate }, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
        Assert.True(EclipseCompositionalProductionParameters.TryParse(value, out var parsed)); Assert.Equal(p, parsed);
        Assert.False(EclipseProductionTargetParameters.TryParse(value, out _));
        foreach (var fault in new[] { "string", "path", "extra", "baseline", "schema" })
        {
            var n = JsonNode.Parse(value.GetRawText())!;
            if (fault == "string") n["targetGasRate"] = "4500";
            if (fault == "path") n["sourceFile"] = "../SPE3_GASCOND.DATA";
            if (fault == "extra") n["extra"] = true;
            if (fault == "baseline") n["baselineRunId"] = "1";
            if (fault == "schema") n["schemaVersion"] = "eclipse-production-target-parameters/1";
            Assert.False(EclipseCompositionalProductionParameters.TryParse(JsonSerializer.SerializeToElement(n), out _));
        }
    }
    [Theory]
    [InlineData("hash")] [InlineData("source")] [InlineData("well")] [InlineData("line")]
    [InlineData("unit")] [InlineData("original")] [InlineData("e100")] [InlineData("legacy")]
    public async Task SubmissionRejectsStaleAndCrossSimulatorTargetsBeforeClaimingAnyRun(string fault)
    {
        var modelRoot = Path.Combine(root, "models"); Directory.CreateDirectory(modelRoot);
        var file = Path.Combine(modelRoot, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        var snapshot = EclipseCompositionalProductionReader.Read(new EclipseDeckPackageResolver().Resolve(file, modelRoot))!;
        var p = Parameters(snapshot);
        p = fault switch { "hash" => p with { SourceSha256 = new('0',64) }, "source" => p with { SourceFile = "OTHER.DATA" },
            "well" => p with { Well = "I" }, "line" => p with { LineNumber = 431 }, "unit" => p with { Unit = "sm3/day" },
            "original" => p with { OriginalGasRate = 6199 }, _ => p };
        if (fault == "e100") File.Copy(Path.Combine(AppContext.BaseDirectory,"Fixtures/ECLIPSE_PVDG_GASWATER.DATA"),file,true);
        var value = JsonSerializer.SerializeToElement(new { schemaVersion = EclipseCompositionalProductionParameters.Schema, p.BaselineRunId,
            p.Well,p.SourceFile,p.SourceSha256,p.LineNumber,p.Unit,p.OriginalGasRate,p.TargetGasRate },new JsonSerializerOptions(JsonSerializerDefaults.Web));
        if (fault == "legacy") value = JsonSerializer.SerializeToElement(new { schemaVersion="eclipse-production-target-parameters/1", baselineRunId=1,
            well="P",phase="INITIAL",sourceFile="SPE3_GASCOND.DATA",lineNumber=430,unit="STB/day",originalOilRate=20000,targetOilRate=10000 });
        var options = Microsoft.Extensions.Options.Options.Create(new WorkerOptions { StorageRoot = root });
        using var coordinator = new EclipseExecutionCoordinator();
        using var service = new EclipseRunService(new Grdp.SoftwareIntegration.Worker.Storage.StorageResolver(options), coordinator,
            new PtkRunRegistry(new WorkerIdentity(options)), new EclipseLauncher(options),new EclipseDeckPackageResolver(),options);
        var response = await service.SubmitAsync(new(1,"models/SPE3_GASCOND.DATA",Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file))),null,"eclipse",value,60),TestContext.Current.CancellationToken);
        Assert.Equal(400,response.HttpStatus); Assert.False(Directory.Exists(Path.Combine(root,"runs")));
    }

    [Fact]
    public async Task UnreferencedExtraFileKeepsCompositionalModelRunnableButDoesNotOfferIncompletePackageEditing()
    {
        var modelRoot=Path.Combine(root,"models");Directory.CreateDirectory(modelRoot);
        var file=Path.Combine(modelRoot,"SPE3_GASCOND.DATA");File.Copy(Path.Combine(AppContext.BaseDirectory,"Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"),file);
        File.WriteAllText(Path.Combine(modelRoot,"README.txt"),"Keep this original companion file.");
        Assert.Null(EclipseCompositionalProductionReader.Read(new EclipseDeckPackageResolver().Resolve(file,modelRoot)));
        var service = new Grdp.SoftwareIntegration.Worker.Inspection.EclipseDataInspectionService(new Grdp.SoftwareIntegration.Worker.Storage.StorageResolver(
            Microsoft.Extensions.Options.Options.Create(new WorkerOptions{StorageRoot=root})));
        var response=await service.InspectAsync(new("models/SPE3_GASCOND.DATA",Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file)))),TestContext.Current.CancellationToken);
        Assert.Equal(200,response.HttpStatus);
        var body=Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.ModelValidationResponse>(response.Body);
        var inspection=Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.EclipseDataInspection>(body.Inspection);
        Assert.Equal("eclipse_300",body.ModelKind);Assert.Equal(2,inspection.PackageFiles!.Count);Assert.Null(inspection.CompositionalProductionContext);
    }

}
