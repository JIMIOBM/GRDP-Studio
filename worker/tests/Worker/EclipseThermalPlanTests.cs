using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseThermalPlanTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(),"grdp-thermal-plan",Guid.NewGuid().ToString("N"));
    public EclipseThermalPlanTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root,true);
    private EclipseDeckPackage Package(string kind = "OFFICIAL") {
        var file=Path.Combine(root,"THERM3.DATA"); File.Copy(Path.Combine(AppContext.BaseDirectory,$"Fixtures/THERMAL-INJECTION-{kind}-BASELINE.DATA"),file);
        return new EclipseDeckPackageResolver().Resolve(file,root);
    }
    private static EclipseThermalPlanParameters Parameters(EclipseDeckPackage package, string plan = "two-stage") {
        var s=EclipseThermalInjectionReader.Read(package)!; var r=Assert.Single(s.Context.Records);
        EclipseThermalPlanStage[] stages = plan switch {
            "restore" => [new(730,20,430,.7,1000),new(1825,30,410,.6,900),new(2920,37.5,450,.7,1000)],
            "pressure-limited" => [new(1095,100,450,.7,700),new(2190,100,400,.7,700)],
            _ => [new(1095,25,450,.7,1000),new(2190,25,400,.7,1000)]
        };
        return new(1,r.Well,s.Context.SourceFile,s.Context.SourceSha256,r.LineNumber,r.ControlLineNumber,r.TemperatureUnit,r.RateUnit,
            r.RateUnit=="STB/day"?"psia":"barsa",new(r.SurfaceInjectionRate,r.OriginalTemperature,r.OriginalSteamQuality,r.BhpUpperLimit),stages);
    }
    private static JsonObject Json(EclipseThermalPlanParameters p) {
        var options=new JsonSerializerOptions(JsonSerializerDefaults.Web);
        var n=JsonSerializer.SerializeToNode(p,options)!.AsObject(); n["schemaVersion"]=EclipseThermalPlanParameters.Schema; return n;
    }
    [Theory]
    [InlineData("OFFICIAL","two-stage")][InlineData("TEMPERATURE","two-stage")]
    [InlineData("OFFICIAL","restore")][InlineData("TEMPERATURE","restore")]
    [InlineData("OFFICIAL","pressure-limited")][InlineData("TEMPERATURE","pressure-limited")]
    public async Task GeneratedInputExactlyMatchesIndependentlySolvedNativeCase(string kind,string plan) {
        var package=Package(kind); var p=Parameters(package,plan);
        Assert.True(EclipseThermalPlanParameters.TryParse(JsonSerializer.SerializeToElement(Json(p)),out var parsed));
        Assert.True(EclipseThermalPlanEditor.Matches(EclipseThermalInjectionReader.Read(package),parsed!));
        await EclipseThermalPlanEditor.ApplyAsync(package,parsed!,TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory,$"Fixtures/THERMAL-PLAN-{kind}-{plan}.DATA")),File.ReadAllBytes(package.MainFile));
        Assert.Equal("THERMAL",EclipseThermalReader.Read(package)!.ModelType);
        Assert.Null(EclipseThermalInjectionReader.Read(package));
    }
    [Theory][InlineData("hash")][InlineData("well")][InlineData("line")][InlineData("controlLine")][InlineData("original")][InlineData("unit")][InlineData("boundary")][InlineData("end")]
    public async Task MismatchCannotEditOrClaimAWorkerRun(string fault) {
        var models=Path.Combine(root,"models");Directory.CreateDirectory(models);
        var file=Path.Combine(models,"THERM3.DATA");File.Copy(Path.Combine(AppContext.BaseDirectory,"Fixtures/THERMAL-INJECTION-OFFICIAL-BASELINE.DATA"),file);
        var package=new EclipseDeckPackageResolver().Resolve(file,models);var p=Parameters(package);
        p=fault switch {"hash"=>p with{SourceSha256=new('0',64)},"well"=>p with{Well="PNEAR"},"line"=>p with{LineNumber=1},
            "controlLine"=>p with{ControlLineNumber=1},"original"=>p with{Original=p.Original with{SteamQuality=.6}},"unit"=>p with{TemperatureUnit="degC",RateUnit="sm3/day",PressureUnit="barsa"},
            "boundary"=>p with{Stages=[p.Stages[0] with{StartTimeDays=1094},p.Stages[1]]},_=>p with{Stages=[p.Stages[0],p.Stages[1] with{StartTimeDays=3650}]}};
        var before=File.ReadAllBytes(file);Assert.False(EclipseThermalPlanEditor.Matches(EclipseThermalInjectionReader.Read(package),p));
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseThermalPlanEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken));Assert.Equal(before,File.ReadAllBytes(file));
        var options=Microsoft.Extensions.Options.Options.Create(new WorkerOptions{StorageRoot=root});using var coordinator=new EclipseExecutionCoordinator();
        using var service=new EclipseRunService(new Grdp.SoftwareIntegration.Worker.Storage.StorageResolver(options),coordinator,new PtkRunRegistry(new WorkerIdentity(options)),new EclipseLauncher(options),new EclipseDeckPackageResolver(),options);
        var response=await service.SubmitAsync(new(1,"models/THERM3.DATA",Convert.ToHexStringLower(SHA256.HashData(before)),null,"eclipse",JsonSerializer.SerializeToElement(Json(p)),60),TestContext.Current.CancellationToken);
        Assert.Equal(400,response.HttpStatus);Assert.False(Directory.Exists(Path.Combine(root,"runs")));
    }
    [Theory][InlineData("one")][InlineData("nine")][InlineData("order")][InlineData("duplicate")][InlineData("noop")][InlineData("string")][InlineData("fraction")][InlineData("missing")][InlineData("extra")][InlineData("range")][InlineData("absoluteZero")][InlineData("schema")][InlineData("units")][InlineData("rootExtra")]
    public void StrictProtocolRejectsAmbiguityAndInvalidPhysics(string fault) {
        var p=Json(Parameters(Package()));var stages=p["stages"]!.AsArray();var first=stages[0]!.AsObject();
        switch(fault) {
            case "one":stages.RemoveAt(1);break;case "nine":for(int j=0;j<7;j++)stages.Add(first.DeepClone());break;
            case "order":first["startTimeDays"]=2500;break;case "duplicate":first["startTimeDays"]=2190;break;
            case "noop":foreach(var field in p["original"]!.AsObject())first[field.Key]=field.Value!.DeepClone();break;
            case "string":first["surfaceInjectionRate"]="25";break;case "fraction":first["startTimeDays"]=1095.5;break;
            case "missing":first.Remove("steamQuality");break;case "extra":first["endTimeDays"]=2000;break;
            case "range":first["steamQuality"]=1.1;break;case "absoluteZero":first["injectionTemperature"]=-459.67;break;
            case "schema":p["schemaVersion"]=EclipseThermalInjectionParameters.StageSchema;break;
            case "units":p["pressureUnit"]="barsa";break;case "rootExtra":p["targetValue"]=1;break;
        }
        Assert.False(EclipseThermalPlanParameters.TryParse(JsonSerializer.SerializeToElement(p),out _));
    }
}
