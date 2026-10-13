using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseControlPlanTests : IDisposable
{
    private readonly string root=Path.Combine(Path.GetTempPath(),"grdp-control-plan",Guid.NewGuid().ToString("N"));
    public EclipseControlPlanTests()=>Directory.CreateDirectory(root);
    public void Dispose()=>Directory.Delete(root,true);
    private static string Sample()=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures","ECLIPSE_CONTROL_PLAN.DATA"));
    [Fact] public void NativeReferenceHasCompleteInitialAndThreeDateChains()
    {
        var s=EclipseControlPlanReader.Read(Sample(),"CASE.DATA");Assert.NotNull(s);Assert.Equal("1982-10-19",s.Plan.StartDate);Assert.Equal(3650,s.Plan.EndTimeDays);
        Assert.Equal(10,s.Targets.Count);Assert.Equal(new[]{0,365,730,1095},s.Targets.Where(t=>t.Record.Well=="PRODUCR1").Select(t=>t.Record.TimeDays));
        Assert.All(s.Plan.Records,c=>{Assert.Equal(500,c.OriginalOilRate);Assert.Equal(2500,c.BhpLimit);Assert.Equal("STB/day",c.Unit);});
        Assert.Equal(new[]{"1982-10-19","1983-10-19","1984-10-18","1985-10-18"},s.Plan.Records.Where(c=>c.Well=="PRODUCR2").Select(c=>c.Date));
    }
    [Fact] public async Task PackageInspection13KeepsNativeCompleteControlChain()
    {
        var path=Path.Combine(root,"CASE.DATA");await File.WriteAllTextAsync(path,Sample(),TestContext.Current.CancellationToken);
        var i=EclipseDataInspector.InspectPackage([path],"CASE.DATA");Assert.Equal("eclipse-data-inspection/13",i.SchemaVersion);
        Assert.NotNull(i.ProductionControlPlan);Assert.Equal(10,i.ProductionControlPlan.Records.Count);Assert.Empty(i.VfpTables!);Assert.Null(i.GroupStructureControls);
        var json=JsonSerializer.Serialize(i,new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase});
        Assert.Equal(10,JsonDocument.Parse(json).RootElement.GetProperty("productionControlPlan").GetProperty("records").GetArrayLength());
    }
    [Fact] public async Task SixEditsAreAtomicAndOnlyReplaceTheirExactOriginalTokens()
    {
        var original=Sample();var snapshot=EclipseControlPlanReader.Read(original,"CASE.DATA")!;
        var edits=snapshot.Targets.Where(t=>t.Record.TimeDays>0).Select(t=>{var c=t.Record;return new EclipseControlPlanEdit(c.Well,c.SourceFile,c.LineNumber,c.Date,c.TimeDays,c.Unit,c.OriginalOilRate,c.TimeDays==365?250:c.TimeDays==730?350:450);}).ToArray();
        var expected=original;
        foreach(var t in snapshot.Targets.Where(t=>t.Record.TimeDays>0).OrderByDescending(t=>t.Start))expected=expected[..t.Start]+(t.Record.TimeDays==365?"250":t.Record.TimeDays==730?"350":"450")+expected[(t.Start+t.Length)..];
        var path=Path.Combine(root,"CASE.DATA");await File.WriteAllTextAsync(path,original,TestContext.Current.CancellationToken);
        await EclipseControlPlanEditor.ApplyAsync(new(path,root,[path]),new(1,edits),TestContext.Current.CancellationToken);
        Assert.Equal(expected,await File.ReadAllTextAsync(path,TestContext.Current.CancellationToken));
        var changed=EclipseControlPlanReader.Read(expected,"CASE.DATA")!;Assert.Equal(snapshot.Plan.EndTimeDays,changed.Plan.EndTimeDays);Assert.Equal(snapshot.Plan.StartDate,changed.Plan.StartDate);
    }
    [Theory][InlineData("duplicate")][InlineData("reverseDate")][InlineData("clock")][InlineData("mixedTime")][InlineData("wildcard")][InlineData("shut")][InlineData("otherLimit")][InlineData("group")][InlineData("laterGeometry")][InlineData("unknownWell")][InlineData("mode")][InlineData("economic")]
    public void AmbiguousOrUnsupportedScheduleDoesNotExposeEdits(string fault)
    {
        var original=Sample().Replace("\r\n","\n");var s=original;s=fault switch {
            "duplicate"=>s.Replace(" 'PRODUCR1' 'OPEN' 'ORAT' 500 4* 2500 /"," 'PRODUCR1' 'OPEN' 'ORAT' 500 4* 2500 /\n 'PRODUCR1' 'OPEN' 'ORAT' 500 4* 2500 /"),
            "reverseDate"=>s.Replace("19 'OCT' 1983", "19 'OCT' 1982"),
            "clock"=>s.Replace("19 'OCT' 1983 /", "19 'OCT' 1983 '06:00:00' /"),
            "mixedTime"=>s.Replace("DATES", "TSTEP\n 1 /\nDATES"),
            "wildcard"=>s.Replace("'PRODUCR1' 'OPEN'", "'PRODUCR*' 'OPEN'"),
            "shut"=>s.Replace("'PRODUCR1' 'OPEN'", "'PRODUCR1' 'SHUT'"),
            "otherLimit"=>s.Replace("500 4* 2500", "500 100 3* 2500"),
            "group"=>s.Replace("DATES", "GCONPROD\n G ORAT 2000 /\n/\nDATES"),
            "laterGeometry"=>s.Replace("WCONPROD\n 'PRODUCR1'", "COMPDAT\n PRODUCR1 5 5 1 1 OPEN /\n/\nWCONPROD\n 'PRODUCR1'"),
            "unknownWell"=>s.Replace(" 'PRODUCR1' 'OPEN'", " 'UNKNOWN' 'OPEN'"),
            "mode"=>s.Replace("'ORAT' 500", "'LRAT' 500"),
            _=>s.Replace("DATES", "WECON\n PRODUCR1 1 /\n/\nDATES")};
        Assert.NotEqual(original,s);Assert.Null(EclipseControlPlanReader.Read(s,"CASE.DATA"));
    }
    [Theory][InlineData("date")][InlineData("time")][InlineData("original")][InlineData("unit")][InlineData("well")][InlineData("source")][InlineData("order")]
    public async Task StaleLastEditCannotLeaveAnEarlierTokenPartiallyChanged(string fault)
    {
        var original=Sample();var snapshot=EclipseControlPlanReader.Read(original,"CASE.DATA")!;
        var edits=snapshot.Targets.Where(t=>t.Record.TimeDays>0).Select(t=>{var c=t.Record;return new EclipseControlPlanEdit(c.Well,c.SourceFile,c.LineNumber,c.Date,c.TimeDays,c.Unit,c.OriginalOilRate,250);}).ToArray();
        edits[^1]=fault switch {"date"=>edits[^1] with {Date="1985-10-19"},"time"=>edits[^1] with {TimeDays=1096},"original"=>edits[^1] with {OriginalOilRate=501},"unit"=>edits[^1] with {Unit="sm3/day"},"well"=>edits[^1] with {Well="PRODUCR3"},"source"=>edits[^1] with {SourceFile="OTHER.DATA"},_=>edits[0]};
        var path=Path.Combine(root,"CASE.DATA");await File.WriteAllTextAsync(path,original,TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseControlPlanEditor.ApplyAsync(new(path,root,[path]),new(1,edits),TestContext.Current.CancellationToken));
        Assert.Equal(original,await File.ReadAllTextAsync(path,TestContext.Current.CancellationToken));
    }
    [Fact] public void StrictParametersKeepDatesUnitsAndSourceOrder()
    {
        var c=EclipseControlPlanReader.Read(Sample(),"CASE.DATA")!.Plan.Records[4];
        var edit=new {c.Well,c.SourceFile,c.LineNumber,c.Date,c.TimeDays,c.Unit,c.OriginalOilRate,TargetOilRate=250};
        var json=JsonSerializer.SerializeToElement(new {schemaVersion=EclipseControlPlanParameters.Schema,baselineRunId=1,edits=new[]{edit}},new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase});
        Assert.True(EclipseControlPlanParameters.TryParse(json,out var p));Assert.Single(p!.Edits);
        foreach(var old in new[]{"\"targetOilRate\":250","\"timeDays\":365","\"unit\":\"STB/day\""}){
            var modified=json.GetRawText().Replace(old,old.StartsWith("\"target")?"\"targetOilRate\":0":old.StartsWith("\"time")?"\"timeDays\":-1":"\"unit\":\"RB/day\"");
            Assert.False(EclipseControlPlanParameters.TryParse(JsonDocument.Parse(modified).RootElement,out _));
        }
    }
    [Fact] public void MetricChangesRateAndPressureUnitsWithoutChangingDates()
    {
        var s=EclipseControlPlanReader.Read(System.Text.RegularExpressions.Regex.Replace(Sample(),"(?m)^FIELD(?=[ \\t\\r\\n])","METRIC"),"CASE.DATA");
        Assert.NotNull(s);Assert.All(s.Plan.Records,c=>Assert.Equal("sm3/day",c.Unit));Assert.Equal(3650,s.Plan.EndTimeDays);
    }
}
