using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseMultiSatPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_MULTI_SAT_PVT_OIL_GAS.DATA"));
    [Theory]
    [InlineData("PVDO","VISCOSITY",2,"OIL-MU")][InlineData("PVDO","FORMATION_VOLUME_FACTOR",1.02,"OIL-BO")]
    [InlineData("PVDG","VISCOSITY",2,"GAS-MU")][InlineData("PVDG","FORMATION_VOLUME_FACTOR",1.02,"GAS-BG")]
    [InlineData("SWFN","WATER_KR",.5,"WATER-KR")][InlineData("SGFN","GAS_KR",.5,"GAS-KR")][InlineData("SOF3","OIL_KR",.5,"OIL-KR")]
    public async Task IndependentSatAndPvtMapsBindTheirOwnTablesAndMatchNativeBytes(string keyword,string field,double multiplier,string fixture){
        var root=Path.Combine(Path.GetTempPath(),"grdp-multi-sat-pvt",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var path=Path.Combine(root,"CASE.DATA");var bytes=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,bytes,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",bytes.Length,Convert.ToHexStringLower(SHA256.HashData(bytes)))]};
            Assert.NotNull(inspection.MultiPvtContext);Assert.NotNull(inspection.MultiSatRelpermContext);
            Assert.Equal(new[]{6,7},inspection.MultiPvtContext.Regions.Select(r=>r.CellCount));Assert.Equal(new[]{5,8},inspection.MultiSatRelpermContext.Regions.Select(r=>r.CellCount));
            Assert.Null(EclipseMultiPvtReader.Read(Source,"CASE.DATA"));Assert.Null(EclipseMultiSatRelpermReader.ReadFamilyII(Source,"CASE.DATA"));
            var capture=Environment.GetEnvironmentVariable("GRDP_MULTI_SAT_PVT_INSPECTION_CAPTURE");if(fixture=="OIL-BO"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            bool fluid=keyword is "PVDO" or "PVDG";
            var request=new Dictionary<string,object>{["schemaVersion"]=fluid?EclipseMultiPvtParameters.Schema:EclipseMultiSatFamilyIIParameters.Schema,["regionNumber"]=2,["baselineRunId"]=1,["sourceFile"]="CASE.DATA",["sourceSha256"]=Convert.ToHexStringLower(SHA256.HashData(bytes)),["unit"]=fluid?(field=="VISCOSITY"?"cP":keyword=="PVDO"?"rb/stb":"rb/Mscf"):"1",["multiplier"]=multiplier,["field"]=field};
            request["tables"]=fluid?inspection.MultiPvtContext.Regions[1].Tables.Select(t=>new{keyword=t.Keyword,keywordLineNumber=t.KeywordLineNumber,rows=t.Rows.Select(r=>new{lineNumber=r.LineNumber,values=r.Values})}).ToArray():inspection.MultiSatRelpermContext.Regions[1].Tables.Select(t=>new{keyword=t.Keyword,keywordLineNumber=t.KeywordLineNumber,rows=t.Rows.Select(r=>new{lineNumber=r.LineNumber,values=r.Values})}).ToArray();
            if(fluid)request["keyword"]=keyword;
            using var doc=JsonDocument.Parse(JsonSerializer.Serialize(request));var package=new EclipseDeckPackage(path,root,[path]);
            if(fluid){Assert.True(EclipseMultiPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiPvtEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));await EclipseMultiPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);}
            else{Assert.True(EclipseMultiSatFamilyIIParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiSatFamilyIIEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));await EclipseMultiSatFamilyIIEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);}
            Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","MULTI-SAT-PVT-"+fixture+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public void BothContextsRequireCompleteIndependentMapsAndBothValidTableFamilies(){
        Assert.NotNull(EclipseMultiSatPvtReader.Read(Source,"CASE.DATA"));
        foreach(var pair in new[]{("5*1 8*2","5*1 7*2"),("5*1 8*2","5*1 8*3"),("6*1 7*2","6*1 6*2"),("6*1 7*2","6*1 7*3"),("2 2 30 10","2 2 12 10"),("0.80   0.9    0.9","0.80   0.9    0.8"),("1.243  0.5","1.243  1*"),("REGIONS\r\n","REGIONS\r\nCOPYREG\r\n"),("13*4500","12*4500")}){
            var bad=Source.Replace(pair.Item1,pair.Item2,StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseMultiSatPvtReader.Read(bad,"CASE.DATA"));
        }
    }
}
