using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseProductionControlTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-rate-control", Guid.NewGuid().ToString("N"));
    public EclipseProductionControlTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private static string Deck(string mode, string units) => $"RUNSPEC\n{units}\nSCHEDULE\nWCONPROD\n 'P1' 'OPEN' '{mode}' {(mode == "WRAT" ? "1*" : mode == "GRAT" ? "2*" : "3*")} 1000 / -- preserve defaults\n/\nTSTEP\n10*5 /\nEND\n";
    private static string Request(string mode, string units) => JsonSerializer.Serialize(new {
        schemaVersion = "eclipse-production-control-parameters/1", baselineRunId = 1, well = "P1", phase = "INITIAL",
        sourceFile = "CASE.DATA", lineNumber = 5, controlMode = mode, originalTargetRate = 1000, targetRate = 500,
        unit = units == "METRIC" ? "sm3/day" : mode == "GRAT" ? "Mscf/day" : "STB/day"
    });
    [Theory]
    [InlineData("WRAT", "FIELD")]
    [InlineData("GRAT", "FIELD")]
    [InlineData("LRAT", "FIELD")]
    [InlineData("WRAT", "METRIC")]
    [InlineData("GRAT", "METRIC")]
    [InlineData("LRAT", "METRIC")]
    public async Task ChangesOnlyTheSelectedExpandedRateField(string mode, string units)
    {
        using var doc = JsonDocument.Parse(Request(mode, units));
        Assert.True(EclipseProductionControlParameters.TryParse(doc.RootElement, out var p));
        var path = Path.Combine(root, "CASE.DATA");
        var text = Deck(mode, units);
        await File.WriteAllTextAsync(path, text, TestContext.Current.CancellationToken);
        await EclipseProductionControlEditor.ApplyAsync(new(path, root, [path]), p!, TestContext.Current.CancellationToken);
        Assert.Equal(text.Replace("1000 /", "500 /"), await File.ReadAllTextAsync(path, TestContext.Current.CancellationToken));
    }
    [Theory]
    [InlineData("stale")]
    [InlineData("mode")]
    [InlineData("unit")]
    [InlineData("default")]
    [InlineData("repeat")]
    [InlineData("later")]
    [InlineData("override")]
    [InlineData("shut")]
    [InlineData("include")]
    [InlineData("duplicate")]
    public async Task AmbiguityOrSourceMismatchDoesNotWrite(string fault)
    {
        var text = Deck("WRAT", "FIELD");
        text = fault switch {
            "default" => text.Replace("1* 1000", "2*"), "repeat" => text.Replace("1* 1000", "1* 2*1000"),
            "later" => text.Replace("WCONPROD", "TSTEP\n1 /\nWCONPROD"),
            "override" => text.Replace("TSTEP", "WELTARG\n P1 WRAT 900 /\n/\nTSTEP"),
            "shut" => text.Replace("'OPEN'", "'SHUT'"), "include" => text + "INCLUDE\n 'OTHER.INC' /\n",
            "duplicate" => text.Replace("/ -- preserve defaults", "/\n 'P1' OPEN WRAT 1* 1000 / -- preserve defaults"), _ => text
        };
        var request = Request("WRAT", "FIELD");
        if (fault == "stale") request = request.Replace("\"originalTargetRate\":1000", "\"originalTargetRate\":900");
        if (fault == "mode") request = request.Replace("WRAT", "LRAT");
        if (fault == "unit") request = request.Replace("STB/day", "sm3/day");
        using var doc = JsonDocument.Parse(request);
        Assert.True(EclipseProductionControlParameters.TryParse(doc.RootElement, out var p));
        var path = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(path, text, TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseProductionControlEditor.ApplyAsync(new(path, root, [path]), p!, TestContext.Current.CancellationToken));
        Assert.Equal(text, await File.ReadAllTextAsync(path, TestContext.Current.CancellationToken));
    }
    [Theory]
    [InlineData("\"targetRate\":500", "\"targetRate\":0")]
    [InlineData("\"targetRate\":500", "\"targetRate\":1000")]
    [InlineData("CASE.DATA", "../CASE.DATA")]
    [InlineData("WRAT", "ORAT")]
    [InlineData("STB/day", "Mscf/day")]
    [InlineData("\"baselineRunId\":1", "\"baselineRunId\":1,\"extra\":true")]
    public void InvalidContractsReject(string from, string to)
    {
        using var doc = JsonDocument.Parse(Request("WRAT", "FIELD").Replace(from, to));
        Assert.False(EclipseProductionControlParameters.TryParse(doc.RootElement, out _));
    }
    [Fact]
    public async Task RepeatedTimeStepsRemainAVisibleInitialControlBoundary()
    {
        var path = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(path, Deck("WRAT", "FIELD").Replace("FIELD\n", "FIELD\nDIMENS\n 1 1 1 /\n"), TestContext.Current.CancellationToken);
        var inspection = EclipseDataInspector.Inspect(path, "CASE.DATA");
        var timeline = Assert.Single(inspection.ScheduleTimeline!);
        Assert.Equal("TSTEP", timeline.Kind);
        Assert.Equal(10, timeline.Steps!.Count);
        Assert.All(timeline.Steps, step => Assert.Equal("5", step));
    }
}
