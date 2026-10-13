using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseProductionTargetTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-production-target", Guid.NewGuid().ToString("N"));
    public EclipseProductionTargetTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Request = """
        {"schemaVersion":"eclipse-production-target-parameters/1","baselineRunId":1,"well":"P1",
         "phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":6,"unit":"STB/day",
         "originalOilRate":20000,"targetOilRate":10000}
        """;
    private const string Deck = "FIELD\nRUNSPEC\nSCHEDULE\nWCONPROD\n-- keep this comment\n 'P1' 'OPEN'\n 'ORAT' 20000 4* 2500 / -- pressure limit preserved\n 'P2' 'OPEN' 'ORAT' 20000 4* 2500 /\n/\nTSTEP\n10*365 /\nEND\n";

    [Fact] public async Task OfficialDecoratedScheduleHeaderRetainsSourceMetadata()
    {
        var path = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(path, Deck.Replace("SCHEDULE\n", "SCHEDULE ==========\n"), TestContext.Current.CancellationToken);
        var inspection = EclipseDataInspector.Inspect(path, "CASE.DATA");
        Assert.NotNull(inspection.ScheduleMetadata);
        Assert.Equal("P1", inspection.ScheduleMetadata.Records[0].Values[0]);
        Assert.Equal(6, inspection.ScheduleMetadata.Records[0].LineNumber);
    }

    [Fact] public void StrictContractRejectsExtraFieldsDuplicatesPathsUnitsAndUnchangedTargets()
    {
        using var valid = JsonDocument.Parse(Request);
        Assert.True(EclipseProductionTargetParameters.TryParse(valid.RootElement, out _));
        foreach (var bad in new[] { Request.Replace("\"INITIAL\"", "\"FORECAST_INITIAL\""),
            Request.Replace("\"CASE.DATA\"", "\"../CASE.DATA\""), Request.Replace("\"STB/day\"", "\"sm3/day\""),
            Request.Replace("10000", "20000"), Request.Replace("10000", "-1"),
            Request.Replace("\"baselineRunId\":1", "\"baselineRunId\":1,\"status\":\"SHUT\""),
            Request.Replace("\"baselineRunId\":1", "\"baselineRunId\":1,\"baselineRunId\":1") })
        {
            using var doc = JsonDocument.Parse(bad);
            Assert.False(EclipseProductionTargetParameters.TryParse(doc.RootElement, out _));
        }
    }

    [Fact] public async Task MultiLineRecordChangesOnlyTargetTokenAndPreservesOpenPressureCommentsAndLaterDates()
    {
        var text = Deck + "WCONPROD\n 'P1' 'OPEN' 'ORAT' 30000 4* 2500 /\n/\n";
        var path = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(path, text);
        using var doc = JsonDocument.Parse(Request);
        EclipseProductionTargetParameters.TryParse(doc.RootElement, out var parameters);
        await EclipseProductionTargetEditor.ApplyAsync(new(path, root, [path]), parameters!, TestContext.Current.CancellationToken);
        Assert.Equal(text.Replace("'ORAT' 20000 4* 2500 / --", "'ORAT' 10000 4* 2500 / --"), await File.ReadAllTextAsync(path));
    }

    [Theory]
    [InlineData("original")]
    [InlineData("line")]
    [InlineData("shut")]
    [InlineData("duplicate")]
    [InlineData("template")]
    [InlineData("default")]
    [InlineData("include")]
    [InlineData("unit")]
    [InlineData("time")]
    public async Task AmbiguousOrStaleTargetsFailBeforeWriting(string kind)
    {
        var text = kind switch {
            "shut" => Deck.Replace("'P1' 'OPEN'", "'P1' 'SHUT'"),
            "duplicate" => Deck.Replace(" 'P2'", " 'P1'"),
            "template" => Deck.Replace(" 'P2'", " 'P*'"),
            "default" => Deck.Replace("'ORAT' 20000", "'ORAT' 1*"),
            "include" => Deck + "INCLUDE 'file.inc' /\n",
            "unit" => Deck.Replace("FIELD", "METRIC"),
            "time" => Deck.Replace("WCONPROD\n", "TSTEP\n1 /\nWCONPROD\n"),
            _ => Deck
        };
        var request = kind == "original" ? Request.Replace("20000", "19000") : kind == "line" ? Request.Replace("\"lineNumber\":6", "\"lineNumber\":5") : Request;
        var path = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(path, text);
        using var doc = JsonDocument.Parse(request);
        EclipseProductionTargetParameters.TryParse(doc.RootElement, out var parameters);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseProductionTargetEditor.ApplyAsync(new(path, root, [path]), parameters!, TestContext.Current.CancellationToken));
        Assert.Equal(text, await File.ReadAllTextAsync(path));
    }
}
