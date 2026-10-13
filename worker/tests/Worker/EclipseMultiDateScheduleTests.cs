using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseMultiDateScheduleTests
{
    private const string Request = """
        {"schemaVersion":"eclipse-schedule-parameters/7","baselineRunId":74,"wells":["G1","G2"],
        "events":[{"date":"1970-01-15","status":"SHUT"},{"date":"1970-02-01","status":"OPEN"},{"date":"1970-03-01","status":"SHUT"}]}
        """;
    private const string Deck = """
        RUNSPEC
        FIELD
        DIMENS
        1 1 1 /
        SCHEDULE
        WELSPECS
         'G1' 'FIELD' 1 1 1* 'OIL' /
         'G2' 'FIELD' 1 1 1* 'OIL' /
        /
        DATES
        15 'JAN' 1970 /
        /
        WCONHIST
         'G1' 'OPEN' 'ORAT' 10 /
         'G2' 'OPEN' 'ORAT' 20 /
        /
        DATES
        1 'FEB' 1970 /
        /
        WCONHIST
         'G1' 'OPEN' 'ORAT' 30 /
         'G2' 'OPEN' 'ORAT' 40 /
        /
        DATES
        1 'MAR' 1970 /
        /
        WCONHIST
         'G1' 'OPEN' 'ORAT' 50 /
         'G2' 'OPEN' 'ORAT' 60 /
        /
        END
        """;

    [Fact]
    public void ThreeEventsAndSingleWellAreAcceptedWithoutRelaxingOldContracts()
    {
        using var json = JsonDocument.Parse(Request);
        Assert.True(EclipseScheduleParameters.TryParse(json.RootElement, out var parsed));
        Assert.Equal(3, parsed!.Events!.Count);
        using var single = JsonDocument.Parse(Request.Replace("[\"G1\",\"G2\"]", "[\"G1\"]"));
        Assert.True(EclipseScheduleParameters.TryParse(single.RootElement, out _));
        using var old = JsonDocument.Parse(Request.Replace("parameters/7", "parameters/6"));
        Assert.False(EclipseScheduleParameters.TryParse(old.RootElement, out _));
    }

    [Theory]
    [InlineData("1970-03-01", "1970-02-01")]
    [InlineData("1970-03-01", "1970-01-01")]
    [InlineData("SHUT", "AUTO")]
    [InlineData("\"G2\"", "\"G1\"")]
    public void InvalidPlansAreRejected(string from, string to)
    {
        using var json = JsonDocument.Parse(Request.Replace(from, to));
        Assert.False(EclipseScheduleParameters.TryParse(json.RootElement, out _));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AllEventsAreAppliedOrNothingIsWritten(bool missingThirdDate)
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-multidate", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var file = Path.Combine(root, "CASE.DATA");
            var original = missingThirdDate ? Deck.Replace("1 'MAR' 1970", "1 'APR' 1970") : Deck;
            await File.WriteAllTextAsync(file, original, TestContext.Current.CancellationToken);
            using var json = JsonDocument.Parse(Request);
            Assert.True(EclipseScheduleParameters.TryParse(json.RootElement, out var parsed));
            var package = new EclipseDeckPackage(file, root, [file]);
            if (missingThirdDate)
            {
                await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseScheduleEditor.ApplyAsync(package, parsed!, TestContext.Current.CancellationToken));
                Assert.Equal(original, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
                return;
            }
            var audit = await EclipseScheduleEditor.ApplyAsync(package, parsed!, TestContext.Current.CancellationToken);
            var updated = await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken);
            Assert.Contains("SHUT at 1970-03-01", audit);
            foreach (var pair in new[] { ("20 /", "SHUT"), ("40 /", "OPEN"), ("60 /", "SHUT") })
            {
                // Git checkout can use CRLF on Windows; verify the same records under either newline convention.
                var section = updated[(updated.IndexOf(pair.Item1, StringComparison.Ordinal) + pair.Item1.Length)..].Replace("\r\n", "\n");
                Assert.StartsWith("\n/\n\n-- GRDP-Studio", section);
                Assert.Contains($"'G1' '{pair.Item2}' /\n       'G2' '{pair.Item2}' /", section);
            }
            Assert.Equal(3, updated.Split("WELOPEN").Length - 1);
        }
        finally { Directory.Delete(root, true); }
    }
}
