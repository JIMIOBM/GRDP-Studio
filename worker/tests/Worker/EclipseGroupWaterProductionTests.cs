using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseGroupWaterProductionTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-wrat", Guid.NewGuid().ToString("N"));
    public EclipseGroupWaterProductionTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Deck = "RUNSPEC\nFIELD\nSCHEDULE\nWELSPECS\n P1 G 1 1 /\n P2 G 1 2 /\n/\nCOMPDAT\n P1 1 1 1 1 OPEN /\n P2 1 2 1 1 OPEN /\n/\nWCONPROD\n P1 OPEN GRUP 8000 4* 1000 /\n P2 OPEN GRUP 4000 4* 1000 /\n/\nGCONPROD\n G WRAT 1* 2000 / -- keep comment\n/\nTSTEP\n 20*10 /\nEND\n";
    private static EclipseGroupWaterProductionParameters P => new(1, "G", "CASE.DATA", 17, "WRAT", "STB/day", 2000, 1000, ["P1", "P2"]);
    [Theory][InlineData("FIELD", "STB/day")][InlineData("METRIC", "sm3/day")]
    public async Task WaterSlotOnlyChangesAndOilPressureLimitsAreBound(string units, string rateUnit)
    {
        var text = Deck.Replace("FIELD", units); var file = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        var inspection = EclipseDataInspector.InspectPackage([file], "CASE.DATA");
        Assert.Equal("eclipse-data-inspection/10", inspection.SchemaVersion);
        var c = Assert.Single(inspection.WaterProductionGroupControls!);
        Assert.Equal(8000, c.Members[0].OilRateLimit); Assert.Equal(4000, c.Members[1].OilRateLimit);
        Assert.Equal(1000, c.Members[0].BhpLimit); Assert.Equal(rateUnit, c.Unit);
        Assert.Null(inspection.GroupRateControls);
        await EclipseGroupWaterProductionEditor.ApplyAsync(new(file, root, [file]), P with { Unit = rateUnit }, TestContext.Current.CancellationToken);
        Assert.Equal(text.Replace("G WRAT 1* 2000", "G WRAT 1* 1000"), await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Theory][InlineData("GRAT")][InlineData("LRAT")][InlineData("ORAT")][InlineData("RESV")]
    public void OtherModesKeepTheirIndependentContracts(string mode)
    { Assert.Empty(EclipseGroupWaterProductionReader.Read(Deck.Replace("G WRAT", "G " + mode), "CASE.DATA")); }
    [Theory][InlineData("tree")][InlineData("guide")][InlineData("extraLimit")][InlineData("wellWaterLimit")][InlineData("connectionLimit")][InlineData("wildcard")][InlineData("missingOilLimit")][InlineData("dynamic")][InlineData("late")][InlineData("missingBhp")][InlineData("E300")]
    public void UnprovenControlsAreNotOffered(string fault)
    {
        var text = fault switch {
            "tree" => Deck.Replace("SCHEDULE", "SCHEDULE\nGRUPTREE\n G FIELD /\n/"),
            "guide" => Deck.Replace("TSTEP", "WGRUPCON\n P1 YES 1 WAT /\n/\nTSTEP"),
            "extraLimit" => Deck.Replace("G WRAT 1* 2000 /", "G WRAT 100 2000 /"),
            "wellWaterLimit" => Deck.Replace("GRUP 8000 4*", "GRUP 8000 500 3*"),
            "connectionLimit" => Deck.Replace("TSTEP", "CECON\n P1 4* 0.5 /\n/\nTSTEP"),
            "wildcard" => Deck.Replace("P1 OPEN GRUP", "'P*' OPEN GRUP"),
            "missingOilLimit" => Deck.Replace("GRUP 8000 4*", "GRUP 5*"),
            "missingBhp" => Deck.Replace("4* 1000", "5*"),
            "dynamic" => Deck.Replace("TSTEP", "UDQ\n DEFINE WUX 1 /\n/\nTSTEP"),
            "late" => Deck.Replace("END", "GCONPROD\n G WRAT 1* 1000 /\n/\nEND"),
            _ => Deck.Replace("FIELD", "FIELD\nCOMPS")
        };
        Assert.Empty(EclipseGroupWaterProductionReader.Read(text, "CASE.DATA"));
    }
    [Theory][InlineData("source")][InlineData("line")][InlineData("unit")][InlineData("mode")][InlineData("members")][InlineData("original")]
    public async Task StaleRequestNeverChangesUploadedBytes(string fault)
    {
        var p = fault switch { "source" => P with { SourceFile = "OTHER.DATA" }, "line" => P with { LineNumber = 18 }, "unit" => P with { Unit = "sm3/day" }, "mode" => P with { ControlMode = "GRAT" }, "members" => P with { Members = ["P2", "P1"] }, _ => P with { OriginalTargetRate = 3000 } };
        var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, Deck, TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseGroupWaterProductionEditor.ApplyAsync(new(file, root, [file]), p, TestContext.Current.CancellationToken));
        Assert.Equal(Deck, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Fact] public void StrictParameterShapeRejectsWrongModesTypesAndDuplicates()
    {
        const string valid = """{"schemaVersion":"eclipse-group-water-production-parameters/1","baselineRunId":1,"group":"G","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":17,"controlMode":"WRAT","unit":"STB/day","originalTargetRate":2000,"targetRate":1000,"members":["P1","P2"]}""";
        using var good = JsonDocument.Parse(valid); Assert.True(EclipseGroupWaterProductionParameters.TryParse(good.RootElement, out _));
        foreach (var bad in new[] { valid.Replace("WRAT", "LRAT"), valid.Replace("STB/day", "Mscf/day"), valid.Replace("\"P2\"", "\"P1\""), valid.Replace("\"baselineRunId\":1", "\"baselineRunId\":\"1\""), valid.Replace("\"targetRate\":1000", "\"targetRate\":1000,\"extra\":0") })
        { using var d = JsonDocument.Parse(bad); Assert.False(EclipseGroupWaterProductionParameters.TryParse(d.RootElement, out _)); }
    }
}
