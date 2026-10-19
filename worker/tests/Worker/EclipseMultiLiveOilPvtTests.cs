using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseMultiLiveOilPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_MULTI_PVTO_LIVE_OIL.DATA"));
    private static string Request(string field,double multiplier){var c=EclipseMultiLiveOilPvtReader.Read(Source,"CASE.DATA")!.Regions[1];return JsonSerializer.Serialize(new{schemaVersion=EclipseMultiLiveOilPvtParameters.Schema,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(Source))),keywordLineNumber=c.KeywordLineNumber,unit=field=="VISCOSITY"?"cP":"rb/stb",multiplier,field,records=c.Records.Select(r=>new{rows=r.Rows.Select(v=>new{lineNumber=v.LineNumber,values=v.Values})}),gasTable=new{keywordLineNumber=c.GasTable.KeywordLineNumber,rows=c.GasTable.Rows.Select(v=>new{lineNumber=v.LineNumber,values=v.Values})}});}
    [Theory][InlineData("VISCOSITY",2,"VISCOSITY")][InlineData("FORMATION_VOLUME_FACTOR",1.02,"VOLUME")]
    public async Task SelectedFullBranchesMatchNativeInputAndWrongRegionCannotWrite(string field,double multiplier,string fixture){
        var root=Path.Combine(Path.GetTempPath(),"grdp-multi-pvto",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var path=Path.Combine(root,"CASE.DATA");var source=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",source.Length,Convert.ToHexStringLower(SHA256.HashData(source)))]};var c=inspection.MultiLiveOilPvtContext;Assert.NotNull(c);Assert.Equal(new[]{150,150},c.Regions.Select(r=>r.CellCount));Assert.All(c.Regions,r=>{Assert.Equal(9,r.Records.Count);Assert.Equal(12,r.Records.Sum(v=>v.Rows.Count));});Assert.Null(inspection.MultiPvtContext);Assert.Null(inspection.LiveOilPvtContext);
            var capture=Environment.GetEnvironmentVariable("GRDP_MULTI_PVTO_INSPECTION_CAPTURE");if(fixture=="VOLUME"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            using var doc=JsonDocument.Parse(Request(field,multiplier));Assert.True(EclipseMultiLiveOilPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));var package=new EclipseDeckPackage(path,root,[path]);
            await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiLiveOilPvtEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            var stale=p with{Edit=p.Edit with{SourceSha256=new string('0',64)}};await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiLiveOilPvtEditor.ApplyAsync(package,stale,TestContext.Current.CancellationToken));Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await EclipseMultiLiveOilPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","MULTI-PVTO-"+fixture+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public void RejectConflictingEquilibrationMapsDefaultsCapacityMissingBranchesAndAdvancedRegionRewrites(){
        Assert.NotNull(EclipseMultiLiveOilPvtReader.Read(Source,"CASE.DATA"));
        var eql=Source.IndexOf("EQLNUM",StringComparison.Ordinal);var wrongMap=Source[..eql]+Source[eql..].Replace("5*1 5*2","10*1",StringComparison.Ordinal);Assert.Null(EclipseMultiLiveOilPvtReader.Read(wrongMap,"CASE.DATA"));
        foreach(var pair in new[]{("1    2   16   12    1   12 /","1    2   16   12    1   8 /"),("2  100   10    1    1 /","1  100   10    1    1 /"),("8400   4800   8500    0     8200    0      1      0       0","8400   4800   8500    0     8200    0      1      0       1"),("8500  1.270","8500  1*"),("9014.7 1.726  0.605   /","9014.7 1.726  1*   /"),("RSVD\r\n","PBVD\r\n"),("REGIONS\r\n","REGIONS\r\nCOPYREG\r\n")}){var bad=Source.Replace(pair.Item1,pair.Item2,StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseMultiLiveOilPvtReader.Read(bad,"CASE.DATA"));}
    }
    [Fact] public void RejectDuplicateTypedRequestsAndBindUnselectedGasTable(){
        var json=Request("FORMATION_VOLUME_FACTOR",1.02);
        foreach(var bad in new[]{json.Replace("\"unit\":\"rb/stb\"","\"field\":\"VISCOSITY\"",StringComparison.Ordinal),json.Replace("\"multiplier\":1.02","\"multiplier\":\"1.02\"",StringComparison.Ordinal),json.Replace("\"FORMATION_VOLUME_FACTOR\"","\"RS\"",StringComparison.Ordinal),json.Replace("\"multiplier\":1.02","\"multiplier\":9",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}){using var doc=JsonDocument.Parse(bad);Assert.False(EclipseMultiLiveOilPvtParameters.TryParse(doc.RootElement,out _));}
    }
}
