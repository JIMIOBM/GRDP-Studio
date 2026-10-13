using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseGroupInjectionTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-group", Guid.NewGuid().ToString("N"));
    public EclipseGroupInjectionTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Deck = "RUNSPEC\nFIELD\nSCHEDULE\nWELSPECS\n P1 G 1 1 /\n P2 G 1 2 /\n/\nCOMPDAT\n P1 1 1 1 1 OPEN /\n P2 1 2 1 1 OPEN /\n/\nWCONINJE\n P1 WATER OPEN GRUP 20000 1* 6000 /\n P2 WAT OPEN GRUP 20000 1* 6000 /\n/\nGCONINJE\n G WATER RATE 60000 / -- preserve this comment\n/\nTSTEP\n 1 9 /\nEND\n";
    private static EclipseGroupInjectionParameters P => new(1, "G", "CASE.DATA", 17, "WATER", "STB/day", 60000, 30000, ["P1", "P2"]);
    [Fact] public async Task OnlyGroupTargetChangesAndInspectionUsesNewVersion()
    {
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, Deck, TestContext.Current.CancellationToken);
        var i = EclipseDataInspector.InspectPackage([file], "CASE.DATA");
        Assert.Equal("eclipse-data-inspection/7", i.SchemaVersion); Assert.Empty(i.VfpTables!);
        Assert.Equal(6000, i.InjectionGroupControls![0].Members[0].BhpLimit);
        await EclipseGroupInjectionEditor.ApplyAsync(new(file, root, [file]), P, TestContext.Current.CancellationToken);
        Assert.Equal(Deck.Replace("G WATER RATE 60000", "G WATER RATE 30000"), await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Theory][InlineData("tree")][InlineData("guide")][InlineData("efficiency")][InlineData("dynamic")][InlineData("late")][InlineData("wildcard")][InlineData("bhp")][InlineData("groupLimit")][InlineData("stale")][InlineData("members")][InlineData("unit")][InlineData("target")][InlineData("phase")][InlineData("mixed")]
    public async Task AmbiguousOrStaleRequestLeavesInputUntouched(string fault)
    {
        var text = fault switch { "tree"=>Deck.Replace("SCHEDULE", "SCHEDULE\nGRUPTREE\nG FIELD /\n/"), "guide"=>Deck.Replace("TSTEP", "WGRUPCON\nP1 YES 100 OIL /\n/\nTSTEP"), "efficiency"=>Deck.Replace("TSTEP", "WEFAC\nP1 0.5 /\n/\nTSTEP"), "dynamic"=>Deck.Replace("TSTEP", "ACTIONX\nX /\n/\nTSTEP"), "late"=>Deck.Replace("END", "WCONINJE\nP1 WATER SHUT /\n/\nEND"), "wildcard"=>Deck.Replace("G WATER RATE", "'G*' WATER RATE"), "bhp"=>Deck.Replace("1* 6000", "3*"), "phase"=>Deck.Replace("G WATER RATE", "G GAS RATE"), "mixed"=>Deck.Replace("P2 WAT OPEN", "P2 GAS OPEN"), "groupLimit"=>Deck.Replace("G WATER RATE 60000 /", "G WATER RATE 60000 1000 /"), _=>Deck };
        var p = fault switch { "stale"=>P with {LineNumber=18}, "members"=>P with {Members=["P1","WRONG"]}, "unit"=>P with {Unit="sm3/day"}, "target"=>P with {OriginalTargetRate=50000}, _=>P };
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseGroupInjectionEditor.ApplyAsync(new(file, root, [file]), p, TestContext.Current.CancellationToken));
        Assert.Equal(text, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Fact] public async Task MetricTargetUsesSurfaceVolumeUnitAndPreservesAllOtherBytes()
    {
        var text = Deck.Replace("FIELD", "METRIC");
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        Assert.Equal("sm3/day", EclipseGroupInjectionReader.Read(text, "CASE.DATA")[0].Control.Unit);
        await EclipseGroupInjectionEditor.ApplyAsync(new(file, root, [file]), P with {Unit="sm3/day"}, TestContext.Current.CancellationToken);
        Assert.Equal(text.Replace("G WATER RATE 60000", "G WATER RATE 30000"), await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Fact] public void ParameterContractRejectsExtraDuplicateAndWrongTypedFields()
    {
        const string valid = """{"schemaVersion":"eclipse-group-injection-parameters/1","baselineRunId":1,"group":"G","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":17,"controlMode":"RATE","injectionPhase":"WATER","unit":"STB/day","originalTargetRate":60000,"targetRate":30000,"members":["P1","P2"]}""";
        using var good = JsonDocument.Parse(valid); Assert.True(EclipseGroupInjectionParameters.TryParse(good.RootElement, out _));
        foreach (var bad in new[] { valid.Replace("30000", "0"), valid.Replace("\"P2\"", "\"P1\""), valid.Replace("\"baselineRunId\":1", "\"baselineRunId\":\"1\""), valid.Replace("\"phase\":\"INITIAL\"", "\"phase\":\"DATES\""), valid.Replace("\"group\":\"G\"", "\"group\":\"FIELD\""), valid.Replace("\"targetRate\":30000", "\"targetRate\":30000,\"extra\":0") })
        { using var doc = JsonDocument.Parse(bad); Assert.False(EclipseGroupInjectionParameters.TryParse(doc.RootElement, out _)); }
    }
}
