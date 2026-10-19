using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipsePureWaterPvtRegionsTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_MULTI_PVTW_PURE_WATER.DATA"));
    [Theory][InlineData("REFERENCE_PRESSURE",4000,"PRESSURE")][InlineData("FORMATION_VOLUME_FACTOR",1.02,"VOLUME")][InlineData("COMPRESSIBILITY",6.06e-6,"COMPRESSIBILITY")][InlineData("VISCOSITY",1,"VISCOSITY")][InlineData("VISCOSIBILITY",1e-6,"VISCOSIBILITY")]
    public async Task PureWaterSelectedRegionMatchesExactNativeInputAndRetainsOtherRegion(string field,double target,string fixture){
        var directory=Path.Combine(Path.GetTempPath(),"grdp-pure-water-regions",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(directory);
        try{
            var file=Path.Combine(directory,"CASE.DATA");var bytes=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(file,bytes,TestContext.Current.CancellationToken);var sha=Convert.ToHexStringLower(SHA256.HashData(bytes));
            var i=EclipseDataInspector.InspectPackage([file],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",bytes.Length,sha)]};Assert.Null(i.MultiPvtContext);Assert.Null(i.PvtwContext);Assert.NotNull(i.MultiWaterPvtContext);Assert.Equal(new[]{105,120},i.MultiWaterPvtContext.Regions.Select(r=>r.CellCount));var r=i.MultiWaterPvtContext.Regions[1];
            var capture=Environment.GetEnvironmentVariable("GRDP_MULTI_PURE_PVTW_INSPECTION_CAPTURE");if(field=="VISCOSIBILITY"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(i,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            EclipsePvtwReader.Number(r.RecordValues[EclipsePvtwParameters.Index(field)],out var original);
            using var document=JsonDocument.Parse(JsonSerializer.Serialize(new{schemaVersion=EclipseMultiWaterPvtParameters.Schema,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=sha,lineNumber=r.LineNumber,field,unit=EclipsePvtwParameters.UnitFor("METRIC",field),originalValue=original,targetValue=target,recordValues=r.RecordValues}));
            Assert.True(EclipseMultiWaterPvtParameters.TryParse(document.RootElement,out var p));Assert.True(p!.MatchesInspection(i));var package=new EclipseDeckPackage(file,directory,[file]);
            await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiWaterPvtEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));
            await EclipseMultiWaterPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);Assert.Equal(await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","MULTI-PVTW-PURE-WATER-"+fixture+".DATA"),TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(directory,true);}
    }
    [Fact] public void DefaultsUnmappedRegionsIncompleteInitializationAndAlternativeTablesStayClosed(){
        Assert.NotNull(EclipsePureWaterPvtRegionsReader.Read(Source));
        var star=EclipseMultiWaterPvtReader.Read(Source.Replace(".5  0.0 /",".5  1* /",StringComparison.Ordinal),"CASE.DATA");Assert.NotNull(star);Assert.Equal("eclipse-multi-water-pvt-context/3",star.SchemaVersion);Assert.All(star.Regions,r=>Assert.Equal("1*",r.RecordValues[4]));
        foreach(var pair in new[]{("7*1 8*2","7*1 8*3"),("7*1 8*2","7*1 8*1"),("7*1 8*2","7*1 8*"),("225*4000.0","224*4000.0"),("225*4000.0","225*-1"),(".5  0.0 /",".5  2* /"),("PRESSURE","EQUIL"),("PVTNUM","EQUALREG"),("DENSITY","PVTWSALT")}){var bad=Source.Replace(pair.Item1,pair.Item2,StringComparison.Ordinal);Assert.NotEqual(Source,bad);Assert.Null(EclipseMultiWaterPvtReader.Read(bad,"CASE.DATA"));}
    }
}
