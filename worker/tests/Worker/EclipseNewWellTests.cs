using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseNewWellTests
{
    private static string Source=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_NEW_WELL_WATER.DATA"));
    private static EclipseNewWellParameters Parameters=>new(1,"ECLIPSE_NEW_WELL_WATER.DATA",Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(Source))),"CASE.EGRID",new string('a',64),
        15,15,1,EclipseNewWellContextReader.Read(Source,"ECLIPSE_NEW_WELL_WATER.DATA")!.InsertionLine,"P2","G",15,1,1,4000,"m",.2,1000,0,100,"SM3/DAY",3000,"BARSA");
    [Fact] public void ContextRequiresActualNewWellOutputsAndExplicitCapacityBeforeInitialTime()
    {
        var c=EclipseNewWellContextReader.Read(Source,"ECLIPSE_NEW_WELL_WATER.DATA");Assert.NotNull(c);Assert.Equal(3,c.MaxWells);Assert.Equal(10,c.MaxConnections);Assert.Equal(1,c.MaxGroups);
        foreach(var bad in new[]{Source.Replace("WSTAT","UNKNOWN",StringComparison.Ordinal),Source.Replace("3   10    1   10","1* 10 1 10",StringComparison.Ordinal),
            Source.Replace("WTRACER","GCONPROD",StringComparison.Ordinal),Source.Replace("TSTEP","DATES",StringComparison.Ordinal),Source+"\nWCONPROD\n'P' 'SHUT' /\n/\n"})
            Assert.Null(EclipseNewWellContextReader.Read(bad,"ECLIPSE_NEW_WELL_WATER.DATA"));
    }
    [Fact] public void ParameterTypesUnitsNamesAndPhysicalValuesAreStrict()
    {
        var p=Parameters;var json=JsonSerializer.Serialize(new {schemaVersion=EclipseNewWellParameters.Schema,baselineRunId=p.BaselineRunId,sourceFile=p.SourceFile,sourceSha256=p.SourceSha256,gridFile=p.GridFile,gridArtifactSha256=p.GridArtifactSha256,
            nx=p.Nx,ny=p.Ny,nz=p.Nz,insertionLine=p.InsertionLine,well=p.Well,group=p.Group,i=p.I,j=p.J,k=p.K,referenceDepth=p.ReferenceDepth,lengthUnit=p.LengthUnit,diameter=p.Diameter,kh=p.Kh,skin=p.Skin,waterRate=p.WaterRate,rateUnit=p.RateUnit,bhpLimit=p.BhpLimit,pressureUnit=p.PressureUnit});
        using var valid=JsonDocument.Parse(json);Assert.True(EclipseNewWellParameters.TryParse(valid.RootElement,out var parsed));Assert.Equal(p,parsed);
        foreach(var bad in new[]{json.Replace("\"waterRate\":100","\"waterRate\":\"100\"",StringComparison.Ordinal),json.Replace("\"well\":\"P2\"","\"well\":\"TOOLONG99\"",StringComparison.Ordinal),
            json.Replace("\"group\":\"G\"","\"group\":\"FIELD\"",StringComparison.Ordinal),json.Replace("\"skin\":0","\"skin\":-1",StringComparison.Ordinal),
            json.Replace("\"i\":15","\"i\":16",StringComparison.Ordinal),json.Replace("BARSA","PSIA",StringComparison.Ordinal),json[..^1]+",\"extra\":1}"}) {
            using var v=JsonDocument.Parse(bad);Assert.False(EclipseNewWellParameters.TryParse(v.RootElement,out _));
        }
    }
    [Fact] public void InspectionRejectsExistingNamesOccupiedCellsWrongGroupTimingAndCapacity()
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-new-well-tests",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            var path=Path.Combine(root,"ECLIPSE_NEW_WELL_WATER.DATA");File.WriteAllText(path,Source,new UTF8Encoding(false));var inspection=EclipseDataInspector.InspectPackage([path],Path.GetFileName(path));var p=Parameters;
            Assert.True(p.MatchesInspection(inspection));
            var capture=Environment.GetEnvironmentVariable("GRDP_NEW_WELL_INSPECTION_CAPTURE");
            if(!string.IsNullOrEmpty(capture)) {
                var snapshot=inspection with {PackageFiles=[new(p.SourceFile,Encoding.UTF8.GetByteCount(Source),p.SourceSha256)]};
                File.WriteAllText(capture,JsonSerializer.Serialize(snapshot,new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase,WriteIndented=true}),new UTF8Encoding(false));
            }
            Assert.False((p with {Well="P"}).MatchesInspection(inspection));Assert.False((p with {I=1,J=1}).MatchesInspection(inspection));
            Assert.False((p with {Group="OTHER"}).MatchesInspection(inspection));Assert.False((p with {InsertionLine=p.InsertionLine+1}).MatchesInspection(inspection));
            Assert.False(p.MatchesInspection(inspection with {NewWellContext=inspection.NewWellContext! with {MaxWells=2}}));
            Assert.False(p.MatchesInspection(inspection with {NewWellContext=inspection.NewWellContext! with {MaxWellsPerGroup=2}}));
            Assert.False(p.MatchesInspection(inspection with {UnitSystem="FIELD"}));
        }finally{Directory.Delete(root,true);}
    }
    [Fact] public async Task WritesOnlyNewWellBlockAndRejectsStaleSourceOrInactiveActualCell()
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-new-well-editor",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            var path=Path.Combine(root,"ECLIPSE_NEW_WELL_WATER.DATA");var grid=Path.Combine(root,"CASE.EGRID");
            File.Copy(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_NEW_WELL_BASELINE.EGRID"),grid);
            var original=Encoding.UTF8.GetBytes(Source);await File.WriteAllBytesAsync(path,original,TestContext.Current.CancellationToken);
            var p=Parameters;var package=new EclipseDeckPackage(path,root,[path]);
            await EclipseNewWellEditor.ApplyAsync(package,p,grid,TestContext.Current.CancellationToken);
            var applied=await File.ReadAllTextAsync(path,TestContext.Current.CancellationToken);var marker="-- GRDP controlled new water producer; existing wells and controls retained.";
            var start=applied.IndexOf(marker,StringComparison.Ordinal);var end=applied.IndexOf("TSTEP",start,StringComparison.Ordinal);
            Assert.True(start>=0&&end>start);Assert.Equal(Source,applied.Remove(start,end-start));
            Assert.Contains(" 'P2' 'G' 15 1 4000 'WAT' /",applied,StringComparison.Ordinal);
            Assert.Contains(" 'P2' 15 1 1 1 'OPEN' 0 0 0.20000000000000001 1000 0 /",applied,StringComparison.Ordinal);
            Assert.Contains(" 'P2' 'OPEN' 'WRAT' 1* 100 3* 3000 /",applied,StringComparison.Ordinal);
            await File.WriteAllBytesAsync(path,original,TestContext.Current.CancellationToken);
            var stale=p with {SourceSha256=new string('0',64)};
            var error=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseNewWellEditor.ApplyAsync(package,stale,grid,TestContext.Current.CancellationToken));
            Assert.Equal("ECLIPSE_NEW_WELL_SOURCE",error.Code);Assert.Equal(original,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
            var mask=EclipseBinaryFieldIndexParser.TryParse(grid)!.Sections.Single(s=>s.Keyword=="ACTNUM");
            var gridBytes=await File.ReadAllBytesAsync(grid,TestContext.Current.CancellationToken);var selected=(p.J-1)*p.Nx+p.I-1;
            Array.Clear(gridBytes,(int)mask.Segments[0].Offset+selected*4,4);await File.WriteAllBytesAsync(grid,gridBytes,TestContext.Current.CancellationToken);
            error=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseNewWellEditor.ApplyAsync(package,p,grid,TestContext.Current.CancellationToken));
            Assert.Equal("ECLIPSE_NEW_WELL_INACTIVE_CELL",error.Code);Assert.Equal(original,await File.ReadAllBytesAsync(path,TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
}
