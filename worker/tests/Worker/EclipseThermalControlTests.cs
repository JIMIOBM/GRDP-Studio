using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;
namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseThermalControlTests : IDisposable
{
    private readonly string root=Path.Combine(Path.GetTempPath(),"grdp-thermal-control",Guid.NewGuid().ToString("N"));
    public EclipseThermalControlTests()=>Directory.CreateDirectory(root);
    public void Dispose()=>Directory.Delete(root,true);
    private EclipseDeckPackage Package(string kind="OFFICIAL")
    {
        var file=Path.Combine(root,"THERM3.DATA");File.Copy(Path.Combine(AppContext.BaseDirectory,$"Fixtures/THERMAL-INJECTION-{kind}-BASELINE.DATA"),file);
        return new EclipseDeckPackageResolver().Resolve(file,root);
    }
    private static EclipseThermalInjectionParameters Parameters(EclipseThermalInjectionReader.Snapshot s,string field="SURFACE_INJECTION_RATE")
    {
        var r=Assert.Single(s.Context.Records);bool rate=field=="SURFACE_INJECTION_RATE";
        return new(1,r.Well,s.Context.SourceFile,s.Context.SourceSha256,r.ControlLineNumber,field,rate?r.RateUnit:"psia",rate?r.SurfaceInjectionRate:r.BhpUpperLimit,rate?25:700);
    }
    [Theory] [InlineData("OFFICIAL","SURFACE_INJECTION_RATE","RATE")] [InlineData("TEMPERATURE","SURFACE_INJECTION_RATE","RATE")]
    [InlineData("OFFICIAL","BHP_UPPER_LIMIT","PRESSURE")] [InlineData("TEMPERATURE","BHP_UPPER_LIMIT","PRESSURE")]
    public async Task RateAndPressureEditsExactlyMatchFourNewIndependentNativeInputs(string kind,string field,string golden)
    {
        var p=Package(kind);var s=EclipseThermalInjectionReader.Read(p);Assert.NotNull(s);var request=Parameters(s,field);
        Assert.Equal(Assert.Single(s.Context.Records).ControlLineNumber,request.LineNumber);
        Assert.NotEqual(Assert.Single(s.Context.Records).LineNumber,request.LineNumber);
        await EclipseThermalInjectionEditor.ApplyAsync(p,request,TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory,$"Fixtures/THERMAL-CONTROL-{kind}-{golden}.DATA")),File.ReadAllBytes(p.MainFile));
        var r=Assert.Single(EclipseThermalInjectionReader.Read(p)!.Context.Records);
        Assert.Equal(.7,r.OriginalSteamQuality);Assert.Equal(450,r.OriginalTemperature);
        Assert.Equal(field=="SURFACE_INJECTION_RATE"?25:37.5,r.SurfaceInjectionRate);Assert.Equal(field=="BHP_UPPER_LIMIT"?700:1000,r.BhpUpperLimit);
    }
    [Theory] [InlineData("hash")] [InlineData("well")] [InlineData("winjLine")] [InlineData("unit")] [InlineData("original")]
    [InlineData("zero")] [InlineData("same")]
    public async Task UnboundControlValuesCannotChangeAnyBytes(string fault)
    {
        var p=Package();var s=EclipseThermalInjectionReader.Read(p)!;var request=Parameters(s);
        request=fault switch {"hash"=>request with{SourceSha256=new('0',64)},"well"=>request with{Well="PNEAR"},"winjLine"=>request with{LineNumber=Assert.Single(s.Context.Records).LineNumber},
            "unit"=>request with{Unit="psia"},"original"=>request with{OriginalValue=37},"zero"=>request with{TargetValue=0},_=>request with{TargetValue=37.5}};
        var before=File.ReadAllBytes(p.MainFile);await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseThermalInjectionEditor.ApplyAsync(p,request,TestContext.Current.CancellationToken));Assert.Equal(before,File.ReadAllBytes(p.MainFile));
    }
    [Theory] [InlineData("SURFACE_INJECTION_RATE")] [InlineData("BHP_UPPER_LIMIT")]
    public void NewControlFieldsRequireSchemaTwoAndCannotBeSmuggledThroughSchemaOne(string field)
    {
        var p=Parameters(EclipseThermalInjectionReader.Read(Package())!,field);
        var n=JsonSerializer.SerializeToNode(new{schemaVersion=EclipseThermalInjectionParameters.ControlSchema,p.BaselineRunId,p.Well,p.SourceFile,p.SourceSha256,p.LineNumber,p.Field,p.Unit,p.OriginalValue,p.TargetValue},new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
        Assert.True(EclipseThermalInjectionParameters.TryParse(JsonSerializer.SerializeToElement(n),out var parsed));Assert.Equal(p,parsed);
        n["schemaVersion"]=EclipseThermalInjectionParameters.Schema;Assert.False(EclipseThermalInjectionParameters.TryParse(JsonSerializer.SerializeToElement(n),out _));
        n["schemaVersion"]=EclipseThermalInjectionParameters.ControlSchema;n["targetValue"]="25";Assert.False(EclipseThermalInjectionParameters.TryParse(JsonSerializer.SerializeToElement(n),out _));
    }
}
