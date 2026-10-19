using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseMultiSatFamilyILiveOilPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_MULTI_SAT_FAMILY_I_PVTO_LIVE_OIL.DATA"));
    [Theory][InlineData("viscosity","VISCOSITY",2)][InlineData("volume","FORMATION_VOLUME_FACTOR",1.02)][InlineData("water-kr","WATER_KR",.5)][InlineData("gas-kr","GAS_KR",.5)][InlineData("oil-kr","OIL_KR",.5)]
    public async Task BothRegionAxesAreValidatedAndEverySelectedEditMatchesIndependentNativeBytes(string label,string field,double multiplier){
        var root=Path.Combine(Path.GetTempPath(),"grdp-sat-live-oil",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var file=Path.Combine(root,"CASE.DATA");var bytes=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(file,bytes,TestContext.Current.CancellationToken);var sha=Convert.ToHexStringLower(SHA256.HashData(bytes));
            var i=EclipseDataInspector.InspectPackage([file],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",bytes.Length,sha)]};Assert.NotNull(i.MultiLiveOilPvtContext);Assert.NotNull(i.MultiSatRelpermContext);Assert.Null(i.MultiPvtContext);Assert.Equal(new[]{150,150},i.MultiLiveOilPvtContext.Regions.Select(r=>r.CellCount));Assert.Equal(new[]{150,150},i.MultiSatRelpermContext.Regions.Select(r=>r.CellCount));Assert.Equal("eclipse-multi-sat-relperm-context/1",i.MultiSatRelpermContext.SchemaVersion);
            var capture=Environment.GetEnvironmentVariable("GRDP_MULTI_SAT_FAMILY_I_PVTO_CAPTURE");if(label=="volume"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(i,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            object payload;if(label is "viscosity" or "volume"){
                var r=i.MultiLiveOilPvtContext.Regions[1];payload=new{schemaVersion=EclipseMultiLiveOilPvtParameters.Schema,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=sha,keywordLineNumber=r.KeywordLineNumber,unit=field=="VISCOSITY"?"cP":"rb/stb",multiplier,field,records=r.Records.Select(v=>new{rows=v.Rows.Select(row=>new{lineNumber=row.LineNumber,values=row.Values})}),gasTable=new{keywordLineNumber=r.GasTable.KeywordLineNumber,rows=r.GasTable.Rows.Select(row=>new{lineNumber=row.LineNumber,values=row.Values})}};
            }else{var r=i.MultiSatRelpermContext.Regions[1];payload=new{schemaVersion=EclipseMultiSatRelpermParameters.Schema,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=sha,field,unit="1",multiplier,tables=r.Tables.Select(t=>new{keyword=t.Keyword,keywordLineNumber=t.KeywordLineNumber,rows=t.Rows.Select(row=>new{lineNumber=row.LineNumber,values=row.Values})})};}
            using var doc=JsonDocument.Parse(JsonSerializer.Serialize(payload));var package=new EclipseDeckPackage(file,root,[file]);
            if(label is "viscosity" or "volume"){
                Assert.True(EclipseMultiLiveOilPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(i));await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiLiveOilPvtEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));await EclipseMultiLiveOilPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            }else{
                Assert.True(EclipseMultiSatRelpermParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(i));await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiSatRelpermEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));await EclipseMultiSatRelpermEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            }
            Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","MULTI-SAT-FAMILY-I-PVTO-"+label.ToUpperInvariant()+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public void MissingEitherAxisOrConflictingEquilibrationMapsNeverExposePartialCombinedEditing(){
        Assert.NotNull(EclipseMultiSatLiveOilPvtReader.Read(Source,"CASE.DATA"));Assert.Null(EclipseMultiLiveOilPvtReader.Read(Source,"CASE.DATA"));Assert.Null(EclipseMultiSatRelpermReader.Read(Source,"CASE.DATA"));
        foreach(var pair in new[]{("50*1 50*2","50*1 50*3"),("SATNUM","ISATNUM"),("PVTO","PVDO"),("RSVD","PBVD"),("2  100   10    1    1 /","1  100   10    1    1 /")}){var bad=Source.Replace(pair.Item1,pair.Item2,StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseMultiSatLiveOilPvtReader.Read(bad,"CASE.DATA"));}
        var start=Source.IndexOf("EQLNUM",StringComparison.Ordinal);var wrong=Source[..start]+Source[start..].Replace("5*1 5*2","10*1",StringComparison.Ordinal);Assert.Null(EclipseMultiSatLiveOilPvtReader.Read(wrong,"CASE.DATA"));
    }
}
