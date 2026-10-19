using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseMultiPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_MULTI_PVT_OIL_GAS.DATA"));
    private static string Request(string source,string keyword,string field,double multiplier){
        var context=EclipseMultiPvtReader.Read(source,"CASE.DATA")!.Regions.Single(r=>r.Number==2);
        return JsonSerializer.Serialize(new{schemaVersion=EclipseMultiPvtParameters.Schema,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source))),unit=field=="VISCOSITY"?"cP":keyword=="PVDO"?"rb/stb":"rb/Mscf",multiplier,field,keyword,tables=context.Tables.Select(t=>new{keyword=t.Keyword,keywordLineNumber=t.KeywordLineNumber,rows=t.Rows.Select(r=>new{lineNumber=r.LineNumber,values=r.Values})})});
    }
    [Theory][InlineData("PVDO","VISCOSITY",2,"OIL-MU")][InlineData("PVDO","FORMATION_VOLUME_FACTOR",1.02,"OIL-BO")][InlineData("PVDG","VISCOSITY",2,"GAS-MU")][InlineData("PVDG","FORMATION_VOLUME_FACTOR",1.02,"GAS-BG")]
    public async Task SelectedRegionMatchesIndependentNativeInputAndWrongRegionCannotWrite(string keyword,string field,double multiplier,string fixture){
        var root=Path.Combine(Path.GetTempPath(),"grdp-multi-pvt",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var path=Path.Combine(root,"CASE.DATA");var bytes=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,bytes,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",bytes.Length,Convert.ToHexStringLower(SHA256.HashData(bytes)))]};Assert.Equal("eclipse-data-inspection/4",inspection.SchemaVersion);Assert.NotNull(inspection.MultiPvtContext);Assert.Equal(new[]{6,7},inspection.MultiPvtContext.Regions.Select(r=>r.CellCount));Assert.Null(inspection.OilPvtContext);Assert.Null(inspection.GasPvtContext);
            var capture=Environment.GetEnvironmentVariable("GRDP_MULTI_PVT_INSPECTION_CAPTURE");if(fixture=="OIL-BO"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            using var doc=JsonDocument.Parse(Request(Source,keyword,field,multiplier));Assert.True(EclipseMultiPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));var package=new EclipseDeckPackage(path,root,[path]);
            await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiPvtEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await EclipseMultiPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","MULTI-PVT-"+fixture+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public void RejectUnsupportedInitializationIncompleteMapTableCountsDefaultValuesAndWrongCapacity(){
        Assert.NotNull(EclipseMultiPvtReader.Read(Source,"CASE.DATA"));
        foreach(var pair in new[]{("6*1 7*2","6*1 6*2"),("6*1 7*2","6*1 7*3"),("1 2 30 10","1 2 30 5"),("13*4500","12*4500"),("13*4500","13*"),("13*0.0","13*0.9"),("1 2 30 10","2 2 30 10"),("SOLUTION\r\n","SOLUTION\r\nEQUIL\r\n"),("1.243  0.5","1.243  1*"),("32      1.05    0.7 /","32      1.05    0.7 /\r\n/"),("SUMMARY\r\n","BOX\r\nSUMMARY\r\n")}){
            var bad=Source.Replace(pair.Item1,pair.Item2,StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseMultiPvtReader.Read(bad,"CASE.DATA"));
        }
    }
    [Fact] public void RejectTypedBoundsDuplicatePropertiesAndBindBothFluidTables(){
        var json=Request(Source,"PVDO","VISCOSITY",2);
        foreach(var bad in new[]{json.Replace("\"unit\":\"cP\"","\"keyword\":\"PVDG\"",StringComparison.Ordinal),json.Replace("\"multiplier\":2","\"multiplier\":\"2\"",StringComparison.Ordinal),json.Replace("\"VISCOSITY\"","\"PRESSURE\"",StringComparison.Ordinal),json.Replace("\"PVDO\"","\"PVTO\"",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}){using var doc=JsonDocument.Parse(bad);Assert.False(EclipseMultiPvtParameters.TryParse(doc.RootElement,out _));}
    }
    [Fact] public void AdvancedRegionAssignmentDoesNotExposeAnUnresolvedPvtMap(){
        foreach(var keyword in new[]{"EQUALREG","COPYREG","ADDREG","OPERATER"}){
            var bad=Source.Replace("PVTNUM",keyword+"\r\nPVTNUM",StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseMultiPvtReader.Read(bad,"CASE.DATA"));
        }
    }

}
