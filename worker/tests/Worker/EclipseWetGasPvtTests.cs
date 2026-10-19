using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseWetGasPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_PVTG_WET_GAS.DATA"));
    [Fact] public void CompleteSaturatedAndUndersaturatedBranchesRejectUnsafeSourceForms()
    {
        var context=EclipseWetGasPvtReader.Read(Source,"CASE.DATA");Assert.NotNull(context);Assert.Equal(13,context.Records.Count);Assert.Equal(29,context.Records.Sum(r=>r.Rows.Count));Assert.Equal(3,context.Records[^1].Rows.Count);
        foreach(var bad in new[]{Source.Replace("VAPOIL","--VAPOIL",StringComparison.Ordinal),Source.Replace("PROPS","PROPS\nCOPY\n A PVTNUM /\n/",StringComparison.Ordinal),Source.Replace("PVTG    --","PVTG\n/\n--",StringComparison.Ordinal),Source.Replace("0.000132","1*",StringComparison.Ordinal),Source.Replace("METRIC","METRIC\nPVTNUM\n162*2 /",StringComparison.Ordinal),Source.Replace("1    1   20   20    1   15", "1    1   20   20    1   2",StringComparison.Ordinal),Source.Replace("1    1   20   20", "1    1   20   12",StringComparison.Ordinal)}){Assert.NotEqual(Source,bad);Assert.Null(EclipseWetGasPvtReader.Read(bad,"CASE.DATA"));}
        var missing=context.Records.ToArray();missing[^1]=new([missing[^1].Rows[0]]);Assert.False(EclipseWetGasPvtReader.ValidRecords(missing,context.KeywordLineNumber));
        var wrong=context.Records.ToArray();wrong[1]=new([wrong[1].Rows[0] with{Values=["30",".000124",".02046",".01420"]},wrong[1].Rows[1]]);Assert.False(EclipseWetGasPvtReader.ValidRecords(wrong,context.KeywordLineNumber));
        wrong=context.Records.ToArray();wrong[0]=new([wrong[0].Rows[0],wrong[0].Rows[1] with{Values=[".000132",".04231",".01389"]}]);Assert.False(EclipseWetGasPvtReader.ValidRecords(wrong,context.KeywordLineNumber));
        // Official PVTG has both rising and falling Bg / viscosity versus Rv.
        Assert.True(EclipseWetGasPvtReader.ValidRecords(context.Records,context.KeywordLineNumber));
    }
    [Fact] public void TabdimsPressureAndRvCapacitiesAreIndependentOfSaturationNodes(){
        Assert.NotNull(EclipseWetGasPvtReader.Read(Source.Replace("1    1   20   20    1   15","1    1   2   20    1   15",StringComparison.Ordinal),"CASE.DATA"));
        Assert.Null(EclipseWetGasPvtReader.Read(Source.Replace("1    1   20   20    1   15","1    1   20   20    1   2",StringComparison.Ordinal),"CASE.DATA"));
        Assert.NotNull(EclipseWetGasPvtReader.Read(Source.Replace("1    1   20   20    1   15","1    1   20   20",StringComparison.Ordinal),"CASE.DATA"));
    }
    [Theory] [InlineData("VISCOSITY",2,"cP","PVTG-VISCOSITY.DATA")] [InlineData("FORMATION_VOLUME_FACTOR",1.02,"rm3/sm3","PVTG-VOLUME.DATA")]
    public async Task CompleteBranchEditMatchesIndependentNativeInputAndPreservesAllOtherBytes(string field,double multiplier,string unit,string oracle)
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-pvtg",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            const string name="ECLIPSE_PVTG_WET_GAS.DATA";var path=Path.Combine(root,name);var bytes=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,bytes,TestContext.Current.CancellationToken);var sha=Convert.ToHexStringLower(SHA256.HashData(bytes));
            var inspection=EclipseDataInspector.InspectPackage([path],name) with{PackageFiles=[new EclipsePackageFile(name,bytes.Length,sha)]};Assert.Equal("eclipse-data-inspection/4",inspection.SchemaVersion);Assert.NotNull(inspection.WetGasPvtContext);
            var capture=Environment.GetEnvironmentVariable("GRDP_PVTG_INSPECTION_CAPTURE");if(!string.IsNullOrEmpty(capture)&&field=="VISCOSITY")await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            var c=inspection.WetGasPvtContext;var json=JsonSerializer.Serialize(new{schemaVersion=EclipseWetGasPvtParameters.Schema,baselineRunId=1,sourceFile=name,sourceSha256=sha,keywordLineNumber=c.KeywordLineNumber,unit,multiplier,field,records=c.Records},new JsonSerializerOptions(JsonSerializerDefaults.Web));
            using var doc=JsonDocument.Parse(json);Assert.True(EclipseWetGasPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));
            await EclipseWetGasPvtEditor.ApplyAsync(new EclipseDeckPackage(path,root,[path]),p,TestContext.Current.CancellationToken);
            Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures",oracle),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await File.WriteAllBytesAsync(path,bytes,TestContext.Current.CancellationToken);
            var failure=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseWetGasPvtEditor.ApplyAsync(new EclipseDeckPackage(path,root,[path]),p with{SourceSha256=new string('0',64)},TestContext.Current.CancellationToken));Assert.Equal("ECLIPSE_PVTG_SOURCE",failure.Code);Assert.Equal(bytes,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            var request=JsonNode.Parse(json)!.AsObject();foreach(var bad in new[]{("field","PRESSURE"),("unit","Pa.s"),("multiplier","2"),("records","missing")}){var value=request.DeepClone().AsObject();value[bad.Item1]=bad.Item2;using var invalid=JsonDocument.Parse(value.ToJsonString());Assert.False(EclipseWetGasPvtParameters.TryParse(invalid.RootElement,out _));}
            request["multiplier"]=101;using var over=JsonDocument.Parse(request.ToJsonString());Assert.False(EclipseWetGasPvtParameters.TryParse(over.RootElement,out _));
            foreach(var system in new[]{"FIELD","LAB"})Assert.Equal(field=="VISCOSITY",p.MatchesInspection(inspection with{UnitSystem=system}));
            Assert.True(p.MatchesInspection(inspection with{UnitSystem="PVT-M"}));
        }finally{Directory.Delete(root,true);}
    }
}
