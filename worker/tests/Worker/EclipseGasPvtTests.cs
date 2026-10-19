using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Reflection;
using Microsoft.Extensions.Options;
using Grdp.SoftwareIntegration.Worker.Storage;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseGasPvtTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_PVDG_GASWATER.DATA"));
    private static string Request(string sha)=>JsonSerializer.Serialize(new {schemaVersion=EclipseGasPvtParameters.Schema,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=sha,keywordLineNumber=82,unit="cP",multiplier=2,rows=new[]{new{lineNumber=83,values=new[]{".0","1.0","2.0"}},new{lineNumber=84,values=new[]{"8000.0",".92","2.0"}}}});
    [Fact] public void ZeroReferencePressureAndTwoSaturationTablesAreValidButTwoPvtRegionsOrVaporizedOilAreNot()
    {
        Assert.NotNull(EclipseGasPvtReader.Read(Source,"CASE.DATA"));
        foreach(var bad in new[]{Source.Replace(".92    2.0","1.1    2.0",StringComparison.Ordinal),Source.Replace(".92    2.0",".92    1*",StringComparison.Ordinal),Source.Replace("FIELD","FIELD\nVAPOIL",StringComparison.Ordinal),Source.Replace("    2    1   20   20","    2    2   20   20",StringComparison.Ordinal),Source.Replace("PVDG","PVDG\n 0 1 2 /",StringComparison.Ordinal),Source.Replace("PROPS     ","PROPS\nCOPY\n OTHER PVTNUM /\n/\n     ",StringComparison.Ordinal)})Assert.Null(EclipseGasPvtReader.Read(bad,"CASE.DATA"));
    }
    [Fact] public async Task ActualTwoRowSourceInspectionAndExactEditsKeepBgPressureAndAllPhysicalControls()
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-pvdg",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            var path=Path.Combine(root,"CASE.DATA");var source=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA") with {PackageFiles=[new EclipsePackageFile("CASE.DATA",source.Length,Convert.ToHexStringLower(SHA256.HashData(source)))]};Assert.Equal("eclipse-data-inspection/4",inspection.SchemaVersion);Assert.NotNull(inspection.GasPvtContext);Assert.Equal(82,inspection.GasPvtContext.KeywordLineNumber);Assert.Equal(2,inspection.GasPvtContext.Rows.Count);
            var capture=Environment.GetEnvironmentVariable("GRDP_PVDG_INSPECTION_CAPTURE");if(!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(inspection,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            using var doc=JsonDocument.Parse(Request(Convert.ToHexStringLower(SHA256.HashData(source))));Assert.True(EclipseGasPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(inspection));
            var package=new EclipseDeckPackage(path,root,[path]);await EclipseGasPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            var expected=Source.Replace("1.0     2.0","1.0     4",StringComparison.Ordinal).Replace(".92    2.0",".92    4",StringComparison.Ordinal);Assert.Equal(Encoding.UTF8.GetBytes(expected),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);var failure=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseGasPvtEditor.ApplyAsync(package,p with{SourceSha256=new string('0',64)},TestContext.Current.CancellationToken));Assert.Equal("ECLIPSE_PVDG_SOURCE",failure.Code);Assert.Equal(source,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public void ProductionSubmissionAcceptsNullAndBothPvtFamiliesButRejectsInvalidAndUnknownParameters()
    {
        var validate=typeof(EclipseRunService).GetMethod("Validate",BindingFlags.NonPublic|BindingFlags.Instance)!;
        var options=Options.Create(new WorkerOptions());
        using var coordinator=new EclipseExecutionCoordinator();
        using var service=new EclipseRunService(new StorageResolver(options),coordinator,new PtkRunRegistry(new WorkerIdentity(options)),new EclipseLauncher(options),new EclipseDeckPackageResolver(),options);
        var gas=Request(new string('a',64));var oil="""
            {"schemaVersion":"eclipse-pvdo-parameters/1","baselineRunId":1,"sourceFile":"CASE.DATA","sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","keywordLineNumber":1,"unit":"cP","multiplier":2,"rows":[{"lineNumber":2,"values":["2500","1.26","0.5"]},{"lineNumber":3,"values":["3000","1.256","0.5"]}]}
            """;
        foreach(var text in new[]{"null",gas,oil}){using var doc=JsonDocument.Parse(text);Assert.Null(validate.Invoke(service,[new RunExecuteRequest(1,"models/CASE.DATA",new string('a',64),null,"eclipse",doc.RootElement,60)]));}
        foreach(var text in new[]{"{}",gas.Replace("\"multiplier\":2","\"multiplier\":1",StringComparison.Ordinal),gas.Replace("\"unit\":\"cP\"","\"unit\":\"Pa.s\"",StringComparison.Ordinal),gas[..^1]+",\"extra\":1}"}){using var doc=JsonDocument.Parse(text);Assert.NotNull(validate.Invoke(service,[new RunExecuteRequest(1,"models/CASE.DATA",new string('a',64),null,"eclipse",doc.RootElement,60)]));}
    }
    [Fact] public async Task VolumeFactorChangesOnlyTwoBgTokensAndRetainsZeroPressureViscosityAndUnits()
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-pvdg-volume",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            var path=Path.Combine(root,"CASE.DATA");var source=Encoding.UTF8.GetBytes(Source);var sha=Convert.ToHexStringLower(SHA256.HashData(source));await File.WriteAllBytesAsync(path,source,TestContext.Current.CancellationToken);
            var inspection=EclipseDataInspector.InspectPackage([path],"CASE.DATA");var request=JsonNode.Parse(Request(sha))!.AsObject();request["schemaVersion"]=EclipseGasPvtParameters.FieldSchema;request["field"]="FORMATION_VOLUME_FACTOR";request["unit"]="rb/Mscf";request["multiplier"]=1.02;
            using var doc=JsonDocument.Parse(request.ToJsonString());Assert.True(EclipseGasPvtParameters.TryParse(doc.RootElement,out var p));Assert.Equal(1,p!.Column);Assert.True(p.MatchesInspection(inspection));
            foreach(var system in new[]{"METRIC","LAB","PVT-M"}){Assert.False(p.MatchesInspection(inspection with{UnitSystem=system}));Assert.True((p with{Unit=EclipseGasPvtParameters.VolumeUnit(system)}).MatchesInspection(inspection with{UnitSystem=system}));}
            await EclipseGasPvtEditor.ApplyAsync(new EclipseDeckPackage(path,root,[path]),p,TestContext.Current.CancellationToken);
            var expected=Source.Replace("1.0     2.0","1.02     2.0",StringComparison.Ordinal).Replace(".92    2.0","0.93840000000000001    2.0",StringComparison.Ordinal);
            Assert.Equal(Encoding.UTF8.GetBytes(expected),await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            foreach(var change in new[]{("field","PRESSURE"),("unit","cP"),("unit","rb/stb"),("schemaVersion",EclipseGasPvtParameters.Schema)}){var bad=request.DeepClone().AsObject();bad[change.Item1]=change.Item2;using var value=JsonDocument.Parse(bad.ToJsonString());Assert.False(EclipseGasPvtParameters.TryParse(value.RootElement,out _));}
            request["rows"]![0]!["values"]![1]="100000";using var over=JsonDocument.Parse(request.ToJsonString());Assert.False(EclipseGasPvtParameters.TryParse(over.RootElement,out _));request.Remove("field");using var missing=JsonDocument.Parse(request.ToJsonString());Assert.False(EclipseGasPvtParameters.TryParse(missing.RootElement,out _));
        }finally{Directory.Delete(root,true);}
    }

}
