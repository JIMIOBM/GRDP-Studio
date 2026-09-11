using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseParsingTests
{
    [Theory]
    [InlineData("INCLUDE 'x'", true)]
    [InlineData("include\n", true)]
    [InlineData("-- INCLUDE 'x'", false)]
    [InlineData("'INCLUDE'", false)]
    [InlineData("\"INCLUDE\"", true)]
    [InlineData("'it''s INCLUDE'", false)]
    [InlineData("FOOINCLUDE INCLUDE_FILE", false)]
    [InlineData("1INCLUDE INCLUDE_1", false)]
    public void IncludeScannerHonorsCommentsQuotesAndAsciiBoundaries(string deck, bool expected)
    {
        Assert.Equal(expected, EclipseDeckScanner.ContainsInclude(new StringReader(deck)));
    }

    [Fact]
    public void IncludeScannerReadsIncrementallyWithoutReadToEnd()
    {
        Assert.True(EclipseDeckScanner.ContainsInclude(new ReadOnlyIncrementalReader("-- ignored\nINCLUDE 'model.inc'")));
    }

    [Fact]
    public void EclEndRequiresAllCounts()
    {
        Assert.True(EclipseParsers.TryParseEclEnd("Comments = 0 Warnings = 1 Problems = 0 Errors = 0 Bugs = 0", out var counts));
        Assert.Equal(0, counts!.Errors);
        Assert.False(EclipseParsers.TryParseEclEnd("Errors = 0 Problems = 0 Bugs = 0", out _));
    }

    [Fact]
    public void RsmKeepsHeaderSeriesSourceOrderAndSkipsMalformedValues()
    {
        var series = EclipseParsers.ParseRsm(Block(Row("TIME", "FOPR", "FWPR"), Row("DAYS", "STB/DAY", "STB/DAY"), null, Row("0", "2", "bad"), Row("1", "3", "4")));
        Assert.Collection(series,
            fopr => { Assert.Equal("STB/DAY", fopr.Unit); Assert.Equal(new[] { 2d, 3d }, fopr.Points.Select(point => point.Value)); },
            fwpr => Assert.Single(fwpr.Points));
    }

    [Fact]
    public void RsmDiscardsNonFiniteNegativeAndOutOfOrderPoints()
    {
        var series = Assert.Single(EclipseParsers.ParseRsm(Block(
            Row("TIME", "FOPR"), Row("DAYS", "STB/DAY"), null,
            Row("0", "1"), Row("NaN", "2"), Row("-1", "3"), Row("1", "NaN"),
            Row("2", "Infinity"), Row("2", "4"), Row("1.5", "5"), Row("3", "6"))));

        Assert.Equal([0d, 2d, 3d], series.Points.Select(point => point.TimeDays));
        Assert.Equal([1d, 4d, 6d], series.Points.Select(point => point.Value));
        Assert.True(EclipseParsers.IsPublishableSummary([series]));
    }

    [Fact]
    public void RsmWithoutUsableSeriesPublishesNullSummary()
    {
        var series = EclipseParsers.ParseRsm(Block(
            Row("TIME", "FOPR"), Row("DAYS", "STB/DAY"), null,
            Row("NaN", "1"), Row("-1", "2"), Row("1", "Infinity")));

        Assert.Single(series);
        Assert.Empty(series[0].Points);
        Assert.False(EclipseParsers.IsPublishableSummary(series));
        Assert.False(EclipseParsers.IsPublishableSummary([]));
        Assert.Null(EclipseRunService.CreateSummary(series));
        Assert.Null(EclipseRunService.CreateSummary([]));
    }

    [Fact]
    public void InvalidParsedSummaryIsNotPublishable()
    {
        var invalid = new EclipseSummarySeries("FOPR", null, null, [new(double.NaN, 1)]);

        Assert.False(EclipseParsers.IsPublishableSummary([invalid]));
        Assert.Null(EclipseRunService.CreateSummary([invalid]));
    }

    [Fact]
    public void RsmParityPreservesTimeYearsQualifiersOrderAndFinalBlockEmptyHeaders()
    {
        var rsm = Block(Row("TIME", "YEARS", "FOPR", "WGPR", "WGPR"), Row("DAYS", "YEARS", "STB/DAY", "SM3/DAY", "SM3/DAY"), Row("", "", "", "PRODUCER", "WWPR"), Row("0", "0", "100", "1", "2"), Row("1", "0.002738", "bad", "3", "4"))
            + Block(Row("TIME", "YEARS", "FOPR", "FWPR"), Row("DAYS", "YEARS", "STB/DAY", "STB/DAY"), null, Row("2", "0.005476", "80", "9"))
            + Block(Row("TIME", "YEARS", "FPR", "FWPR"), Row("DAYS", "YEARS", "PSIA", "STB/DAY"), null);

        var series = EclipseParsers.ParseRsm(rsm);

        Assert.Collection(series,
            fopr => { Assert.Equal("FOPR", fopr.Keyword); Assert.Equal(new[] { 0d, 2d }, fopr.Points.Select(point => point.TimeDays)); },
            wgpr => { Assert.Equal("PRODUCER", wgpr.ObjectName); Assert.Equal(1d, wgpr.Points[1].TimeDays); },
            wgpr => Assert.Equal("WWPR", wgpr.ObjectName),
            fwpr => Assert.Single(fwpr.Points),
            fpr => Assert.Empty(fpr.Points));
        Assert.DoesNotContain(series, item => item.Keyword == "YEARS");
    }

    [Theory]
    [InlineData("FOPR", "C:/private/license", null)]
    [InlineData("FOPR", "STB/DAY", "private-server")]
    [InlineData("LICENSE", "STB/DAY", null)]
    public void RsmRejectsUnsafeHeaderStringsBeforeTheyCanBePublished(string keyword, string unit, string? qualifier)
    {
        var rsm = Block(Row("TIME", keyword), Row("DAYS", unit), qualifier is null ? null : Row("", qualifier), Row("0", "2"));

        Assert.Empty(EclipseParsers.ParseRsm(rsm));
    }

    [Fact]
    public async Task UnreadableOrUnparseableFreshRsmIsOptional()
    {
        var unreadable = await EclipseRunService.ReadRsmAsync(Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N") + ".RSM"));
        var path = Path.GetTempFileName();
        try
        {
            await File.WriteAllTextAsync(path, "SUMMARY OF RUN CASE", TestContext.Current.CancellationToken);
            var unparseable = await EclipseRunService.ReadRsmAsync(path, _ => throw new InvalidDataException());

            Assert.Null(unreadable);
            Assert.Null(unparseable);
        }
        finally
        {
            File.Delete(path);
        }
    }

    private const int Width = 14;
    private static string Block(string keywords, string units, string? qualifiers, params string[] rows) =>
        "SUMMARY OF RUN CASE\n" + new string('-', 80) + "\n" + keywords + "\n" + units + "\n" + (qualifiers is null ? string.Empty : qualifiers + "\n") + "\n" + new string('-', 80) + "\n" + string.Join("\n", rows) + "\n";
    private static string Row(params string[] values) => string.Concat(values.Select(value => value.PadRight(Width)));

    [Fact]
    public void CompletionRulesRejectStaleOutputsCountsAndDiagnostics()
    {
        var stamp = DateTime.UtcNow;
        Assert.False(EclipseRunRules.IsFresh((3, stamp), 3, stamp));
        Assert.True(EclipseRunRules.IsFresh((3, stamp), 4, stamp));
        Assert.True(EclipseRunRules.IsSupportedVersion("2023.1\n2024.1\n"));
        Assert.False(EclipseRunRules.IsSupportedVersion("2024.10"));
        Assert.True(EclipseRunRules.HasLicenseDiagnostic("LICENSE checkout failed"));
        Assert.True(EclipseRunRules.HasFatalDiagnostic("fatal error"));
    }

    [Theory]
    [InlineData(AdapterStopReason.Cancelled, true, true)]
    [InlineData(AdapterStopReason.TimedOut, false, true)]
    [InlineData(AdapterStopReason.None, false, true)]
    public void CancellationAndTimeoutHaveFrozenCleanupOrder(AdapterStopReason reason, bool kill, bool check)
    {
        Assert.Equal(kill, EclipseRunRules.RequiresKill(reason));
        Assert.Equal(check, EclipseRunRules.RequiresCheck(reason));
    }

    private sealed class ReadOnlyIncrementalReader(string value) : StringReader(value)
    {
        public override string ReadToEnd() => throw new InvalidOperationException("Scanner must not buffer the complete deck.");
    }
}
