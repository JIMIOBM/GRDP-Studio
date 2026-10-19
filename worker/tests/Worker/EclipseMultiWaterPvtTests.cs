using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseMultiWaterPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_MULTI_PVT_WATER.DATA"));
    private static string Request(string field,double target){var c=EclipseMultiWaterPvtReader.Read(Source,"CASE.DATA")!.Regions[1];EclipsePvtwReader.Number(c.RecordValues[EclipsePvtwParameters.Index(field)],out var original);return JsonSerializer.Serialize(new{schemaVersion=EclipseMultiWaterPvtParameters.Schema,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(Source))),lineNumber=c.LineNumber,field,unit=EclipsePvtwParameters.UnitFor("FIELD",field),originalValue=original,targetValue=target,recordValues=c.RecordValues});}
    [Theory][InlineData("REFERENCE_PRESSURE",4000,"PRESSURE")][InlineData("FORMATION_VOLUME_FACTOR",1.0506,"VOLUME")][InlineData("COMPRESSIBILITY",6e-5,"COMPRESSIBILITY")][InlineData("VISCOSITY",.8,"VISCOSITY")][InlineData("VISCOSIBILITY",1e-6,"VISCOSIBILITY")]
    public async Task EachSelectedWaterFieldMatchesNativeBytesAndBindsItsActualRegion(string field,double target,string fixture){
        var root=Path.Combine(Path.GetTempPath(),"grdp-multi-water-pvt",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var path=Path.Combine(root,"CASE.DATA");var bytes=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,bytes,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",bytes.Length,Convert.ToHexStringLower(SHA256.HashData(bytes)))]};Assert.NotNull(inspection.MultiWaterPvtContext);Assert.NotNull(inspection.MultiPvtContext);Assert.Null(inspection.PvtwContext);Assert.Equal(new[]{6,7},inspection.MultiWaterPvtContext.Regions.Select(r=>r.CellCount));
            var capture=Environment.GetEnvironmentVariable("GRDP_MULTI_PVTW_INSPECTION_CAPTURE");if(field=="VISCOSIBILITY"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            using var doc=JsonDocument.Parse(Request(field,target));Assert.True(EclipseMultiWaterPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));var package=new EclipseDeckPackage(path,root,[path]);
            await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiWaterPvtEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiWaterPvtEditor.ApplyAsync(package,p with{Edit=p.Edit with{SourceSha256=new string('0',64)}},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await EclipseMultiWaterPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","MULTI-PVT-WATER-"+fixture+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public void IncompleteDefaultsAndUnsupportedInitializersDoNotExposeWaterRegions(){
        Assert.NotNull(EclipseMultiWaterPvtReader.Read(Source,"CASE.DATA"));
        var omitted=EclipseMultiWaterPvtReader.Read(Source.Replace("0.4 0 /","0.4 /",StringComparison.Ordinal),"CASE.DATA");Assert.NotNull(omitted);Assert.Equal("eclipse-multi-water-pvt-context/2",omitted.SchemaVersion);Assert.All(omitted.Regions,r=>Assert.Equal(4,r.RecordValues.Count));
        var star=EclipseMultiWaterPvtReader.Read(Source.Replace("0.4 0 /","0.4 1* /",StringComparison.Ordinal),"CASE.DATA");Assert.NotNull(star);Assert.Equal("eclipse-multi-water-pvt-context/3",star.SchemaVersion);Assert.Equal("1*",star.Regions[1].RecordValues[4]);
        foreach(var pair in new[]{("0.4 0 /","0.4 2* /"),("6*1 7*2","6*1 7*3"),("13*4500","12*4500"),("SOLUTION\r\n","SOLUTION\r\nEQUIL\r\n")}){var bad=Source.Replace(pair.Item1,pair.Item2,StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseMultiWaterPvtReader.Read(bad,"CASE.DATA"));}
    }
    [Fact] public void TypedRegionUnitsAndUnchangedFullRecordAreMandatory(){
        var json=Request("VISCOSITY",.8);
        foreach(var bad in new[]{json.Replace("\"regionNumber\":2","\"regionNumber\":\"2\"",StringComparison.Ordinal),json.Replace("\"unit\":\"cP\"","\"field\":\"VISCOSITY\"",StringComparison.Ordinal),json.Replace("\"targetValue\":0.8","\"targetValue\":\"0.8\"",StringComparison.Ordinal),json.Replace("\"targetValue\":0.8","\"targetValue\":1001",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}){Assert.NotEqual(json,bad);using var doc=JsonDocument.Parse(bad);Assert.False(EclipseMultiWaterPvtParameters.TryParse(doc.RootElement,out _));}
    }
}
