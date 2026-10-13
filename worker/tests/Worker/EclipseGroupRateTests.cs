using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseGroupRateTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-group", Guid.NewGuid().ToString("N"));
    public EclipseGroupRateTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Deck = "RUNSPEC\nFIELD\nSCHEDULE\nWELSPECS\n P1 G 1 1 /\n P2 G 1 2 /\n/\nCOMPDAT\n P1 1 1 1 1 OPEN /\n P2 1 2 1 1 OPEN /\n/\nWCONPROD\n P1 OPEN GRUP 20000 4* 2500 /\n P2 OPEN GRUP 20000 4* 2500 /\n/\nGCONPROD\n G GRAT 2* 60000 / -- preserve this comment\n/\nTSTEP\n 1 9 /\nEND\n";
    private static EclipseGroupRateParameters P => new(1, "G", "CASE.DATA", 17, "GRAT", "Mscf/day", 60000, 30000, ["P1", "P2"]);
    [Fact] public async Task OnlyGroupTargetChangesAndInspectionUsesNewVersion()
    {
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, Deck, TestContext.Current.CancellationToken);
        var i = EclipseDataInspector.InspectPackage([file], "CASE.DATA");
        Assert.Equal("eclipse-data-inspection/9", i.SchemaVersion); Assert.Empty(i.VfpTables!);
        Assert.Equal(2500, i.GroupRateControls![0].Members[0].BhpLimit);
        await EclipseGroupRateEditor.ApplyAsync(new(file, root, [file]), P, TestContext.Current.CancellationToken);
        Assert.Equal(Deck.Replace("G GRAT 2* 60000", "G GRAT 2* 30000"), await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Theory][InlineData("tree")][InlineData("guide")][InlineData("efficiency")][InlineData("dynamic")][InlineData("late")][InlineData("wildcard")][InlineData("bhp")][InlineData("groupLimit")][InlineData("stale")][InlineData("members")][InlineData("unit")][InlineData("target")]
    public async Task AmbiguousOrStaleRequestLeavesInputUntouched(string fault)
    {
        var text = fault switch { "tree"=>Deck.Replace("SCHEDULE", "SCHEDULE\nGRUPTREE\nG FIELD /\n/"), "guide"=>Deck.Replace("TSTEP", "WGRUPCON\nP1 YES 100 OIL /\n/\nTSTEP"), "efficiency"=>Deck.Replace("TSTEP", "WEFAC\nP1 0.5 /\n/\nTSTEP"), "dynamic"=>Deck.Replace("TSTEP", "ACTIONX\nX /\n/\nTSTEP"), "late"=>Deck.Replace("END", "WCONPROD\nP1 SHUT /\n/\nEND"), "wildcard"=>Deck.Replace("G GRAT", "'G*' GRAT"), "bhp"=>Deck.Replace("4* 2500", "5*"), "groupLimit"=>Deck.Replace("G GRAT 2* 60000 /", "G GRAT 2* 60000 1000 /"), _=>Deck };
        var p = fault switch { "stale"=>P with {LineNumber=18}, "members"=>P with {Members=["P1","WRONG"]}, "unit"=>P with {Unit="sm3/day"}, "target"=>P with {OriginalTargetRate=50000}, _=>P };
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseGroupRateEditor.ApplyAsync(new(file, root, [file]), p, TestContext.Current.CancellationToken));
        Assert.Equal(text, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Fact] public async Task MetricTargetUsesSurfaceVolumeUnitAndPreservesAllOtherBytes()
    {
        var text = Deck.Replace("FIELD", "METRIC");
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        Assert.Equal("sm3/day", EclipseGroupRateReader.Read(text, "CASE.DATA")[0].Control.Unit);
        await EclipseGroupRateEditor.ApplyAsync(new(file, root, [file]), P with {Unit="sm3/day"}, TestContext.Current.CancellationToken);
        Assert.Equal(text.Replace("G GRAT 2* 60000", "G GRAT 2* 30000"), await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Fact] public void ParameterContractRejectsExtraDuplicateAndWrongTypedFields()
    {
        const string valid = """{"schemaVersion":"eclipse-group-rate-parameters/1","baselineRunId":1,"group":"G","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":17,"controlMode":"GRAT","unit":"Mscf/day","originalTargetRate":60000,"targetRate":30000,"members":["P1","P2"]}""";
        using var good = JsonDocument.Parse(valid); Assert.True(EclipseGroupRateParameters.TryParse(good.RootElement, out _));
        foreach (var bad in new[] { valid.Replace("30000", "0"), valid.Replace("\"P2\"", "\"P1\""), valid.Replace("\"baselineRunId\":1", "\"baselineRunId\":\"1\""), valid.Replace("\"phase\":\"INITIAL\"", "\"phase\":\"DATES\""), valid.Replace("\"group\":\"G\"", "\"group\":\"FIELD\""), valid.Replace("\"targetRate\":30000", "\"targetRate\":30000,\"extra\":0") })
        { using var doc = JsonDocument.Parse(bad); Assert.False(EclipseGroupRateParameters.TryParse(doc.RootElement, out _)); }
    }
    [Theory][InlineData("GRAT", "G GRAT 2* 60000", "Mscf/day")][InlineData("LRAT", "G LRAT 3* 60000", "STB/day")]
    public async Task OfficialTargetSlotPreservesDefaultsAndAllOtherBytes(string mode,string record,string unit)
    {
        var text=Deck.Replace("G GRAT 2* 60000",record);
        var file=Path.Combine(root,"CASE.DATA"); await File.WriteAllTextAsync(file,text,TestContext.Current.CancellationToken);
        var found=Assert.Single(EclipseGroupRateReader.Read(text,"CASE.DATA"));
        Assert.Equal(mode,found.Control.ControlMode); Assert.Equal(unit,found.Control.Unit);
        await EclipseGroupRateEditor.ApplyAsync(new(file,root,[file]),P with {ControlMode=mode,Unit=unit},TestContext.Current.CancellationToken);
        Assert.Equal(text.Replace(record,record.Replace("60000","30000")),await File.ReadAllTextAsync(file,TestContext.Current.CancellationToken));
    }
    [Theory][InlineData("G WRAT 1* 60000")][InlineData("G ORAT 60000")][InlineData("G GRAT 60000")][InlineData("G GRAT 2* 2*60000")][InlineData("G LRAT 2* 60000")][InlineData("G GRAT 100 1* 60000")]
    public void UnsupportedModeMisplacedOrRepeatedTargetNeverOffered(string record)
    { Assert.Empty(EclipseGroupRateReader.Read(Deck.Replace("G GRAT 2* 60000",record),"CASE.DATA")); }
    [Theory][InlineData("COMPS")][InlineData("THERMAL")][InlineData("TEMP")]
    public void E300AndThermalAreSeparateCapabilities(string keyword)
    { Assert.Empty(EclipseGroupRateReader.Read(Deck.Replace("FIELD","FIELD\n"+keyword),"CASE.DATA")); }
    [Fact] public void OldOratInspectorAndContractStayFrozen()
    {
        var orat=Deck.Replace("G GRAT 2* 60000","G ORAT 60000");
        Assert.Single(EclipseGroupProductionReader.Read(orat,"CASE.DATA"));
        Assert.Empty(EclipseGroupRateReader.Read(orat,"CASE.DATA"));
        Assert.Empty(EclipseGroupProductionReader.Read(Deck,"CASE.DATA"));
    }
    [Fact] public async Task CorrectUnitWithWrongModeCannotEditInput()
    {
        var file=Path.Combine(root,"CASE.DATA");await File.WriteAllTextAsync(file,Deck,TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseGroupRateEditor.ApplyAsync(new(file,root,[file]),P with {ControlMode="LRAT"},TestContext.Current.CancellationToken));
        Assert.Equal(Deck,await File.ReadAllTextAsync(file,TestContext.Current.CancellationToken));
    }

}
