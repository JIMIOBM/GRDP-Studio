using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Globalization;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipsePvtwTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_PVTW_WATER.DATA"));
    private static string Request(string sha)=>JsonSerializer.Serialize(new {schemaVersion=EclipsePvtwParameters.Schema,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=sha,lineNumber=77,field="VISCOSITY",unit="cP",originalValue=.5,targetValue=1,recordValues=new[]{".0","1.0","3.03E-06",".5","0.0"}});
    [Fact] public void SourceReaderRejectsDefaultsMultipleRegionsOverlaysAndThermal()
    {
        Assert.NotNull(EclipsePvtwReader.Read(Source,"CASE.DATA"));
        foreach(var bad in new[]{Source.Replace(".5  0.0 /","1*  0.0 /",StringComparison.Ordinal),Source.Replace("PVTW","PVTW\n 0 1 0 .5 0 /",StringComparison.Ordinal),Source.Replace("METRIC","METRIC\nTHERMAL",StringComparison.Ordinal),Source.Replace("METRIC","METRIC\nPVTNUM",StringComparison.Ordinal),Source.Replace("METRIC","METRIC\nPVTNUM 225*2 /",StringComparison.Ordinal),Source.Replace("    1    1   20","    1    2   20",StringComparison.Ordinal)})Assert.Null(EclipsePvtwReader.Read(bad,"CASE.DATA"));
    }
    [Fact] public void StrictContractRejectsTypesUnknownFieldWrongUnitOrStaleFullRecord()
    {
        var json=Request(new string('a',64));using var good=JsonDocument.Parse(json);Assert.True(EclipsePvtwParameters.TryParse(good.RootElement,out _));
        foreach(var bad in new[]{json.Replace("\"targetValue\":1","\"targetValue\":\"1\"",StringComparison.Ordinal),json.Replace("\"targetValue\":1","\"targetValue\":0",StringComparison.Ordinal),json.Replace("\"unit\":\"cP\"","\"unit\":\"Pa.s\"",StringComparison.Ordinal),json.Replace("\".5\"","\"1*\"",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}){using var doc=JsonDocument.Parse(bad);Assert.False(EclipsePvtwParameters.TryParse(doc.RootElement,out _));}
    }
    [Fact] public async Task ActualInspectionAndExactTokenEditWithStaleShaRejectedBeforeWrite()
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-pvtw",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            var path=Path.Combine(root,"CASE.DATA");var source=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with { PackageFiles=[new EclipsePackageFile("CASE.DATA",source.Length,Convert.ToHexStringLower(SHA256.HashData(source)))] };Assert.NotNull(inspection.PvtwContext);Assert.Equal(77,inspection.PvtwContext.LineNumber);
            var capture=Environment.GetEnvironmentVariable("GRDP_PVTW_INSPECTION_CAPTURE");if(!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            using var doc=JsonDocument.Parse(Request(Convert.ToHexStringLower(SHA256.HashData(source))));Assert.True(EclipsePvtwParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));
            var package=new EclipseDeckPackage(path,root,[path]);await EclipsePvtwEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            Assert.Equal(Encoding.UTF8.GetBytes(Source.Replace("3.03E-06  .5  0.0 /","3.03E-06  1  0.0 /",StringComparison.Ordinal)),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var failure=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipsePvtwEditor.ApplyAsync(package,p with{SourceSha256=new string('0',64)},TestContext.Current.CancellationToken));Assert.Equal("ECLIPSE_PVTW_SOURCE",failure.Code);Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        } finally {Directory.Delete(root,true);}
    }

    [Theory]
    [InlineData("REFERENCE_PRESSURE",0,4000,"barsa")]
    [InlineData("FORMATION_VOLUME_FACTOR",1,1.02,"rm3/sm3")]
    [InlineData("COMPRESSIBILITY",2,.00000606,"1/bar")]
    [InlineData("VISCOSIBILITY",4,.000001,"1/bar")]
    public async Task AllWaterFieldsEditOnlySelectedTokenAndRespectModelUnits(string field,int index,double target,string unit)
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-pvtw-field",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            var path=Path.Combine(root,"CASE.DATA");var original=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,original,TestContext.Current.CancellationToken);
            var request=JsonNode.Parse(Request(Convert.ToHexStringLower(SHA256.HashData(original))))!;
            request["schemaVersion"]=EclipsePvtwParameters.Schema2;request["field"]=field;request["unit"]=unit;request["targetValue"]=target;
            EclipsePvtwReader.Number(request["recordValues"]![index]!.GetValue<string>(),out var value);request["originalValue"]=value;
            using var doc=JsonDocument.Parse(request.ToJsonString());Assert.True(EclipsePvtwParameters.TryParse(doc.RootElement,out var p));Assert.Equal(index,p!.ValueIndex);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA");Assert.True(p.MatchesInspection(inspection));Assert.False((p with {Unit=unit=="barsa"?"psia":"invalid"}).MatchesInspection(inspection));
            await EclipsePvtwEditor.ApplyAsync(new EclipseDeckPackage(path,root,[path]),p,TestContext.Current.CancellationToken);
            const string old="  .0  1.0  3.03E-06  .5  0.0 /";
            string[] values=[".0","1.0","3.03E-06",".5","0.0"];values[index]=target.ToString("G17",CultureInfo.InvariantCulture);
            var expected="  "+string.Join("  ",values)+" /";
            Assert.Equal(Encoding.UTF8.GetBytes(Source.Replace(old,expected,StringComparison.Ordinal)),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            request["schemaVersion"]=EclipsePvtwParameters.Schema;using var legacy=JsonDocument.Parse(request.ToJsonString());Assert.False(EclipsePvtwParameters.TryParse(legacy.RootElement,out _));
        }finally{Directory.Delete(root,true);}
    }
    [Theory]
    [InlineData("METRIC","barsa","rm3/sm3","1/bar")]
    [InlineData("FIELD","psia","rb/stb","1/psi")]
    [InlineData("LAB","atma","rcc/scc","1/atm")]
    [InlineData("PVT-M","atma","rm3/sm3","1/atm")]
    public void ReferencePressureVolumeAndInversePressureUnitsAreDistinct(string system,string pressure,string volume,string inverse)
    {
        Assert.Equal(pressure,EclipsePvtwParameters.UnitFor(system,"REFERENCE_PRESSURE"));Assert.Equal(volume,EclipsePvtwParameters.UnitFor(system,"FORMATION_VOLUME_FACTOR"));Assert.Equal(inverse,EclipsePvtwParameters.UnitFor(system,"COMPRESSIBILITY"));Assert.Equal(inverse,EclipsePvtwParameters.UnitFor(system,"VISCOSIBILITY"));Assert.Equal("cP",EclipsePvtwParameters.UnitFor(system,"VISCOSITY"));
        Assert.False(EclipsePvtwParameters.Scalar("REFERENCE_PRESSURE",pressure,-1));Assert.False(EclipsePvtwParameters.Scalar("FORMATION_VOLUME_FACTOR",volume,0));Assert.False(EclipsePvtwParameters.Scalar("COMPRESSIBILITY",inverse,-1));
    }
}
