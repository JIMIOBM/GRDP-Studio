using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseThpControlTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-thp", Guid.NewGuid().ToString("N"));
    public EclipseThpControlTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Table = "VFPPROD\n1 6900 LIQ WCT GOR /\n1 3000 /\n200 1000 /\n0 .8 /\n1 4 /\n0 /\n1 1 1 1 2000 2100 /\n1 1 2 1 2100 2200 /\n1 2 1 1 2200 2300 /\n1 2 2 1 2300 2400 /\n2 1 1 1 2400 2500 /\n2 1 2 1 2500 2600 /\n2 2 1 1 2600 2700 /\n2 2 2 1 2700 2800 /\n";
    private static string Deck => "RUNSPEC\nFIELD\nSCHEDULE\n" + Table + "ECHO\nWCONPROD\n HORIZ OPEN THP 5* 400 500 1 /\n/\nTSTEP\n1 9 /\nEND\n";
    private static EclipseThpControlParameters Parameters(string text, double target = 700) => new(1, "HORIZ", "CASE.DATA", 21, "psia", 500, target, 1, EclipseVfpTableReader.Read(text, "CASE.DATA").Single().Sha256, 0);
    [Fact]
    public void CompleteTableIsBoundAndHasExactAxes()
    {
        var table = Assert.Single(EclipseVfpTableReader.Read(Deck, "CASE.DATA"));
        Assert.Equal(1, table.Number);
        Assert.Equal(6900, table.DatumDepth);
        Assert.Equal("LIQ", table.FlowType);
        Assert.Equal(new double[] { 200, 1000 }, table.ThpAxis);
        Assert.Equal(64, table.Sha256.Length);
    }
    [Theory]
    [InlineData("missing")]
    [InlineData("duplicate")]
    [InlineData("extra")]
    [InlineData("axis")]
    [InlineData("units")]
    [InlineData("temperature")]
    [InlineData("late")]
    public void InvalidOrLaterTablesAreNotOffered(string fault)
    {
        var text = fault switch {
            "missing" => Deck.Replace("2 2 2 1 2700 2800 /", ""),
            "duplicate" => Deck.Replace("2 2 2 1", "2 2 1 1"),
            "extra" => Deck.Replace("ECHO", "1 1 1 1 2000 2100 /\nECHO"),
            "axis" => Deck.Replace("200 1000 /", "1000 200 /"),
            "units" => Deck.Replace("LIQ WCT GOR /", "LIQ WCT GOR THP 1* METRIC /"),
            "temperature" => Deck.Replace("LIQ WCT GOR /", "LIQ WCT GOR THP 1* FIELD TEMP /"),
            _ => Deck.Replace("VFPPROD", "TSTEP\n1 /\nVFPPROD")
        };
        Assert.Empty(EclipseVfpTableReader.Read(text, "CASE.DATA"));
    }
    [Fact]
    public async Task ChangesOnlyThpAndPreservesTableBhpAndAlq()
    {
        var path = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(path, Deck, TestContext.Current.CancellationToken);
        await EclipseThpControlEditor.ApplyAsync(new(path, root, [path]), Parameters(Deck), TestContext.Current.CancellationToken);
        Assert.Equal(Deck.Replace("400 500 1", "400 700 1"), await File.ReadAllTextAsync(path, TestContext.Current.CancellationToken));
    }
    [Theory]
    [InlineData("hash")]
    [InlineData("table")]
    [InlineData("alq")]
    [InlineData("outside")]
    [InlineData("override")]
    [InlineData("mode")]
    public async Task UnsafeBindingDoesNotWrite(string fault)
    {
        var text = fault switch { "override" => Deck.Replace("TSTEP", "WELTARG\n HORIZ THP 600 /\n/\nTSTEP"), "mode" => Deck.Replace("OPEN THP", "OPEN BHP"), _ => Deck };
        var p = Parameters(Deck);
        p = fault switch { "hash" => p with { VfpTableSha256 = new string('a', 64) }, "table" => p with { VfpTableNumber = 2 }, "alq" => p with { OriginalAlq = 1 }, "outside" => p with { TargetThp = 1200 }, _ => p };
        var path = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(path, text, TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseThpControlEditor.ApplyAsync(new(path, root, [path]), p, TestContext.Current.CancellationToken));
        Assert.Equal(text, await File.ReadAllTextAsync(path, TestContext.Current.CancellationToken));
    }
    [Theory]
    [InlineData("lineNumber", "\"21\"")]
    [InlineData("baselineRunId", "null")]
    [InlineData("targetThp", "0")]
    [InlineData("vfpTableNumber", "1.5")]
    public void MalformedContractDoesNotThrow(string field, string value)
    {
        var json = JsonSerializer.Serialize(new { schemaVersion = EclipseThpControlParameters.Schema, baselineRunId = 1, well = "HORIZ", phase = "INITIAL", sourceFile = "CASE.DATA", lineNumber = 21, unit = "psia", originalThp = 500, targetThp = 700, vfpTableNumber = 1, vfpTableSha256 = new string('a', 64), originalAlq = 0 });
        json = System.Text.RegularExpressions.Regex.Replace(json, "\"" + field + "\":[0-9]+", "\"" + field + "\":" + value);
        using var document = JsonDocument.Parse(json);
        Assert.False(EclipseThpControlParameters.TryParse(document.RootElement, out _));
    }
}
