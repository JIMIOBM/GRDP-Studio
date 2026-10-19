using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseMultiWetGasPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_MULTI_PVTG_WET_GAS.DATA"));
    private static string Request(string field,double multiplier){var c=EclipseMultiWetGasPvtReader.Read(Source,"CASE.DATA")!.Regions[1];return JsonSerializer.Serialize(new{schemaVersion=EclipseMultiWetGasPvtParameters.Schema,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(Source))),keywordLineNumber=c.KeywordLineNumber,unit=field=="VISCOSITY"?"cP":"rm3/sm3",multiplier,field,records=c.Records.Select(r=>new{rows=r.Rows.Select(v=>new{lineNumber=v.LineNumber,values=v.Values})}),oilTable=new{keywordLineNumber=c.OilTable.KeywordLineNumber,records=c.OilTable.Records.Select(r=>new{rows=r.Rows.Select(v=>new{lineNumber=v.LineNumber,values=v.Values})})}});}
    [Theory][InlineData("VISCOSITY",2,"VISCOSITY")][InlineData("FORMATION_VOLUME_FACTOR",1.02,"VOLUME")]
    public async Task SelectedFullBranchesMatchNativeInputAndWrongRegionCannotWrite(string field,double multiplier,string fixture){
        var root=Path.Combine(Path.GetTempPath(),"grdp-multi-pvtg",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var path=Path.Combine(root,"CASE.DATA");var source=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",source.Length,Convert.ToHexStringLower(SHA256.HashData(source)))]};var c=inspection.MultiWetGasPvtContext;Assert.NotNull(c);Assert.Equal(new[]{81,81},c.Regions.Select(r=>r.CellCount));Assert.All(c.Regions,r=>{Assert.Equal(13,r.Records.Count);Assert.Equal(29,r.Records.Sum(v=>v.Rows.Count));Assert.Equal(8,r.OilTable.Records.Count);Assert.Equal(11,r.OilTable.Records.Sum(v=>v.Rows.Count));});Assert.Null(inspection.MultiPvtContext);Assert.Null(inspection.WetGasPvtContext);
            var capture=Environment.GetEnvironmentVariable("GRDP_MULTI_PVTG_INSPECTION_CAPTURE");if(fixture=="VOLUME"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            using var doc=JsonDocument.Parse(Request(field,multiplier));Assert.True(EclipseMultiWetGasPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));var package=new EclipseDeckPackage(path,root,[path]);
            await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiWetGasPvtEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            var stale=p with{Edit=p.Edit with{SourceSha256=new string('0',64)}};await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiWetGasPvtEditor.ApplyAsync(package,stale,TestContext.Current.CancellationToken));Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await EclipseMultiWetGasPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","MULTI-PVTG-"+fixture+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public void RejectConflictingEquilibrationMapsDefaultsCapacityMissingBranchesAndAdvancedRegionRewrites(){
        Assert.NotNull(EclipseMultiWetGasPvtReader.Read(Source,"CASE.DATA"));
        var eql=Source.IndexOf("EQLNUM",StringComparison.Ordinal);var wrongMap=Source[..eql]+Source[eql..].Replace("81*2 81*1","162*1",StringComparison.Ordinal);Assert.Null(EclipseMultiWetGasPvtReader.Read(wrongMap,"CASE.DATA"));
        foreach(var pair in new[]{("1    2   20   20    1   15 /","1    2   20   12    1   15 /"),("2 100 10 1 1 /","1 100 10 1 1 /"),("2500.0   100","2500.0   1*"),("0.000354  0.003900    0.02842","0.000354  0.003900    1*"),("RVVD\r\n","PDVD\r\n"),("REGIONS\r\n","REGIONS\r\nCOPYREG\r\n"),("1      1      10 /","1      1      21 /")}){var bad=Source.Replace(pair.Item1,pair.Item2,StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseMultiWetGasPvtReader.Read(bad,"CASE.DATA"));}

    }
    [Fact] public void RejectDuplicateTypedRequestsAndBindUnselectedOilTable(){
        var json=Request("FORMATION_VOLUME_FACTOR",1.02);
        foreach(var bad in new[]{json.Replace("\"unit\":\"rm3/sm3\"","\"field\":\"VISCOSITY\"",StringComparison.Ordinal),json.Replace("\"multiplier\":1.02","\"multiplier\":\"1.02\"",StringComparison.Ordinal),json.Replace("\"FORMATION_VOLUME_FACTOR\"","\"RS\"",StringComparison.Ordinal),json.Replace("\"multiplier\":1.02","\"multiplier\":101",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}){Assert.NotEqual(json,bad);using var doc=JsonDocument.Parse(bad);Assert.False(EclipseMultiWetGasPvtParameters.TryParse(doc.RootElement,out _));}
    }
}
