using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseLiveOilPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_PVTO_LIVE_OIL.DATA"));
    [Fact] public void CompleteSaturatedAndUndersaturatedBranchesRejectUnsafeSourceForms()
    {
        var context=EclipseLiveOilPvtReader.Read(Source,"CASE.DATA");Assert.NotNull(context);Assert.Equal(9,context.Records.Count);Assert.Equal(12,context.Records.Sum(r=>r.Rows.Count));Assert.Equal(2,context.Records[^1].Rows.Count);
        foreach(var bad in new[]{Source.Replace("DISGAS","VAPOIL",StringComparison.Ordinal),Source.Replace("PROPS","PROPS\nCOPY\n A PVTNUM /\n/",StringComparison.Ordinal),Source.Replace("PVTO","PVTO\n/",StringComparison.Ordinal),Source.Replace("1.726","1.9",StringComparison.Ordinal),Source.Replace("0.605","1*",StringComparison.Ordinal),Source.Replace("FIELD","FIELD\nPVTNUM\n300*2 /",StringComparison.Ordinal)}){Assert.NotEqual(Source,bad);Assert.Null(EclipseLiveOilPvtReader.Read(bad,"CASE.DATA"));}
        var missing=context.Records.ToArray();missing[^1]=new([missing[^1].Rows[0]]);Assert.False(EclipseLiveOilPvtReader.ValidRecords(missing,context.KeywordLineNumber));
        var wrong=context.Records.ToArray();wrong[1]=new([wrong[1].Rows[0] with{Values=[".001","264.7","1.15",".975"]}]);Assert.False(EclipseLiveOilPvtReader.ValidRecords(wrong,context.KeywordLineNumber));
    }
    [Fact] public void TabdimsPressureAndRsCapacitiesAreIndependentOfSaturationNodes(){
        Assert.NotNull(EclipseLiveOilPvtReader.Read(Source.Replace("1    1   16   12    1   12","1    1   8   12    1   12",StringComparison.Ordinal),"CASE.DATA"));
        Assert.Null(EclipseLiveOilPvtReader.Read(Source.Replace("1    1   16   12    1   12","1    1   16   12    1   8",StringComparison.Ordinal),"CASE.DATA"));
        Assert.Null(EclipseLiveOilPvtReader.Read(Source.Replace("1    1   16   12    1   12","1    1   16   1    1   12",StringComparison.Ordinal),"CASE.DATA"));
        Assert.NotNull(EclipseLiveOilPvtReader.Read(Source.Replace("1    1   16   12    1   12","1    1   16   12",StringComparison.Ordinal),"CASE.DATA"));
    }
    [Theory] [InlineData("VISCOSITY",2,"cP","PVTO-VISCOSITY.DATA")] [InlineData("FORMATION_VOLUME_FACTOR",1.02,"rb/stb","PVTO-VOLUME.DATA")]
    public async Task CompleteBranchEditMatchesIndependentNativeInputAndPreservesAllOtherBytes(string field,double multiplier,string unit,string oracle)
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-pvto",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            const string name="ECLIPSE_PVTO_LIVE_OIL.DATA";var path=Path.Combine(root,name);var bytes=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,bytes,TestContext.Current.CancellationToken);var sha=Convert.ToHexStringLower(SHA256.HashData(bytes));
            var inspection=EclipseDataInspector.InspectPackage([path],name) with{PackageFiles=[new EclipsePackageFile(name,bytes.Length,sha)]};Assert.Equal("eclipse-data-inspection/4",inspection.SchemaVersion);Assert.NotNull(inspection.LiveOilPvtContext);
            var capture=Environment.GetEnvironmentVariable("GRDP_PVTO_INSPECTION_CAPTURE");if(!string.IsNullOrEmpty(capture)&&field=="VISCOSITY")await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            var c=inspection.LiveOilPvtContext;var json=JsonSerializer.Serialize(new{schemaVersion=EclipseLiveOilPvtParameters.Schema,baselineRunId=1,sourceFile=name,sourceSha256=sha,keywordLineNumber=c.KeywordLineNumber,unit,multiplier,field,records=c.Records},new JsonSerializerOptions(JsonSerializerDefaults.Web));
            using var doc=JsonDocument.Parse(json);Assert.True(EclipseLiveOilPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));
            await EclipseLiveOilPvtEditor.ApplyAsync(new EclipseDeckPackage(path,root,[path]),p,TestContext.Current.CancellationToken);
            Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures",oracle),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await File.WriteAllBytesAsync(path,bytes,TestContext.Current.CancellationToken);
            var failure=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseLiveOilPvtEditor.ApplyAsync(new EclipseDeckPackage(path,root,[path]),p with{SourceSha256=new string('0',64)},TestContext.Current.CancellationToken));Assert.Equal("ECLIPSE_PVTO_SOURCE",failure.Code);Assert.Equal(bytes,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            var request=JsonNode.Parse(json)!.AsObject();foreach(var bad in new[]{("field","PRESSURE"),("unit","Pa.s"),("multiplier","2"),("records","missing")}){var value=request.DeepClone().AsObject();value[bad.Item1]=bad.Item2;using var invalid=JsonDocument.Parse(value.ToJsonString());Assert.False(EclipseLiveOilPvtParameters.TryParse(invalid.RootElement,out _));}
            request["multiplier"]=field=="VISCOSITY"?10000:8;using var over=JsonDocument.Parse(request.ToJsonString());Assert.False(EclipseLiveOilPvtParameters.TryParse(over.RootElement,out _));
            foreach(var system in new[]{"METRIC","LAB","PVT-M"})Assert.Equal(field=="VISCOSITY",p.MatchesInspection(inspection with{UnitSystem=system}));
        }finally{Directory.Delete(root,true);}
    }
}
