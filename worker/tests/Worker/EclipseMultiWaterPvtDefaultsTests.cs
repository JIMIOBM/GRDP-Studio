using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseMultiWaterPvtDefaultsTests
{
    [Theory]
    [InlineData(false,"REFERENCE_PRESSURE",4000,"PRESSURE")][InlineData(false,"FORMATION_VOLUME_FACTOR",1.0506,"VOLUME")][InlineData(false,"COMPRESSIBILITY",6e-5,"COMPRESSIBILITY")][InlineData(false,"VISCOSITY",.8,"VISCOSITY")][InlineData(false,"VISCOSIBILITY",1e-6,"VISCOSIBILITY")]
    [InlineData(true,"REFERENCE_PRESSURE",4000,"PRESSURE")][InlineData(true,"FORMATION_VOLUME_FACTOR",1.02,"VOLUME")][InlineData(true,"COMPRESSIBILITY",6.06e-6,"COMPRESSIBILITY")][InlineData(true,"VISCOSITY",1,"VISCOSITY")][InlineData(true,"VISCOSIBILITY",1e-6,"VISCOSIBILITY")]
    public async Task OriginalFourTokensBindTheOfficialDefaultAndOnlyCvEditingInsertsTheMissingToken(bool pure,string field,double target,string fixture){
        var root=Path.Combine(Path.GetTempPath(),"grdp-water-defaults",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var data=await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures",pure?"ECLIPSE_MULTI_PVTW_PURE_DEFAULT.DATA":"ECLIPSE_MULTI_PVT_OIL_GAS.DATA"),TestContext.Current.CancellationToken);var file=Path.Combine(root,"CASE.DATA");await File.WriteAllBytesAsync(file,data,TestContext.Current.CancellationToken);
            var sha=Convert.ToHexStringLower(SHA256.HashData(data));var i=EclipseDataInspector.InspectPackage([file],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",data.Length,sha)]};Assert.NotNull(i.MultiWaterPvtContext);Assert.Equal("eclipse-multi-water-pvt-context/2",i.MultiWaterPvtContext.SchemaVersion);var r=i.MultiWaterPvtContext.Regions[1];Assert.Equal(4,r.RecordValues.Count);
            var capture=Environment.GetEnvironmentVariable(pure?"GRDP_PURE_DEFAULT_PVTW_CAPTURE":"GRDP_THREE_DEFAULT_PVTW_CAPTURE");if(field=="VISCOSIBILITY"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(i,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            double original=0;if(field!="VISCOSIBILITY")EclipsePvtwReader.Number(r.RecordValues[EclipsePvtwParameters.Index(field)],out original);
            var json=JsonSerializer.Serialize(new{schemaVersion=EclipseMultiWaterPvtParameters.Schema2,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=sha,lineNumber=r.LineNumber,field,unit=EclipsePvtwParameters.UnitFor(pure?"METRIC":"FIELD",field),originalValue=original,targetValue=target,recordValues=r.RecordValues});using var doc=JsonDocument.Parse(json);
            Assert.True(EclipseMultiWaterPvtParameters.TryParse(doc.RootElement,out var p));Assert.Equal(4,p!.RecordValues.Count);Assert.Equal("0",p.Edit.RecordValues[4]);Assert.True(p.MatchesInspection(i));
            using var wrongSchema=JsonDocument.Parse(json.Replace(EclipseMultiWaterPvtParameters.Schema2,EclipseMultiWaterPvtParameters.Schema,StringComparison.Ordinal));Assert.False(EclipseMultiWaterPvtParameters.TryParse(wrongSchema.RootElement,out _));
            var package=new EclipseDeckPackage(file,root,[file]);await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiWaterPvtEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(data,await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));
            await EclipseMultiWaterPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","MULTI-PVTW-DEFAULT-"+(pure?"PURE":"THREE")+"-"+fixture+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));
            var edited=EclipseMultiWaterPvtReader.Read(Encoding.UTF8.GetString(await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken)),"CASE.DATA")!;Assert.Equal(4,edited.Regions[0].RecordValues.Count);Assert.Equal(field=="VISCOSIBILITY"?5:4,edited.Regions[1].RecordValues.Count);
        }finally{Directory.Delete(root,true);}
    }
}
