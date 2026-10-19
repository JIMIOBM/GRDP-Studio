using System.Globalization;
using System.Security.Cryptography;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseMultiWaterPvtStarTests
{
    [Theory]
    [InlineData(false,"pressure","REFERENCE_PRESSURE",4000)]
    [InlineData(false,"volume","FORMATION_VOLUME_FACTOR",1.0506)]
    [InlineData(false,"compressibility","COMPRESSIBILITY",6e-5)]
    [InlineData(false,"viscosity","VISCOSITY",.8)]
    [InlineData(false,"viscosibility","VISCOSIBILITY",1e-6)]
    [InlineData(true,"pressure","REFERENCE_PRESSURE",4000)]
    [InlineData(true,"volume","FORMATION_VOLUME_FACTOR",1.02)]
    [InlineData(true,"compressibility","COMPRESSIBILITY",6.06e-6)]
    [InlineData(true,"viscosity","VISCOSITY",1)]
    [InlineData(true,"viscosibility","VISCOSIBILITY",1e-6)]
    public async Task OfficialCvDefaultRetainsRawStarAndOnlySelectedTokenMatchesIndependentNative(bool pure,string label,string field,double target){
        var root=Path.Combine(Path.GetTempPath(),"grdp-water-star",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            var bytes=await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures",pure?"ECLIPSE_MULTI_PVTW_PURE_STAR.DATA":"ECLIPSE_MULTI_PVTW_STAR_DEFAULT.DATA"),TestContext.Current.CancellationToken);var file=Path.Combine(root,"CASE.DATA");await File.WriteAllBytesAsync(file,bytes,TestContext.Current.CancellationToken);var sha=Convert.ToHexStringLower(SHA256.HashData(bytes));
            var i=EclipseDataInspector.InspectPackage([file],"CASE.DATA") with{PackageFiles=[new EclipsePackageFile("CASE.DATA",bytes.Length,sha)]};Assert.NotNull(i.MultiWaterPvtContext);Assert.Equal("eclipse-multi-water-pvt-context/3",i.MultiWaterPvtContext.SchemaVersion);Assert.All(i.MultiWaterPvtContext.Regions,r=>{Assert.Equal(5,r.RecordValues.Count);Assert.Equal("1*",r.RecordValues[4]);});
            var capture=Environment.GetEnvironmentVariable(pure?"GRDP_MULTI_PURE_WATER_STAR_CAPTURE":"GRDP_MULTI_WATER_STAR_CAPTURE");if(label=="viscosity"&&!string.IsNullOrEmpty(capture))await File.WriteAllTextAsync(capture,JsonSerializer.Serialize(i,new JsonSerializerOptions(JsonSerializerDefaults.Web)),TestContext.Current.CancellationToken);
            var r=i.MultiWaterPvtContext.Regions[1];var index=Array.IndexOf(new[]{"REFERENCE_PRESSURE","FORMATION_VOLUME_FACTOR","COMPRESSIBILITY","VISCOSITY","VISCOSIBILITY"},field);var original=index==4?0:double.Parse(r.RecordValues[index].Replace('D','E').Replace('d','e'),CultureInfo.InvariantCulture);using var doc=JsonDocument.Parse(JsonSerializer.Serialize(new{schemaVersion=EclipseMultiWaterPvtParameters.Schema3,regionNumber=2,baselineRunId=1,sourceFile="CASE.DATA",sourceSha256=sha,lineNumber=r.LineNumber,field,unit=EclipsePvtwParameters.UnitFor(i.UnitSystem!,field),originalValue=original,targetValue=target,recordValues=r.RecordValues}));
            Assert.True(EclipseMultiWaterPvtParameters.TryParse(doc.RootElement,out var p));Assert.True(p!.MatchesInspection(i));Assert.Equal("1*",p.RecordValues[4]);Assert.Equal("0",p.Edit.RecordValues[4]);var package=new EclipseDeckPackage(file,root,[file]);await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseMultiWaterPvtEditor.ApplyAsync(package,p with{RegionNumber=1},TestContext.Current.CancellationToken));Assert.Equal(bytes,await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));await EclipseMultiWaterPvtEditor.ApplyAsync(package,p,TestContext.Current.CancellationToken);
            var expected=Path.Combine(AppContext.BaseDirectory,"Fixtures",(pure?"MULTI-PVTW-PURE-STAR-":"MULTI-PVTW-STAR-")+label.ToUpperInvariant()+".DATA");Assert.Equal(await File.ReadAllBytesAsync(expected,TestContext.Current.CancellationToken),await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Theory][InlineData(false)][InlineData(true)]
    public void OtherDefaultPositionsAndDefaultRepeatCountsNeverBecomeEditable(bool pure){
        var source=File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures",pure?"ECLIPSE_MULTI_PVTW_PURE_STAR.DATA":"ECLIPSE_MULTI_PVTW_STAR_DEFAULT.DATA"));Assert.NotNull(EclipseMultiWaterPvtReader.Read(source,"CASE.DATA"));
        Assert.Null(EclipseMultiWaterPvtReader.Read(source.Replace("1*","2*",StringComparison.Ordinal),"CASE.DATA"));
        var lines=System.Text.RegularExpressions.Regex.Split(source,"\r\n|\r|\n");var header=Array.FindIndex(lines,l=>l.Trim()=="PVTW");var row=Array.FindIndex(lines,header+1,l=>l.Trim().Length>0);var tokens=System.Text.RegularExpressions.Regex.Matches(lines[row],@"[^\s/]+");var first=tokens[0];var bad=lines[row][..first.Index]+"1*"+lines[row][(first.Index+first.Length)..];Assert.Null(EclipseMultiWaterPvtReader.Read(source.Replace(lines[row],bad,StringComparison.Ordinal),"CASE.DATA"));
    }
}
