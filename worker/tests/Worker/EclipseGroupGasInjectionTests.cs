using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseGroupGasInjectionTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-group", Guid.NewGuid().ToString("N"));
    public EclipseGroupGasInjectionTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Deck = "RUNSPEC\nFIELD\nSCHEDULE\nWELSPECS\n P1 G 1 1 /\n P2 G 1 2 /\n/\nCOMPDAT\n P1 1 1 1 1 OPEN /\n P2 1 2 1 1 OPEN /\n/\nWCONINJE\n P1 GAS OPEN GRUP 20000 1* 6000 /\n P2 GAS OPEN GRUP 20000 1* 6000 /\n/\nGCONINJE\n G GAS RATE 60000 / -- preserve this comment\n/\nTSTEP\n 1 9 /\nEND\n";
    private static EclipseGroupGasInjectionParameters P => new(1, "G", "CASE.DATA", 17, "GAS", "Mscf/day", 60000, 30000, ["P1", "P2"]);
    [Fact] public async Task OnlyGroupTargetChangesAndInspectionUsesNewVersion()
    {
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, Deck, TestContext.Current.CancellationToken);
        var i = EclipseDataInspector.InspectPackage([file], "CASE.DATA");
        Assert.Equal("eclipse-data-inspection/8", i.SchemaVersion); Assert.Empty(i.VfpTables!);
        Assert.Equal(6000, i.GasInjectionGroupControls![0].Members[0].BhpLimit);
        await EclipseGroupGasInjectionEditor.ApplyAsync(new(file, root, [file]), P, TestContext.Current.CancellationToken);
        Assert.Equal(Deck.Replace("G GAS RATE 60000", "G GAS RATE 30000"), await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Theory][InlineData("tree")][InlineData("guide")][InlineData("efficiency")][InlineData("dynamic")][InlineData("late")][InlineData("wildcard")][InlineData("bhp")][InlineData("groupLimit")][InlineData("stale")][InlineData("members")][InlineData("unit")][InlineData("target")][InlineData("phase")][InlineData("mixed")]
    public async Task AmbiguousOrStaleRequestLeavesInputUntouched(string fault)
    {
        var text = fault switch { "tree"=>Deck.Replace("SCHEDULE", "SCHEDULE\nGRUPTREE\nG FIELD /\n/"), "guide"=>Deck.Replace("TSTEP", "WGRUPCON\nP1 YES 100 OIL /\n/\nTSTEP"), "efficiency"=>Deck.Replace("TSTEP", "WEFAC\nP1 0.5 /\n/\nTSTEP"), "dynamic"=>Deck.Replace("TSTEP", "ACTIONX\nX /\n/\nTSTEP"), "late"=>Deck.Replace("END", "WCONINJE\nP1 GAS SHUT /\n/\nEND"), "wildcard"=>Deck.Replace("G GAS RATE", "'G*' GAS RATE"), "bhp"=>Deck.Replace("1* 6000", "3*"), "phase"=>Deck.Replace("G GAS RATE", "G WATER RATE"), "mixed"=>Deck.Replace("P2 GAS OPEN", "P2 WATER OPEN"), "groupLimit"=>Deck.Replace("G GAS RATE 60000 /", "G GAS RATE 60000 1000 /"), _=>Deck };
        var p = fault switch { "stale"=>P with {LineNumber=18}, "members"=>P with {Members=["P1","WRONG"]}, "unit"=>P with {Unit="sm3/day"}, "target"=>P with {OriginalTargetRate=50000}, _=>P };
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseGroupGasInjectionEditor.ApplyAsync(new(file, root, [file]), p, TestContext.Current.CancellationToken));
        Assert.Equal(text, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Fact] public async Task MetricTargetUsesSurfaceVolumeUnitAndPreservesAllOtherBytes()
    {
        var text = Deck.Replace("FIELD", "METRIC");
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        Assert.Equal("sm3/day", EclipseGroupGasInjectionReader.Read(text, "CASE.DATA")[0].Control.Unit);
        await EclipseGroupGasInjectionEditor.ApplyAsync(new(file, root, [file]), P with {Unit="sm3/day"}, TestContext.Current.CancellationToken);
        Assert.Equal(text.Replace("G GAS RATE 60000", "G GAS RATE 30000"), await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Fact] public async Task CoexistingWaterGroupRetainsFrozenWaterInspection()
    {
        var text = Deck.Replace(" P2 G 1 2 /", " P2 G 1 2 /\n W1 W 1 3 /\n W2 W 1 4 /")
            .Replace(" P2 1 2 1 1 OPEN /", " P2 1 2 1 1 OPEN /\n W1 1 3 1 1 OPEN /\n W2 1 4 1 1 OPEN /")
            .Replace(" P2 GAS OPEN GRUP 20000 1* 6000 /", " P2 GAS OPEN GRUP 20000 1* 6000 /\n W1 WATER OPEN GRUP 20000 1* 6000 /\n W2 WATER OPEN GRUP 20000 1* 6000 /")
            .Replace("G GAS RATE 60000 /", "G GAS RATE 60000 /\n W WATER RATE 1000 /");
        Assert.Empty(EclipseGroupGasInjectionReader.Read(text,"CASE.DATA"));
        Assert.Single(EclipseGroupInjectionReader.Read(text,"CASE.DATA"));
        var file = Path.Combine(root,"CASE.DATA"); await File.WriteAllTextAsync(file,text,TestContext.Current.CancellationToken);
        var inspection = EclipseDataInspector.InspectPackage([file],"CASE.DATA");
        Assert.Equal("eclipse-data-inspection/7",inspection.SchemaVersion);
        Assert.Null(inspection.GasInjectionGroupControls);
    }
    [Fact] public void ParameterContractRejectsExtraDuplicateAndWrongTypedFields()
    {
        const string valid = """{"schemaVersion":"eclipse-group-gas-injection-parameters/1","baselineRunId":1,"group":"G","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":17,"controlMode":"RATE","injectionPhase":"GAS","unit":"Mscf/day","originalTargetRate":60000,"targetRate":30000,"members":["P1","P2"]}""";
        using var good = JsonDocument.Parse(valid); Assert.True(EclipseGroupGasInjectionParameters.TryParse(good.RootElement, out _));
        foreach (var bad in new[] { valid.Replace("30000", "0"), valid.Replace("\"P2\"", "\"P1\""), valid.Replace("\"baselineRunId\":1", "\"baselineRunId\":\"1\""), valid.Replace("\"phase\":\"INITIAL\"", "\"phase\":\"DATES\""), valid.Replace("\"group\":\"G\"", "\"group\":\"FIELD\""), valid.Replace("\"targetRate\":30000", "\"targetRate\":30000,\"extra\":0") })
        { using var doc = JsonDocument.Parse(bad); Assert.False(EclipseGroupGasInjectionParameters.TryParse(doc.RootElement, out _)); }
    }
}
