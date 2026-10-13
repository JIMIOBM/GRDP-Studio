using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseGroupProductionTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-group", Guid.NewGuid().ToString("N"));
    public EclipseGroupProductionTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Deck = "RUNSPEC\nFIELD\nSCHEDULE\nWELSPECS\n P1 G 1 1 /\n P2 G 1 2 /\n/\nCOMPDAT\n P1 1 1 1 1 OPEN /\n P2 1 2 1 1 OPEN /\n/\nWCONPROD\n P1 OPEN GRUP 20000 4* 2500 /\n P2 OPEN GRUP 20000 4* 2500 /\n/\nGCONPROD\n G ORAT 60000 / -- preserve this comment\n/\nTSTEP\n 1 9 /\nEND\n";
    private static EclipseGroupProductionParameters P => new(1, "G", "CASE.DATA", 17, "STB/day", 60000, 30000, ["P1", "P2"]);
    [Fact] public async Task OnlyGroupTargetChangesAndInspectionUsesNewVersion()
    {
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, Deck, TestContext.Current.CancellationToken);
        var i = EclipseDataInspector.InspectPackage([file], "CASE.DATA");
        Assert.Equal("eclipse-data-inspection/6", i.SchemaVersion); Assert.Empty(i.VfpTables!);
        Assert.Equal(2500, i.GroupControls![0].Members[0].BhpLimit);
        await EclipseGroupProductionEditor.ApplyAsync(new(file, root, [file]), P, TestContext.Current.CancellationToken);
        Assert.Equal(Deck.Replace("G ORAT 60000", "G ORAT 30000"), await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Theory][InlineData("tree")][InlineData("guide")][InlineData("efficiency")][InlineData("dynamic")][InlineData("late")][InlineData("wildcard")][InlineData("bhp")][InlineData("groupLimit")][InlineData("stale")][InlineData("members")][InlineData("unit")][InlineData("target")]
    public async Task AmbiguousOrStaleRequestLeavesInputUntouched(string fault)
    {
        var text = fault switch { "tree"=>Deck.Replace("SCHEDULE", "SCHEDULE\nGRUPTREE\nG FIELD /\n/"), "guide"=>Deck.Replace("TSTEP", "WGRUPCON\nP1 YES 100 OIL /\n/\nTSTEP"), "efficiency"=>Deck.Replace("TSTEP", "WEFAC\nP1 0.5 /\n/\nTSTEP"), "dynamic"=>Deck.Replace("TSTEP", "ACTIONX\nX /\n/\nTSTEP"), "late"=>Deck.Replace("END", "WCONPROD\nP1 SHUT /\n/\nEND"), "wildcard"=>Deck.Replace("G ORAT", "'G*' ORAT"), "bhp"=>Deck.Replace("4* 2500", "5*"), "groupLimit"=>Deck.Replace("G ORAT 60000 /", "G ORAT 60000 1000 /"), _=>Deck };
        var p = fault switch { "stale"=>P with {LineNumber=18}, "members"=>P with {Members=["P1","WRONG"]}, "unit"=>P with {Unit="sm3/day"}, "target"=>P with {OriginalTargetRate=50000}, _=>P };
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseGroupProductionEditor.ApplyAsync(new(file, root, [file]), p, TestContext.Current.CancellationToken));
        Assert.Equal(text, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Fact] public async Task MetricTargetUsesSurfaceVolumeUnitAndPreservesAllOtherBytes()
    {
        var text = Deck.Replace("FIELD", "METRIC");
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        Assert.Equal("sm3/day", EclipseGroupProductionReader.Read(text, "CASE.DATA")[0].Control.Unit);
        await EclipseGroupProductionEditor.ApplyAsync(new(file, root, [file]), P with {Unit="sm3/day"}, TestContext.Current.CancellationToken);
        Assert.Equal(text.Replace("G ORAT 60000", "G ORAT 30000"), await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Fact] public void ParameterContractRejectsExtraDuplicateAndWrongTypedFields()
    {
        const string valid = """{"schemaVersion":"eclipse-group-production-parameters/1","baselineRunId":1,"group":"G","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":17,"controlMode":"ORAT","unit":"STB/day","originalTargetRate":60000,"targetRate":30000,"members":["P1","P2"]}""";
        using var good = JsonDocument.Parse(valid); Assert.True(EclipseGroupProductionParameters.TryParse(good.RootElement, out _));
        foreach (var bad in new[] { valid.Replace("30000", "0"), valid.Replace("\"P2\"", "\"P1\""), valid.Replace("\"baselineRunId\":1", "\"baselineRunId\":\"1\""), valid.Replace("\"phase\":\"INITIAL\"", "\"phase\":\"DATES\""), valid.Replace("\"group\":\"G\"", "\"group\":\"FIELD\""), valid.Replace("\"targetRate\":30000", "\"targetRate\":30000,\"extra\":0") })
        { using var doc = JsonDocument.Parse(bad); Assert.False(EclipseGroupProductionParameters.TryParse(doc.RootElement, out _)); }
    }
}
