using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseMultiWellScheduleTests
{
    private const string Request = """
        {"schemaVersion":"eclipse-schedule-parameters/6","baselineRunId":74,"wells":["G1","G2"],
        "events":[{"date":"1970-01-15","status":"SHUT"},{"date":"1970-02-01","status":"OPEN"}]}
        """;
    private const string Schedule = """
        RUNSPEC
        FIELD
        OIL
        DIMENS
        1 1 1 /
        SCHEDULE
        WELSPECS
         'G1' 'GROUP' 1 1 1* 'OIL' /
         'G2' 'GROUP' 1 1 1* 'OIL' /
         'G3' 'GROUP' 1 1 1* 'OIL' /
        /
        DATES
        15 'JAN' 1970 /
        /
        WCONHIST
         'G1' 'OPEN' 'ORAT' 35.686 /
         'G2' 'OPEN' 'ORAT' 749.610 /
        /
        DATES
        1 'FEB' 1970 /
        /
        WCONHIST
         'G1' 'OPEN' 'ORAT' 64.598 /
         'G2' 'OPEN' 'ORAT' 849.610 /
        /
        END
        """;

    [Fact]
    public void ParsesTheWholeGroupWithoutReplacingItWithTheFirstWell()
    {
        using var json = JsonDocument.Parse(Request);
        Assert.True(EclipseScheduleParameters.TryParse(json.RootElement, out var value));
        Assert.Equal(["G1", "G2"], value!.Wells);
        Assert.Equal(2, value.Events!.Count);
    }

    [Theory]
    [InlineData("[\"G1\"]")]
    [InlineData("[\"G1\",\"G1\"]")]
    [InlineData("[\"G1\",\"*\"]")]
    [InlineData("[\"G1\",2]")]
    [InlineData("[\"G1\",\"G2\",\"G3\",\"G4\",\"G5\",\"G6\",\"G7\",\"G8\",\"G9\"]")]
    public void RejectsInvalidGroups(string wells)
    {
        using var json = JsonDocument.Parse(Request.Replace("[\"G1\",\"G2\"]", wells));
        Assert.False(EclipseScheduleParameters.TryParse(json.RootElement, out _));
    }

    [Fact]
    public async Task AppendsBothWellsAfterExistingControlsAndPreservesOtherRows()
    {
        await WithPackage(async (file, package) =>
        {
            using var json = JsonDocument.Parse(Request);
            Assert.True(EclipseScheduleParameters.TryParse(json.RootElement, out var value));
            await EclipseScheduleEditor.ApplyAsync(package, value!, TestContext.Current.CancellationToken);
            var updated = await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken);
            foreach (var well in new[] { "G1", "G2" })
            {
                Assert.Contains($"'{well}' 'SHUT' /", updated);
                Assert.Contains($"'{well}' 'OPEN' /", updated);
                Assert.True(updated.IndexOf("749.610", StringComparison.Ordinal) < updated.IndexOf($"'{well}' 'SHUT' /", StringComparison.Ordinal));
                Assert.True(updated.IndexOf("849.610", StringComparison.Ordinal) < updated.IndexOf($"'{well}' 'OPEN' /", StringComparison.Ordinal));
            }
            Assert.Contains("'G3' 'GROUP'", updated);
            Assert.DoesNotContain("'G3' 'SHUT'", updated);
            Assert.DoesNotContain("0 0 0", updated);
        });
    }

    [Theory]
    [InlineData("G9")]
    [InlineData("G3")]
    public async Task UnknownOrNonProducingMemberRejectsTheEntireGroupBeforeWriting(string secondWell)
    {
        await WithPackage(async (file, package) =>
        {
            using var json = JsonDocument.Parse(Request.Replace("\"G2\"", "\"" + secondWell + "\""));
            Assert.True(EclipseScheduleParameters.TryParse(json.RootElement, out var value));
            await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseScheduleEditor.ApplyAsync(package, value!, TestContext.Current.CancellationToken));
            Assert.Equal(Schedule, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
        });
    }

    [Theory]
    [InlineData("missing")]
    [InlineData("duplicate")]
    [InlineData("reversed")]
    public async Task InvalidDateBlocksRejectAtomically(string fault)
    {
        var input = fault switch
        {
            "missing" => Schedule.Replace("1 'FEB' 1970", "1 'MAR' 1970"),
            "duplicate" => Schedule.Replace("END", "DATES\n15 'JAN' 1970 /\n/\nEND"),
            _ => Schedule.Replace("15 'JAN' 1970", "1 'FEB' 1970").Replace("1 'FEB' 1970 /\n/\nWCONHIST\n 'G1' 'OPEN' 'ORAT' 64.598", "15 'JAN' 1970 /\n/\nWCONHIST\n 'G1' 'OPEN' 'ORAT' 64.598")
        };
        await WithPackage(async (file, package) =>
        {
            using var json = JsonDocument.Parse(Request);
            Assert.True(EclipseScheduleParameters.TryParse(json.RootElement, out var value));
            await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseScheduleEditor.ApplyAsync(package, value!, TestContext.Current.CancellationToken));
            Assert.Equal(input, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
        }, input);
    }

    private static async Task WithPackage(Func<string, EclipseDeckPackage, Task> action, string input = Schedule)
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-multiwell-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var file = Path.Combine(root, "CASE.DATA");
            await File.WriteAllTextAsync(file, input, TestContext.Current.CancellationToken);
            await action(file, new(file, root, [file]));
        }
        finally { Directory.Delete(root, recursive: true); }
    }
}
