using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseMultiSatWetGasPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_MULTI_SAT_PVTG_WET_GAS.DATA"));
    [Theory][InlineData("viscosity","VISCOSITY",2)][InlineData("volume","FORMATION_VOLUME_FACTOR",1.02)][InlineData("water-kr","WATER_KR",.5)][InlineData("gas-kr","GAS_KR",.5)][InlineData("oil-kr","OIL_KR",.5)]
    public async Task BothRegionAxesAreValidatedAndEverySelectedEditMatchesIndependentNativeBytes(string label,string field,double multiplier){
        var root=Path.Combine(Path.GetTempPath(),"grdp-sat-live-oil",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var file=Path.Combine(root,"CASE.DATA");var bytes=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(file,bytes,TestContext.Current.CancellationToken);var sha=Convert.ToHexStringLower(SHA256.HashData(bytes));
            var i=EclipseDataInspector.InspectPackage([file],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",bytes.Length,sha)]};Assert.NotNull(i.MultiWetGasPvtContext);Assert.NotNull(i.MultiSatRelpermContext);Assert.Null(i.MultiPvtContext);Assert.Equal(new[]{81,81},i.MultiWetGasPvtContext.Regions.Select(r=>r.CellCount));Assert.Equal(new[]{72,90},i.MultiSatRelpermContext.Regions.Select(r=>r.CellCount));Assert.Equal("eclipse-multi-sat-relperm-context/2",i.MultiSatRelpermContext.SchemaVersion);
            var capture=Environment.GetEnvironmentVariable("GRDP_MULTI_SAT_PVTG_CAPTURE");if(label=="volume"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(i,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            object payload;if(label is "viscosity" or "volume"){
                var r=i.MultiWetGasPvtContext.Regions[1];payload=new{schemaVersion=EclipseMultiWetGasPvtParameters.Schema,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=sha,keywordLineNumber=r.KeywordLineNumber,unit=field=="VISCOSITY"?"cP":"rm3/sm3",multiplier,field,records=r.Records.Select(v=>new{rows=v.Rows.Select(row=>new{lineNumber=row.LineNumber,values=row.Values})}),oilTable=new{keywordLineNumber=r.OilTable.KeywordLineNumber,records=r.OilTable.Records.Select(v=>new{rows=v.Rows.Select(row=>new{lineNumber=row.LineNumber,values=row.Values})})}};
            }else{var r=i.MultiSatRelpermContext.Regions[1];payload=new{schemaVersion=EclipseMultiSatFamilyIIParameters.Schema,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=sha,field,unit="1",multiplier,tables=r.Tables.Select(t=>new{keyword=t.Keyword,keywordLineNumber=t.KeywordLineNumber,rows=t.Rows.Select(row=>new{lineNumber=row.LineNumber,values=row.Values})})};}
            using var doc=JsonDocument.Parse(JsonSerializer.Serialize(payload));var package=new EclipseDeckPackage(file,root,[file]);
            if(label is "viscosity" or "volume"){
                Assert.True(EclipseMultiWetGasPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(i));await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiWetGasPvtEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));await EclipseMultiWetGasPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            }else{
                Assert.True(EclipseMultiSatFamilyIIParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(i));await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiSatFamilyIIEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));await EclipseMultiSatFamilyIIEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            }
            Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","MULTI-SAT-PVTG-"+label.ToUpperInvariant()+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public void MissingEitherAxisOrConflictingEquilibrationMapsNeverExposePartialCombinedEditing(){
        Assert.NotNull(EclipseMultiSatWetGasPvtReader.Read(Source,"CASE.DATA"));Assert.Null(EclipseMultiWetGasPvtReader.Read(Source,"CASE.DATA"));Assert.Null(EclipseMultiSatRelpermReader.ReadFamilyII(Source,"CASE.DATA"));
        foreach(var pair in new[]{("4*1 5*2","4*1 5*3"),("SATNUM","ISATNUM"),("PVTO","PVDO"),("RSVD","PBVD"),("RVVD","PDVD"),("VAPOIL","SOLVENT"),("2 100 10 1 1 /","1 100 10 1 1 /")}){var bad=Source.Replace(pair.Item1,pair.Item2,StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseMultiSatWetGasPvtReader.Read(bad,"CASE.DATA"));}
        var start=Source.IndexOf("EQLNUM",StringComparison.Ordinal);var wrong=Source[..start]+Source[start..].Replace("81*2 81*1","162*1",StringComparison.Ordinal);Assert.Null(EclipseMultiSatWetGasPvtReader.Read(wrong,"CASE.DATA"));
    }
}
