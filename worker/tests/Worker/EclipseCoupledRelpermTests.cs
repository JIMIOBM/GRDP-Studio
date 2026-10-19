using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseCoupledRelpermTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_SWOF_SGOF_OIL.DATA"));
    private static string Request(string source,string field){
        var context=EclipseCoupledRelpermReader.Read(source,"CASE.DATA")!;
        return JsonSerializer.Serialize(new {schemaVersion=EclipseCoupledRelpermParameters.Schema,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source))),unit="1",multiplier=.5,field,tables=context.Tables.Select(t=>new{keyword=t.Keyword,keywordLineNumber=t.KeywordLineNumber,rows=t.Rows.Select(r=>new{lineNumber=r.LineNumber,values=r.Values})})});
    }
    [Theory][InlineData("WATER_KR","WATER")][InlineData("GAS_KR","GAS")][InlineData("OIL_KR","OIL")]
    public async Task LinkedTablesMatchIndependentCommercialInputsAndRejectStaleSources(string field,string fixture){
        var root=Path.Combine(Path.GetTempPath(),"grdp-coupled-relperm",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var path=Path.Combine(root,"CASE.DATA");var source=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",source.Length,Convert.ToHexStringLower(SHA256.HashData(source)))]};Assert.Equal("eclipse-data-inspection/4",inspection.SchemaVersion);Assert.NotNull(inspection.CoupledRelpermContext);Assert.Equal(new[]{30,10},inspection.CoupledRelpermContext.Tables.Select(t=>t.Rows.Count));
            var capture=Environment.GetEnvironmentVariable("GRDP_COUPLED_RELPERM_INSPECTION_CAPTURE");if(field=="OIL_KR"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            using var document=JsonDocument.Parse(Request(Source,field));Assert.True(EclipseCoupledRelpermParameters.TryParse(document.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));
            var package=new EclipseDeckPackage(path,root,[path]);await EclipseCoupledRelpermEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","COUPLED-"+fixture+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);var exception=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseCoupledRelpermEditor.ApplyAsync(package,p with{SourceSha256=new string('0',64)},TestContext.Current.CancellationToken));Assert.Equal("ECLIPSE_COUPLED_RELPERM_SOURCE",exception.Code);Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public void RejectDefaultMixedFamiliesCapacityInvalidEndpointsAndUnlinkedOil(){
        Assert.NotNull(EclipseCoupledRelpermReader.Read(Source,"CASE.DATA"));
        foreach(var pair in new[]{(".00      .0    1.",".00      .0    .9"),("9000*1","9000*2"),("1    1   40","1    1   20"),("0.151090     0.0","0.151090     1*"),("PROPS    =","SWFN\nPROPS    =")}){var bad=Source.Replace(pair.Item1,pair.Item2,StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseCoupledRelpermReader.Read(bad,"CASE.DATA"));}
        var json=Request(Source,"OIL_KR");foreach(var bad in new[]{json.Replace("\"multiplier\":0.5","\"multiplier\":2",StringComparison.Ordinal),json.Replace("\"multiplier\":0.5","\"multiplier\":\"0.5\"",StringComparison.Ordinal),json.Replace("\"OIL_KR\"","\"KROW_ONLY\"",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}){using var doc=JsonDocument.Parse(bad);Assert.False(EclipseCoupledRelpermParameters.TryParse(doc.RootElement,out _));}
    }
}
