using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseThermalRockTests : IDisposable
{
    private readonly string root=Path.Combine(Path.GetTempPath(),"grdp-thermal-rock",Guid.NewGuid().ToString("N"));
    public EclipseThermalRockTests()=>Directory.CreateDirectory(root);
    public void Dispose()=>Directory.Delete(root,true);
    private EclipseDeckPackage Package(string kind="OFFICIAL"){
        var file=Path.Combine(root,"THERM3.DATA");File.Copy(Path.Combine(AppContext.BaseDirectory,$"Fixtures/THERMAL-INJECTION-{kind}-BASELINE.DATA"),file);return new EclipseDeckPackageResolver().Resolve(file,root);
    }
    private static EclipseThermalRockParameters Parameters(EclipseDeckPackage package,string field="HEATCR"){
        var s=EclipseThermalRockReader.Read(package)!;var r=Assert.Single(s.Targets.Where(t=>t.Record.Field==field)).Record;
        return new(1,s.Context.SourceFile,s.Context.SourceSha256,field,r.LineNumber,s.Context.CellCount,r.Unit,r.OriginalValue,field=="HEATCR"?45:12);
    }
    private static JsonObject Json(EclipseThermalRockParameters p){var n=JsonSerializer.SerializeToNode(p,new JsonSerializerOptions(JsonSerializerDefaults.Web))!.AsObject();n["schemaVersion"]=EclipseThermalRockParameters.Schema;return n;}
    [Theory][InlineData("OFFICIAL","HEATCR","heat-capacity")][InlineData("TEMPERATURE","HEATCR","heat-capacity")][InlineData("OFFICIAL","THCONR","conductivity")][InlineData("TEMPERATURE","THCONR","conductivity")]
    public async Task AppliedBytesMatchIndependentlySolvedNativePropertyCase(string kind,string field,string golden){
        var package=Package(kind);var p=Parameters(package,field);var s=EclipseThermalRockReader.Read(package);Assert.NotNull(s);Assert.Equal(180,s.Context.CellCount);Assert.Equal(2,s.Targets.Count);
        Assert.True(EclipseThermalRockParameters.TryParse(JsonSerializer.SerializeToElement(Json(p)),out var parsed));Assert.Equal(p,parsed);
        await EclipseThermalRockEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory,$"Fixtures/THERMAL-ROCK-{kind}-{golden}.DATA")),File.ReadAllBytes(package.MainFile));
        var changed=EclipseThermalRockReader.Read(package)!;Assert.Equal(p.TargetValue,Assert.Single(changed.Targets.Where(t=>t.Record.Field==field)).Record.OriginalValue);
        Assert.NotEqual(p.SourceSha256,changed.Context.SourceSha256);Assert.NotNull(EclipseThermalInjectionReader.Read(package));
    }
    [Theory][InlineData("hash")][InlineData("line")][InlineData("count")][InlineData("unit")][InlineData("original")][InlineData("field")]
    public async Task ForeignSourceMetadataNeverEditsInput(string fault){
        var package=Package();var p=Parameters(package);p=fault switch{"hash"=>p with{SourceSha256=new('0',64)},"line"=>p with{LineNumber=1},"count"=>p with{CellCount=100},"unit"=>p with{Unit="kJ/m3/K"},"original"=>p with{OriginalValue=34},_=>p with{Field="THCONR"}};
        var before=File.ReadAllBytes(package.MainFile);Assert.False(EclipseThermalRockEditor.Matches(EclipseThermalRockReader.Read(package),p));
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseThermalRockEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken));Assert.Equal(before,File.ReadAllBytes(package.MainFile));
    }
    [Theory][InlineData("BOX\n1 9 1 5 1 4 /\nENDBOX\n")][InlineData("HEATCR\n180*35 /\n")][InlineData("MULTIPLY\n 'HEATCR' 2 /\n/\n")][InlineData("COPY\n 'THCONR' 'HEATCR' /\n/\n")][InlineData("THCONSF\n180*1 /\n")][InlineData("THCROCK\n180*24 /\n")][InlineData("HEATCRT\n180*0.1 /\n")]
    public void BoxesOverridesAlternativePhysicsAndDuplicateArraysAreNotOffered(string extra){var package=Package();File.AppendAllText(package.MainFile,"\n"+extra);Assert.Null(EclipseThermalRockReader.Read(package));}
    [Theory][InlineData("180*35.0","179*35.0")][InlineData("180*35.0","90*35.0 90*40.0")][InlineData("180*35.0","180*")][InlineData("180*35.0","180*0.0")][InlineData("180*24.0","180*-1.0")]
    public void NonuniformDefaultedOrIncompleteGridPropertyArraysAreNotOffered(string before,string after){var p=Package();File.WriteAllText(p.MainFile,File.ReadAllText(p.MainFile).Replace(before,after,StringComparison.Ordinal));Assert.Null(EclipseThermalRockReader.Read(p));}
    [Theory][InlineData("string")][InlineData("zeroCapacity")][InlineData("negativeConductivity")][InlineData("nan")][InlineData("extra")][InlineData("missing")][InlineData("count")][InlineData("unit")][InlineData("schema")][InlineData("noop")]
    public void StrictProtocolRejectsInvalidPhysicsAndAmbiguousFields(string fault){var p=Json(Parameters(Package()));switch(fault){case "string":p["targetValue"]="45";break;case "zeroCapacity":p["targetValue"]=0;break;case "negativeConductivity":p["field"]="THCONR";p["unit"]="Btu/ft/day/degR";p["targetValue"]=-1;break;case "nan":p["targetValue"]="NaN";break;case "extra":p["temperature"]=450;break;case "missing":p.Remove("cellCount");break;case "count":p["cellCount"]=180.5;break;case "unit":p["unit"]="degF";break;case "schema":p["schemaVersion"]=EclipseThermalInjectionParameters.Schema;break;default:p["targetValue"]=35;break;}Assert.False(EclipseThermalRockParameters.TryParse(JsonSerializer.SerializeToElement(p),out _));}
    [Fact]public async Task ZeroConductivityAndFullPrecisionAreRetained(){var package=Package();var p=Parameters(package,"THCONR") with{TargetValue=0};await EclipseThermalRockEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);Assert.Equal(0,Assert.Single(EclipseThermalRockReader.Read(package)!.Targets.Where(t=>t.Record.Field=="THCONR")).Record.OriginalValue);}

    [Theory][InlineData("activeOverride")][InlineData("maskChanged")][InlineData("missingMask")]
    public void InactiveZeroOverridesMustNeverTouchAnActiveCell(string fault){
        var package=Package();var text=File.ReadAllText(package.MainFile);
        if(fault=="activeOverride")text=System.Text.RegularExpressions.Regex.Replace(text,"('THCONR'\\s+0\\.0\\s+)1 1 2 2 1 4","${1}2 2 2 2 1 4");
        if(fault=="maskChanged")text=text.Replace("0 1 1 1 1 1 1 1 0","1 1 1 1 1 1 1 1 0",StringComparison.Ordinal);
        if(fault=="missingMask")text=System.Text.RegularExpressions.Regex.Replace(text,"(?ms)^ACTNUM\\s+.*?^/","");
        File.WriteAllText(package.MainFile,text);Assert.Null(EclipseThermalRockReader.Read(package));
    }
    [Theory][InlineData(45.00000000000001)][InlineData(1e-20)]
    public async Task PropertyTokenNeverRoundsAwayTargetPrecision(double target){var package=Package();var p=Parameters(package) with{TargetValue=target};await EclipseThermalRockEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);Assert.Equal(target,Assert.Single(EclipseThermalRockReader.Read(package)!.Targets,t=>t.Record.Field=="HEATCR").Record.OriginalValue);}

    [Theory][InlineData("doubleQuote")][InlineData("padded")][InlineData("unknownOperator")]
    public void AlternateQuotedNamesOrOperatorsCannotHideActiveOverrides(string fault){
        var package=Package();var text=File.ReadAllText(package.MainFile);
        var assignment=fault=="doubleQuote"?"EQUALS\n \"THCONR\" 0 1 1 1 1 1 1 /\n/\n":fault=="padded"?"EQUALS\n 'THCONR  ' 0 1 1 1 1 1 1 /\n/\n":"COPYREG\n 'PERMX' 'THCONR' 1 /\n/\n";
        text=System.Text.RegularExpressions.Regex.Replace(text,"(?m)^EDIT",assignment+"EDIT");File.WriteAllText(package.MainFile,text);Assert.Null(EclipseThermalRockReader.Read(package));
    }
}
