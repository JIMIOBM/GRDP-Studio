using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;
namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseThermalInjectionTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(),"grdp-thermal-injection",Guid.NewGuid().ToString("N"));
    public EclipseThermalInjectionTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root,true);
    private EclipseDeckPackage Package(string kind = "OFFICIAL")
    {
        var file=Path.Combine(root,"THERM3.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory,$"Fixtures/THERMAL-INJECTION-{kind}-BASELINE.DATA"),file);
        return new EclipseDeckPackageResolver().Resolve(file,root);
    }
    private static EclipseThermalInjectionParameters Parameters(EclipseThermalInjectionReader.Snapshot s,string field = "STEAM_QUALITY")
    {
        var r=Assert.Single(s.Context.Records);
        return new(1,r.Well,s.Context.SourceFile,s.Context.SourceSha256,r.LineNumber,field,
            field=="STEAM_QUALITY"?"fraction":r.TemperatureUnit,field=="STEAM_QUALITY"?r.OriginalSteamQuality:r.OriginalTemperature,field=="STEAM_QUALITY"?.5:400);
    }
    [Theory] [InlineData("OFFICIAL")] [InlineData("TEMPERATURE")]
    public void RealThermalSourceKeepsRatePressureAndSeparateEnthalpyInputs(string kind)
    {
        var p=Package(kind);var s=EclipseThermalInjectionReader.Read(p);Assert.NotNull(s);
        Assert.Equal(300,s.Context.Solver);Assert.Equal(3,s.Context.ComponentCount);Assert.Equal("LIVEOIL",s.Context.FluidModel);
        Assert.Equal(new[]{"C1","C2","HEAVY"},s.Context.ComponentNames);
        var r=Assert.Single(s.Context.Records);Assert.Equal("I",r.Well);Assert.Equal(.7,r.OriginalSteamQuality);Assert.Equal(450,r.OriginalTemperature);
        Assert.Equal("degF",r.TemperatureUnit);Assert.Equal(37.5,r.SurfaceInjectionRate);Assert.Equal(1000,r.BhpUpperLimit);Assert.True(r.ControlLineNumber<r.LineNumber);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(p.MainFile))),s.Context.SourceSha256);
    }
    [Theory]
    [InlineData("OFFICIAL","STEAM_QUALITY","QUALITY")] [InlineData("TEMPERATURE","STEAM_QUALITY","QUALITY")]
    [InlineData("OFFICIAL","INJECTION_TEMPERATURE","TEMPERATURE")] [InlineData("TEMPERATURE","INJECTION_TEMPERATURE","TEMPERATURE")]
    public async Task EditedBytesExactlyMatchIndependentlySolvedInputs(string kind,string field,string golden)
    {
        var p=Package(kind);var s=EclipseThermalInjectionReader.Read(p);Assert.NotNull(s);
        await EclipseThermalInjectionEditor.ApplyAsync(p,Parameters(s,field),TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory,$"Fixtures/THERMAL-INJECTION-{kind}-{golden}.DATA")),File.ReadAllBytes(p.MainFile));
        var r=Assert.Single(EclipseThermalInjectionReader.Read(p)!.Context.Records);
        Assert.Equal(field=="STEAM_QUALITY"?.5:.7,r.OriginalSteamQuality);Assert.Equal(field=="INJECTION_TEMPERATURE"?400:450,r.OriginalTemperature);
        Assert.Equal(37.5,r.SurfaceInjectionRate);Assert.Equal(1000,r.BhpUpperLimit);
    }
    [Theory] [InlineData("hash")] [InlineData("well")] [InlineData("line")] [InlineData("unit")]
    [InlineData("field")] [InlineData("original")] [InlineData("target")] [InlineData("same")]
    public async Task MismatchedOrOutOfRangeValuesCannotWriteAnyInput(string fault)
    {
        var p=Package();var value=Parameters(EclipseThermalInjectionReader.Read(p)!);
        value=fault switch {"hash"=>value with{SourceSha256=new('0',64)},"well"=>value with{Well="PNEAR"},"line"=>value with{LineNumber=1},
            "unit"=>value with{Unit="degF"},"field"=>value with{Field="BHP"},"original"=>value with{OriginalValue=.6},"target"=>value with{TargetValue=1.01},_=>value with{TargetValue=.7}};
        var before=File.ReadAllBytes(p.MainFile);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseThermalInjectionEditor.ApplyAsync(p,value,TestContext.Current.CancellationToken));
        Assert.Equal(before,File.ReadAllBytes(p.MainFile));
    }
    [Theory] [InlineData("ACTIONX")] [InlineData("UDQ")] [InlineData("RESTART")] [InlineData("WINJTEMP")] [InlineData("WCONINJE")]
    public void DynamicOrLaterInjectionOverridesDoNotOfferAnInitialEditor(string keyword)
    {
        var p=Package();File.AppendAllText(p.MainFile,"\n"+keyword+"\n I .5 400 /\n/\n");Assert.Null(EclipseThermalInjectionReader.Read(p));
    }
    [Fact] public void StrictFieldUnitsAndNumericTypesCannotBeReplacedByE100OrCompositionalParameters()
    {
        var p=Parameters(EclipseThermalInjectionReader.Read(Package())!);
        var input=JsonSerializer.SerializeToElement(new{schemaVersion=EclipseThermalInjectionParameters.Schema,p.BaselineRunId,p.Well,p.SourceFile,p.SourceSha256,p.LineNumber,p.Field,p.Unit,p.OriginalValue,p.TargetValue},new JsonSerializerOptions(JsonSerializerDefaults.Web));
        Assert.True(EclipseThermalInjectionParameters.TryParse(input,out var parsed));Assert.Equal(p,parsed);
        Assert.False(EclipseCompositionalProductionParameters.TryParse(input,out _));
        foreach(var fault in new[]{"string","extra","path","baseline","unit","range","schema"})
        {
            var n=JsonNode.Parse(input.GetRawText())!;
            if(fault=="string")n["targetValue"]="0.5";if(fault=="extra")n["bhpUpperLimit"]=2000;if(fault=="path")n["sourceFile"]="../THERM3.DATA";
            if(fault=="baseline")n["baselineRunId"]="1";if(fault=="unit")n["unit"]="degC";if(fault=="range")n["targetValue"]=1.01;if(fault=="schema")n["schemaVersion"]="eclipse-compositional-production-parameters/1";
            Assert.False(EclipseThermalInjectionParameters.TryParse(JsonSerializer.SerializeToElement(n),out _));
        }
        Assert.True(EclipseThermalInjectionParameters.Value("STEAM_QUALITY","fraction",0));Assert.True(EclipseThermalInjectionParameters.Value("STEAM_QUALITY","fraction",1));
        Assert.False(EclipseThermalInjectionParameters.Value("INJECTION_TEMPERATURE","degF",-459.67));Assert.False(EclipseThermalInjectionParameters.Value("INJECTION_TEMPERATURE","degC",-273.15));
    }
    [Fact] public async Task BomAndNonAsciiCommentsArePreservedOutsideTheOneSelectedToken()
    {
        var p=Package();var text="\ufeff-- 保留原始热采注释\r\n"+File.ReadAllText(p.MainFile);File.WriteAllBytes(p.MainFile,Encoding.UTF8.GetBytes(text));
        var s=EclipseThermalInjectionReader.Read(p);Assert.NotNull(s);
        await EclipseThermalInjectionEditor.ApplyAsync(p,Parameters(s),TestContext.Current.CancellationToken);
        Assert.Equal(Encoding.UTF8.GetBytes(text.Replace("0.70 450","0.50 450",StringComparison.Ordinal)),File.ReadAllBytes(p.MainFile));
    }
    [Theory] [InlineData("OFFICIAL")] [InlineData("TEMPERATURE")]
    public async Task InspectionServicePublishesThermalContextOnlyForTheCompleteSingleFile(string kind)
    {
        var models=Path.Combine(root,"models");Directory.CreateDirectory(models);var file=Path.Combine(models,"THERM3.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory,$"Fixtures/THERMAL-INJECTION-{kind}-BASELINE.DATA"),file);
        var service=new Grdp.SoftwareIntegration.Worker.Inspection.EclipseDataInspectionService(new Grdp.SoftwareIntegration.Worker.Storage.StorageResolver(
            Microsoft.Extensions.Options.Options.Create(new WorkerOptions{StorageRoot=root})));
        var hash=Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file)));
        var response=await service.InspectAsync(new("models/THERM3.DATA",hash),TestContext.Current.CancellationToken);Assert.Equal(200,response.HttpStatus);
        var body=Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.ModelValidationResponse>(response.Body);
        var i=Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.EclipseDataInspection>(body.Inspection);
        Assert.Equal("eclipse-data-inspection/23",i.SchemaVersion);Assert.NotNull(i.ThermalRockContext);Assert.NotNull(i.ThermalInjectionContext);Assert.Null(i.CompositionalProductionContext);Assert.Null(i.ScheduleMetadata);
        Assert.Equal(hash,i.ThermalInjectionContext.SourceSha256);
        File.WriteAllText(Path.Combine(models,"README.txt"),"Retain original companion.");
        response=await service.InspectAsync(new("models/THERM3.DATA",hash),TestContext.Current.CancellationToken);Assert.Equal(200,response.HttpStatus);
        body=Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.ModelValidationResponse>(response.Body);i=Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.EclipseDataInspection>(body.Inspection);
        Assert.Null(i.ThermalInjectionContext);Assert.Equal(2,i.PackageFiles!.Count);
    }
    [Theory] [InlineData("hash")] [InlineData("well")] [InlineData("line")] [InlineData("unit")] [InlineData("original")] [InlineData("e100")] [InlineData("compositional")]
    public async Task WrongSourceOrSimulatorCannotClaimARunBeforeLauncherChecks(string fault)
    {
        var models=Path.Combine(root,"models");Directory.CreateDirectory(models);var file=Path.Combine(models,"THERM3.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory,"Fixtures/THERMAL-INJECTION-OFFICIAL-BASELINE.DATA"),file);
        var value=Parameters(EclipseThermalInjectionReader.Read(new EclipseDeckPackageResolver().Resolve(file,models))!);
        value=fault switch {"hash"=>value with{SourceSha256=new('0',64)},"well"=>value with{Well="PNEAR"},"line"=>value with{LineNumber=1},
            "unit"=>value with{Unit="degF"},"original"=>value with{OriginalValue=.6},_=>value};
        if(fault=="e100")File.Copy(Path.Combine(AppContext.BaseDirectory,"Fixtures/ECLIPSE_PVDG_GASWATER.DATA"),file,true);
        if(fault=="compositional")File.Copy(Path.Combine(AppContext.BaseDirectory,"Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"),file,true);
        var input=JsonSerializer.SerializeToElement(new{schemaVersion=EclipseThermalInjectionParameters.Schema,value.BaselineRunId,value.Well,value.SourceFile,value.SourceSha256,value.LineNumber,value.Field,value.Unit,value.OriginalValue,value.TargetValue},new JsonSerializerOptions(JsonSerializerDefaults.Web));
        var options=Microsoft.Extensions.Options.Options.Create(new WorkerOptions{StorageRoot=root});using var coordinator=new EclipseExecutionCoordinator();
        using var service=new EclipseRunService(new Grdp.SoftwareIntegration.Worker.Storage.StorageResolver(options),coordinator,new PtkRunRegistry(new WorkerIdentity(options)),new EclipseLauncher(options),new EclipseDeckPackageResolver(),options);
        var response=await service.SubmitAsync(new(1,"models/THERM3.DATA",Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file))),null,"eclipse",input,60),TestContext.Current.CancellationToken);
        Assert.Equal(400,response.HttpStatus);Assert.False(Directory.Exists(Path.Combine(root,"runs")));
    }
    [Theory] [InlineData(.12345678901234567)] [InlineData(1e-20)]
    public async Task DecimalStylePaddingNeverRoundsAwayTargetPrecision(double target)
    {
        var p=Package();var value=Parameters(EclipseThermalInjectionReader.Read(p)!) with{TargetValue=target};
        await EclipseThermalInjectionEditor.ApplyAsync(p,value,TestContext.Current.CancellationToken);
        Assert.Equal(target,Assert.Single(EclipseThermalInjectionReader.Read(p)!.Context.Records).OriginalSteamQuality);
    }

    [Theory] [InlineData(true,"_CASE.DATA")] [InlineData(false,"_CASE.DATA")]
    [InlineData(true,"热采.DATA")] [InlineData(false,"组分.DATA")]
    public void OptionalEditorsNeverPublishSourcesThatTheirParameterContractCannotEncode(bool thermal,string filename)
    {
        var file=Path.Combine(root,filename);
        File.Copy(Path.Combine(AppContext.BaseDirectory,thermal?"Fixtures/THERMAL-INJECTION-OFFICIAL-BASELINE.DATA":"Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"),file);
        var package=new EclipseDeckPackageResolver().Resolve(file,root);
        if(thermal)Assert.Null(EclipseThermalInjectionReader.Read(package));else Assert.Null(EclipseCompositionalProductionReader.Read(package));
    }

}
