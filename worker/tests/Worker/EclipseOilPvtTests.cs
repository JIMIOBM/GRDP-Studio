using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseOilPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_PVDO_OIL.DATA"));
    private static string Request(string sha)=>JsonSerializer.Serialize(new {schemaVersion=EclipseOilPvtParameters.Schema,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=sha,keywordLineNumber=180,unit="cP",multiplier=2,rows=new[]{
        new {lineNumber=182,values=new[]{"2500","1.26","0.5"}},new {lineNumber=183,values=new[]{"3000","1.256","0.5"}},new {lineNumber=184,values=new[]{"3500","1.252","0.5"}},
        new {lineNumber=185,values=new[]{"4000","1.248","0.5"}},new {lineNumber=186,values=new[]{"4500","1.245","0.5"}},new {lineNumber=187,values=new[]{"5000","1.243","0.5"}}}});
    [Fact] public void SourceRejectsDefaultsMultipleRegionsDissolvedGasAndPropsOverlaysButAllowsGridConstruction()
    {
        Assert.NotNull(EclipseOilPvtReader.Read(Source,"CASE.DATA"));
        foreach(var bad in new[]{Source.Replace("1.26   0.5","1.26   1*",StringComparison.Ordinal),Source.Replace("1.256  0.5","1.3  0.5",StringComparison.Ordinal),Source.Replace("3000    1.256","2000    1.256",StringComparison.Ordinal),Source.Replace("FIELD","FIELD\nDISGAS",StringComparison.Ordinal),Source.Replace("FIELD","FIELD\nPVTNUM 13*2 /",StringComparison.Ordinal),Source.Replace("PROPS","PROPS\nCOPY\n OTHER PVTNUM /\n/",StringComparison.Ordinal),Source.Replace("PVDO","PVDO\n2500 1.26 .5 /",StringComparison.Ordinal)})Assert.Null(EclipseOilPvtReader.Read(bad,"CASE.DATA"));
    }
    [Fact] public void TypedContractRejectsUnknownFieldsStaleRowsAndNonphysicalColumn()
    {
        var json=Request(new string('a',64));using var good=JsonDocument.Parse(json);Assert.True(EclipseOilPvtParameters.TryParse(good.RootElement,out _));
        foreach(var bad in new[]{json.Replace("\"multiplier\":2","\"multiplier\":\"2\"",StringComparison.Ordinal),json.Replace("\"multiplier\":2","\"multiplier\":1",StringComparison.Ordinal),json.Replace("\"unit\":\"cP\"","\"unit\":\"Pa.s\"",StringComparison.Ordinal),json.Replace("\"1.256\"","\"1.3\"",StringComparison.Ordinal),json.Replace("\"0.5\"","\"1*\"",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}){using var doc=JsonDocument.Parse(bad);Assert.False(EclipseOilPvtParameters.TryParse(doc.RootElement,out _));}
    }
    [Fact] public async Task ActualPackageInspectionAndSixTokenEditPreserveEveryOtherSourceByte()
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-pvdo",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            var path=Path.Combine(root,"CASE.DATA");var source=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with {PackageFiles=[new EclipsePackageFile("CASE.DATA",source.Length,Convert.ToHexStringLower(SHA256.HashData(source)))]};
            Assert.Equal("eclipse-data-inspection/4",inspection.SchemaVersion);Assert.NotNull(inspection.OilPvtContext);Assert.Equal(180,inspection.OilPvtContext.KeywordLineNumber);Assert.Equal(6,inspection.OilPvtContext.Rows.Count);
            var capture=Environment.GetEnvironmentVariable("GRDP_PVDO_INSPECTION_CAPTURE");if(!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            using var doc=JsonDocument.Parse(Request(Convert.ToHexStringLower(SHA256.HashData(source))));Assert.True(EclipseOilPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));
            var package=new EclipseDeckPackage(path,root,[path]);await EclipseOilPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            var start=Source.IndexOf("PVDO",StringComparison.Ordinal);var end=Source.IndexOf("PVDG",start,StringComparison.Ordinal);var table=Source[start..end];var expected=Source[..start]+table.Replace("0.5","1",StringComparison.Ordinal)+Source[end..];
            Assert.Equal(Encoding.UTF8.GetBytes(expected),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var failure=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseOilPvtEditor.ApplyAsync(package,p with {SourceSha256=new string('0',64)},TestContext.Current.CancellationToken));Assert.Equal("ECLIPSE_PVDO_SOURCE",failure.Code);Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            var rows=p.Rows.ToArray();rows[0]=rows[0] with{LineNumber=183};Assert.False((p with{Rows=rows}).MatchesInspection(inspection));
        }finally{Directory.Delete(root,true);}
    }
}
