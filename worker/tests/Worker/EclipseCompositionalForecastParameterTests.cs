using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalForecastParameterTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-forecast-parameters", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalForecastParameterTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseCompositionalSaveSourceReader.Snapshot Source()
    {
        var source = Path.Combine(root, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), source);
        return EclipseCompositionalSaveSourceReader.Read(new EclipseDeckPackageResolver().Resolve(source, root))!;
    }
    private static JsonObject Parameters(EclipseCompositionalSaveSourceReader.Snapshot s) => new() {
        ["schemaVersion"] = EclipseCompositionalForecastParameters.Schema, ["historyRunId"] = 1,
        ["sourceFile"] = s.Production.SourceFile, ["sourceSha256"] = s.Production.SourceSha256,
        ["saveCatalogSha256"] = new string('a', 64), ["saveReport"] = 10, ["targetGasRate"] = null
    };
    [Fact]
    public void InheritedAndChangedFutureControlsShareOneBoundCheckpointWithoutRebasingHistory()
    {
        var s = Source(); var p = Parameters(s);
        Assert.True(EclipseCompositionalForecastParameters.TryParse(JsonSerializer.SerializeToElement(p), out var original));
        Assert.NotNull(original); Assert.Null(original.TargetGasRate); Assert.True(original.MatchesSource(s));
        p["targetGasRate"] = 4500;
        Assert.True(EclipseCompositionalForecastParameters.TryParse(JsonSerializer.SerializeToElement(p), out var changed));
        Assert.NotNull(changed); Assert.Equal(4500, changed.TargetGasRate); Assert.True(changed.MatchesSource(s));
        p["targetGasRate"] = 6200;
        Assert.True(EclipseCompositionalForecastParameters.TryParse(JsonSerializer.SerializeToElement(p), out var same));
        Assert.NotNull(same); Assert.False(same.MatchesSource(s));
    }
    [Theory]
    [InlineData("history")] [InlineData("source")] [InlineData("hash")] [InlineData("catalog")]
    [InlineData("report")] [InlineData("rate-string")] [InlineData("zero")] [InlineData("negative")]
    [InlineData("high")] [InlineData("extra")] [InlineData("missing")] [InlineData("schema")]
    public void UntypedOrUnboundedRestartRequestsDoNotParse(string fault)
    {
        var p = Parameters(Source());
        switch (fault) {
            case "history": p["historyRunId"] = "1"; break; case "source": p["sourceFile"] = "../SPE3_GASCOND.DATA"; break;
            case "hash": p["sourceSha256"] = "bad"; break; case "catalog": p["saveCatalogSha256"] = "bad"; break;
            case "report": p["saveReport"] = 0; break; case "rate-string": p["targetGasRate"] = "4500"; break;
            case "zero": p["targetGasRate"] = 0; break; case "negative": p["targetGasRate"] = -1; break;
            case "high": p["targetGasRate"] = 100000001; break; case "extra": p["restartArtifactName"] = "BASE.UNRST"; break;
            case "missing": p.Remove("targetGasRate"); break; default: p["schemaVersion"] = "eclipse-history-forecast-parameters/3"; break;
        }
        Assert.False(EclipseCompositionalForecastParameters.TryParse(JsonSerializer.SerializeToElement(p), out _));
    }
    [Fact]
    public void OtherwiseTypedForeignSourceAndReportDoNotMatchThisHistory()
    {
        var s = Source();
        foreach (var field in new[] { "sourceFile", "sourceSha256", "saveReport" })
        {
            var p = Parameters(s);
            if (field == "sourceFile") p[field] = "OTHER.DATA";
            else if (field == "sourceSha256") p[field] = new string('0', 64);
            else p[field] = 11;
            Assert.True(EclipseCompositionalForecastParameters.TryParse(JsonSerializer.SerializeToElement(p), out var value));
            Assert.NotNull(value); Assert.False(value.MatchesSource(s));
        }
    }
}
