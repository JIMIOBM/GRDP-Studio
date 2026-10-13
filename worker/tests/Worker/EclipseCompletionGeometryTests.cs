using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompletionGeometryTests
{
    private static readonly string[] Values = ["P       ","1","1","1","1","OPEN","0",".0","1.0","1000","0"];
    private static string Request(string field = "DIAMETER", double target = 0.2, int line = 18) => JsonSerializer.Serialize(new
    {
        schemaVersion = EclipseCompletionGeometryParameters.Schema, baselineRunId = 1, well = "P       ", sourceFile = "CASE.DATA", lineNumber = line,
        i = "1", j = "1", k1 = "1", k2 = "1", field, unit = EclipseCompletionGeometryParameters.UnitFor("METRIC",field),
        originalValue = field == "DIAMETER" ? 1 : field == "KH" ? 1000 : 0, targetValue = target, recordValues = Values
    });
    [Theory]
    [InlineData("DIAMETER",0.2,true)]
    [InlineData("KH",500,true)]
    [InlineData("SKIN",2,true)]
    [InlineData("DIAMETER",0,false)]
    [InlineData("DIAMETER",3,false)]
    [InlineData("SKIN",-1,false)]
    [InlineData("SKIN",101,false)]
    [InlineData("KH",1000,false)]
    public void StrictPhysicalAndChangedNumericParameters(string field,double target,bool expected)
    {
        using var json = JsonDocument.Parse(Request(field,target));
        Assert.Equal(expected,EclipseCompletionGeometryParameters.TryParse(json.RootElement,out _));
    }
    [Fact]
    public void RejectsPositiveCfPseudoNumbersUnknownFieldsAndAlteredRecord()
    {
        var source = Request();
        foreach (var invalid in new[] { source.Replace("\"targetValue\":0.2","\"targetValue\":\"0.2\""),
                     source.Replace("\".0\"","\"3.028\""),source.Replace("\"originalValue\":1","\"originalValue\":2"),
                     source.Replace("\"k2\":\"1\"","\"k2\":\"2\""),source[..^1]+",\"unknown\":1}" })
        {
            using var json = JsonDocument.Parse(invalid); Assert.False(EclipseCompletionGeometryParameters.TryParse(json.RootElement,out _));
        }
    }
    [Theory]
    [InlineData("DIAMETER",0.2,"0.20000000000000001")]
    [InlineData("KH",500,"500")]
    [InlineData("SKIN",2,"2")]
    public async Task ChangesExactlyOneNumericTokenPreservingMixedNewlinesAndComments(string field,double target,string token)
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-completion-number",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try
        {
            var row="'P       '   1  1  1  1 'OPEN' 0 .0 1.0 1000 0 / -- keep this comment";
            var text="RUNSPEC\r\nDIMENS\n 2 2 1 /\r\nWATER\nMETRIC\r\nGRID\nPROPS\r\nSOLUTION\nSCHEDULE\r\nWELSPECS\n'P' 'G' 1 1 1000 'WAT' /\r\n/\nCOMPDAT\r\n"+row+"\n/\r\nTSTEP\n 1 /\r\nEND\n";
            var file=Path.Combine(root,"CASE.DATA");var original=Encoding.UTF8.GetBytes(text);
            await File.WriteAllBytesAsync(file,original,TestContext.Current.CancellationToken);
            var line=text.Split('\n').Select((value,index)=>(value,index)).Single(v=>v.value.Contains("'P       '",StringComparison.Ordinal)).index+1;
            using var json=JsonDocument.Parse(Request(field,target,line));Assert.True(EclipseCompletionGeometryParameters.TryParse(json.RootElement,out var p));
            var package=new EclipseDeckPackage(file,root,[file]);
            await EclipseCompletionGeometryEditor.ApplyAsync(package,p!,TestContext.Current.CancellationToken);
            var expectedRow=field=="DIAMETER"?row.Replace(".0 1.0 1000", ".0 "+token+" 1000",StringComparison.Ordinal)
                :field=="KH"?row.Replace("1.0 1000 0", "1.0 "+token+" 0",StringComparison.Ordinal):row.Replace("1000 0 /", "1000 "+token+" /",StringComparison.Ordinal);
            Assert.Equal(Encoding.UTF8.GetBytes(text.Replace(row,expectedRow,StringComparison.Ordinal)),await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));
            await File.WriteAllBytesAsync(file,original,TestContext.Current.CancellationToken);
            var stale=p! with { RecordValues = [..p!.RecordValues.Take(10),"1"] };
            var error=await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseCompletionGeometryEditor.ApplyAsync(package,stale,TestContext.Current.CancellationToken));
            Assert.Equal("ECLIPSE_COMPLETION_GEOMETRY_SOURCE",error.Code);
            Assert.Equal(original,await File.ReadAllBytesAsync(file,TestContext.Current.CancellationToken));
        }
        finally { Directory.Delete(root,true); }
    }
}
