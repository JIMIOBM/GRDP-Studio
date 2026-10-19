using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseSgfnTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_SGFN_GAS_INJECTION.DATA"));
    private static string Request(string source,string keyword){
        var context=EclipseRelpermReader.Read(source,"CASE.DATA")!;var table=context.Tables.Single(t=>t.Keyword==keyword);
        return JsonSerializer.Serialize(new {schemaVersion="eclipse-relperm-parameters/2",baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source))),keyword,keywordLineNumber=table.KeywordLineNumber,unit="1",multiplier=.5,rows=table.Rows.Select(r=>new{lineNumber=r.LineNumber,values=r.Values})});
    }
    [Fact] public void GasRowOrderingCapillaryPressureTypedTargetsAndVersionRemainStrict(){
        var json=Request(Source,"SGFN");using var good=JsonDocument.Parse(json);Assert.True(EclipseRelpermParameters.TryParse(good.RootElement,out _));
        foreach(var bad in new[]{json.Replace("/2","/1",StringComparison.Ordinal),json.Replace("\"multiplier\":0.5","\"multiplier\":\"0.5\"",StringComparison.Ordinal),json.Replace("\"multiplier\":0.5","\"multiplier\":2",StringComparison.Ordinal),json.Replace("\"0.75\"","\"1.1\"",StringComparison.Ordinal),json.Replace("\"0.09\"","\"-1\"",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}){using var document=JsonDocument.Parse(bad);Assert.False(EclipseRelpermParameters.TryParse(document.RootElement,out _));}
        foreach(var bad in new[]{Source.Replace("0.05   0     0.09","0.05   0     -1",StringComparison.Ordinal),Source.Replace("0.10   0.022 0.20","0.04   0.022 0.20",StringComparison.Ordinal),Source.Replace("13*1","13*2",StringComparison.Ordinal)}){Assert.NotEqual(Source,bad);Assert.Null(EclipseRelpermReader.Read(bad,"CASE.DATA"));}
    }
    [Theory][InlineData("SGFN")]
    public async Task ExactSourceEditsMatchIndependentCommercialInputsAndRejectStaleShaBeforeWrite(string keyword){
        var root=Path.Combine(Path.GetTempPath(),"grdp-relperm",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var path=Path.Combine(root,"CASE.DATA");var source=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",source.Length,Convert.ToHexStringLower(SHA256.HashData(source)))]};Assert.NotNull(inspection.RelpermContext);
            var capture=Environment.GetEnvironmentVariable("GRDP_SGFN_INSPECTION_CAPTURE");if(keyword=="SGFN"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            using var document=JsonDocument.Parse(Request(Source,keyword));Assert.True(EclipseRelpermParameters.TryParse(document.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));
            var package=new EclipseDeckPackage(path,root,[path]);await EclipseRelpermEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","RELPERM-"+keyword+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);var exception=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseRelpermEditor.ApplyAsync(package,p with{SourceSha256=new string('0',64)},TestContext.Current.CancellationToken));Assert.Equal("ECLIPSE_RELPERM_SOURCE",exception.Code);Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
}
