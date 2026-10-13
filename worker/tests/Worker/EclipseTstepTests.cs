using Grdp.SoftwareIntegration.Worker.Execution;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseTstepTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-tstep", Guid.NewGuid().ToString("N"));
    public EclipseTstepTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root,true);
    private const string Deck = "RUNSPEC\nFIELD\nSCHEDULE\nWCONPROD\n P1 OPEN BHP 5* 3000 /\n/\nTSTEP\n10*5 /\nWCONPROD\n P1 OPEN BHP 5* 3200 /\n/\nTSTEP\n10*5 /\nEND\n";
    private static EclipseTstepParameters P => new(1,"CASE.DATA",7,"day",Enumerable.Repeat(5,10).ToArray(),[1,4,5,10,30]);
    [Fact] public async Task PreservesTimeOfFollowingControlAndEveryOtherRecord()
    {
        var file=Path.Combine(root,"CASE.DATA");await File.WriteAllTextAsync(file,Deck,TestContext.Current.CancellationToken);
        await EclipseTstepEditor.ApplyAsync(new(file,root,[file]),P,TestContext.Current.CancellationToken);
        Assert.Equal(Deck.Replace("TSTEP\n10*5 /\nWCONPROD","TSTEP\n1 4 5 10 30 /\nWCONPROD"),await File.ReadAllTextAsync(file,TestContext.Current.CancellationToken));
    }
    [Theory][InlineData("sum")][InlineData("stale")][InlineData("dates")][InlineData("restart")][InlineData("action")][InlineData("lab")][InlineData("zero")]
    public async Task UnsafeStepRecordNeverWrites(string fault)
    {
        var text=fault switch { "dates"=>Deck.Replace("TSTEP\n10*5 /\nEND","DATES\n1 JAN 1997 /\n/\nEND"),"restart"=>Deck.Replace("FIELD","FIELD\nRESTART\nOLD 1 /"),"action"=>Deck.Replace("SCHEDULE","SCHEDULE\nACTIONX\nX /\n/"),"lab"=>Deck.Replace("FIELD","LAB"),"zero"=>Deck.Replace("10*5","0 50"),_=>Deck };
        var p=fault switch { "sum"=>P with { TargetSteps=[1,2] },"stale"=>P with { LineNumber=8 },_=>P };
        var file=Path.Combine(root,"CASE.DATA");await File.WriteAllTextAsync(file,text,TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseTstepEditor.ApplyAsync(new(file,root,[file]),p,TestContext.Current.CancellationToken));
        Assert.Equal(text,await File.ReadAllTextAsync(file,TestContext.Current.CancellationToken));
    }
}
