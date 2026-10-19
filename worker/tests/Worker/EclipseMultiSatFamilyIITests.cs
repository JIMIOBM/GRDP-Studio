using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseMultiSatFamilyIITests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_MULTI_SAT_FAMILY_II.DATA"));
    private static string Request(string source,string field){
        var context=EclipseMultiSatRelpermReader.ReadFamilyII(source,"CASE.DATA")!.Regions.Single(r=>r.Number==2);
        return JsonSerializer.Serialize(new {schemaVersion=EclipseMultiSatFamilyIIParameters.Schema,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source))),unit="1",multiplier=.5,field,tables=context.Tables.Select(t=>new{keyword=t.Keyword,keywordLineNumber=t.KeywordLineNumber,rows=t.Rows.Select(r=>new{lineNumber=r.LineNumber,values=r.Values})})});
    }
    [Theory][InlineData("WATER_KR","WATER")][InlineData("GAS_KR","GAS")][InlineData("OIL_KR","OIL")]
    public async Task LinkedTablesMatchIndependentCommercialInputsAndRejectStaleSources(string field,string fixture){
        var root=Path.Combine(Path.GetTempPath(),"grdp-coupled-relperm",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var path=Path.Combine(root,"CASE.DATA");var source=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",source.Length,Convert.ToHexStringLower(SHA256.HashData(source)))]};Assert.Equal("eclipse-data-inspection/4",inspection.SchemaVersion);Assert.NotNull(inspection.MultiSatRelpermContext);Assert.Equal(new[]{13,13,15},inspection.MultiSatRelpermContext.Regions[1].Tables.Select(t=>t.Rows.Count));
            var capture=Environment.GetEnvironmentVariable("GRDP_MULTI_FAMILY_II_INSPECTION_CAPTURE");if(field=="OIL_KR"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            using var document=JsonDocument.Parse(Request(Source,field));Assert.True(EclipseMultiSatFamilyIIParameters.TryParse(document.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));
            var package=new EclipseDeckPackage(path,root,[path]);Assert.False((p with{RegionNumber=1}).MatchesInspection(inspection));await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiSatFamilyIIEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));await EclipseMultiSatFamilyIIEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","MULTI-FAMILY-II-"+fixture+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);var exception=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiSatFamilyIIEditor.ApplyAsync(package,p with{Edit=p.Edit with{SourceSha256=new string('0',64)}},TestContext.Current.CancellationToken));Assert.Equal("ECLIPSE_MULTI_FAMILY_II_RELPERM_SOURCE",exception.Code);Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public void RejectDefaultMixedFamiliesCapacityInvalidEndpointsAndUnlinkedOil(){
        Assert.NotNull(EclipseMultiSatRelpermReader.ReadFamilyII(Source,"CASE.DATA"));
        foreach(var pair in new[]{("0.80   0.9    0.9","0.80   0.9    0.8"),("6*1 7*2","6*1 7*3"),("2 1 30 10","2 1 12 10"),("0.2    0     6","0.2    1*    6"),("PROPS\r\n","SWOF\r\nPROPS\r\n")}){var bad=Source.Replace(pair.Item1,pair.Item2,StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseMultiSatRelpermReader.ReadFamilyII(bad,"CASE.DATA"));}
        var json=Request(Source,"OIL_KR");foreach(var bad in new[]{json.Replace("\"unit\":\"1\"","\"field\":\"WATER_KR\"",StringComparison.Ordinal),json.Replace("\"multiplier\":0.5","\"multiplier\":2",StringComparison.Ordinal),json.Replace("\"multiplier\":0.5","\"multiplier\":\"0.5\"",StringComparison.Ordinal),json.Replace("\"OIL_KR\"","\"KROW_ONLY\"",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}){using var doc=JsonDocument.Parse(bad);Assert.False(EclipseMultiSatFamilyIIParameters.TryParse(doc.RootElement,out _));}
    }
}
