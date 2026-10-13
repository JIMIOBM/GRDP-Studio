using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseNewCompletionTests
{
    private const string FileName="ECLIPSE_NEW_COMPLETION_WATER.DATA";
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures",FileName));
    private static EclipseNewCompletionParameters Parameters=>new(1,FileName,Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(Source))),"CASE.EGRID",new string('a',64),
        15,15,1,EclipseNewWellContextReader.Read(Source,FileName)!.InsertionLine,"P",2,1,1,.2,1000,0,"m");
    [Fact] public void TypedParametersRejectUnknownFieldsInvalidNumbersAndUnits()
    {
        var p=Parameters;
        string json=JsonSerializer.Serialize(new {schemaVersion=EclipseNewCompletionParameters.Schema,baselineRunId=p.BaselineRunId,sourceFile=p.SourceFile,sourceSha256=p.SourceSha256,
            gridFile=p.GridFile,gridArtifactSha256=p.GridArtifactSha256,nx=p.Nx,ny=p.Ny,nz=p.Nz,insertionLine=p.InsertionLine,well=p.Well,i=p.I,j=p.J,k=p.K,diameter=p.Diameter,kh=p.Kh,skin=p.Skin,lengthUnit=p.LengthUnit});
        using var parsed=JsonDocument.Parse(json);Assert.True(EclipseNewCompletionParameters.TryParse(parsed.RootElement,out var actual));Assert.Equal(p,actual);
        foreach(var bad in new[]{json.Replace("\"i\":2","\"i\":\"2\"",StringComparison.Ordinal),json.Replace("\"i\":2","\"i\":16",StringComparison.Ordinal),
            json.Replace("\"skin\":0","\"skin\":-1",StringComparison.Ordinal),json.Replace("\"diameter\":0.2","\"diameter\":3",StringComparison.Ordinal),
            json.Replace("\"lengthUnit\":\"m\"","\"lengthUnit\":\"BARSA\"",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}){
            using var document=JsonDocument.Parse(bad);Assert.False(EclipseNewCompletionParameters.TryParse(document.RootElement,out _));
        }
    }
    [Fact] public void BoundExistingOpenProducerRejectsUnknownWellOccupiedCellAndConnectionCapacity()
    {
        string root=Path.Combine(Path.GetTempPath(),"grdp-new-completion",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            string path=Path.Combine(root,FileName);File.WriteAllText(path,Source,new UTF8Encoding(false));
            var inspection=EclipseDataInspector.InspectPackage([path],FileName);var p=Parameters;Assert.True(p.MatchesInspection(inspection));
            var capture=Environment.GetEnvironmentVariable("GRDP_NEW_COMPLETION_INSPECTION_CAPTURE");
            if(!string.IsNullOrEmpty(capture)){
                var snapshot=inspection with {PackageFiles=[new(FileName,Encoding.UTF8.GetByteCount(Source),p.SourceSha256)]};
                File.WriteAllText(capture,JsonSerializer.Serialize(snapshot,new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase,WriteIndented=true}),new UTF8Encoding(false));
            }
            Assert.False((p with {Well="P2"}).MatchesInspection(inspection));Assert.False((p with {Well="I"}).MatchesInspection(inspection));
            Assert.False((p with {I=1}).MatchesInspection(inspection));Assert.False((p with {I=15,J=15}).MatchesInspection(inspection));
            Assert.False((p with {InsertionLine=p.InsertionLine+1}).MatchesInspection(inspection));Assert.False((p with {LengthUnit="ft"}).MatchesInspection(inspection));
            Assert.False(p.MatchesInspection(inspection with {NewWellContext=inspection.NewWellContext! with {MaxConnections=1}}));
            File.WriteAllText(path,Source.Replace("'P' 'OPEN' 'WRAT'","'P' 'SHUT' 'WRAT'",StringComparison.Ordinal),new UTF8Encoding(false));
            Assert.False(p.MatchesInspection(EclipseDataInspector.InspectPackage([path],FileName)));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public async Task AddedConnectionPreservesEveryOldByteAndRejectsStaleSourceAndActualInactiveCell()
    {
        string root=Path.Combine(Path.GetTempPath(),"grdp-new-completion-editor",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            string path=Path.Combine(root,FileName),grid=Path.Combine(root,"CASE.EGRID");
            File.Copy(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_NEW_WELL_BASELINE.EGRID"),grid);
            byte[] original=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,original,TestContext.Current.CancellationToken);
            var p=Parameters;var package=new EclipseDeckPackage(path,root,[path]);
            await EclipseNewCompletionEditor.ApplyAsync(package,p,grid,TestContext.Current.CancellationToken);
            string applied=await File.ReadAllTextAsync(path,TestContext.Current.CancellationToken);
            const string marker="-- GRDP controlled extra connection; existing well and controls retained.";
            int start=applied.IndexOf(marker,StringComparison.Ordinal),end=applied.IndexOf("TSTEP",start,StringComparison.Ordinal);
            Assert.True(start>=0&&end>start);Assert.Equal(Source,applied.Remove(start,end-start));
            Assert.Contains(" 'P' 2 1 1 1 'OPEN' 0 0 0.20000000000000001 1000 0 /",applied,StringComparison.Ordinal);
            Assert.DoesNotContain("WELSPECS",applied[start..end],StringComparison.Ordinal);Assert.DoesNotContain("WCONPROD",applied[start..end],StringComparison.Ordinal);
            await File.WriteAllBytesAsync(path,original,TestContext.Current.CancellationToken);
            var error=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseNewCompletionEditor.ApplyAsync(package,p with {SourceSha256=new string('0',64)},grid,TestContext.Current.CancellationToken));
            Assert.Equal("ECLIPSE_NEW_COMPLETION_SOURCE",error.Code);Assert.Equal(original,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            var mask=EclipseBinaryFieldIndexParser.TryParse(grid)!.Sections.Single(f=>f.Keyword=="ACTNUM");
            byte[] bytes=await File.ReadAllBytesAsync(grid,TestContext.Current.CancellationToken);Array.Clear(bytes,(int)mask.Segments[0].Offset+4,4);
            await File.WriteAllBytesAsync(grid,bytes,TestContext.Current.CancellationToken);
            error=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseNewCompletionEditor.ApplyAsync(package,p,grid,TestContext.Current.CancellationToken));
            Assert.Equal("ECLIPSE_NEW_COMPLETION_INACTIVE_CELL",error.Code);Assert.Equal(original,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
}
