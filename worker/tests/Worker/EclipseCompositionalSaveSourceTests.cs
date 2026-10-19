using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalSaveSourceTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-save-source", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalSaveSourceTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseDeckPackage Package()
    {
        var path = Path.Combine(root, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), path);
        return new EclipseDeckPackageResolver().Resolve(path, root);
    }

    [Fact]
    public void OfficialCheckpointUsesReportTenAtNineYearsAndPreservesOriginalFutureReportClock()
    {
        var s = EclipseCompositionalSaveSourceReader.Read(Package()); Assert.NotNull(s);
        Assert.Equal("1990-01-01", s.StartDate); Assert.Equal(10, s.ReportNumber); Assert.Equal(3285, s.TimeDays);
        Assert.Equal(5475, s.EndTimeDays); Assert.Equal(20, s.FutureReportTimes.Count);
        Assert.Equal(3321.5, s.FutureReportTimes[0]); Assert.Equal(5475, s.FutureReportTimes[^1]);
        Assert.Contains("SEPCOND", s.FutureSchedule); Assert.Contains("I SHUT/", s.FutureSchedule);
        Assert.Contains("MSUMNEWT", s.SummaryText); Assert.DoesNotContain("WELSPECS", s.FutureSchedule);
    }

    [Theory]
    [InlineData(false, "COMPOSITIONAL-SAVE-ORIGINAL.DATA")]
    [InlineData(true, "COMPOSITIONAL-SAVE-LOWER-GAS.DATA")]
    public void GeneratedForecastMatchesIndependentlyExecutedOfficialSolverInputByteForByte(bool change, string fixture)
    {
        var package = Package(); var before = File.ReadAllBytes(package.MainFile);
        var s = EclipseCompositionalSaveSourceReader.Read(package); Assert.NotNull(s);
        var actual = EclipseCompositionalSaveSourceReader.BuildForecast(s, change ? 4500 : null);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", fixture)), actual);
        Assert.Equal(before, File.ReadAllBytes(package.MainFile));
    }

    [Theory]
    [InlineData("suppressed")] [InlineData("multiple")] [InlineData("formatted")] [InlineData("unified")]
    [InlineData("parallel")] [InlineData("no-save")] [InlineData("duplicate-save")] [InlineData("fractional-checkpoint")]
    [InlineData("no-future")] [InlineData("wrong-save-item")]
    public void UnsupportedHistoryStructuresDoNotAdvertiseFastRestart(string fault)
    {
        var p = Package(); var text = File.ReadAllText(p.MainFile);
        var modified = fault switch {
            "suppressed" => text.Replace("MULTSAVE\r\n0 /", "MULTSAVE\r\n-1 /", StringComparison.Ordinal),
            "multiple" => text.Replace("MULTSAVE\r\n0 /", "MULTSAVE\r\n1 /", StringComparison.Ordinal),
            "formatted" => text.Replace("MULTSAVE", "FMTSAVE\r\nMULTSAVE", StringComparison.Ordinal),
            "unified" => text.Replace("MULTSAVE", "UNIFSAVE\r\nMULTSAVE", StringComparison.Ordinal),
            "parallel" => text.Replace("MULTSAVE", "PARALLEL\r\n2 /\r\nMULTSAVE", StringComparison.Ordinal),
            "no-save" => text.Replace("\r\nSAVE\r\n", "\r\n--SAVE\r\n", StringComparison.Ordinal),
            "duplicate-save" => text.Replace("\r\nSAVE\r\n", "\r\nSAVE\r\nSAVE\r\n", StringComparison.Ordinal),
            "fractional-checkpoint" => text.Replace("10*328.5", "9*328.5", StringComparison.Ordinal),
            "no-future" => text.Replace("10*36.5", "0*36.5", StringComparison.Ordinal),
            _ => text.Replace("\r\nSAVE\r\n", "\r\nSAVE\r\n42 /\r\n", StringComparison.Ordinal)
        };
        Assert.NotEqual(text, modified); File.WriteAllText(p.MainFile, modified);
        Assert.Null(EclipseCompositionalSaveSourceReader.Read(p));
    }

    [Theory]
    [InlineData(0)] [InlineData(-1)] [InlineData(100000001)] [InlineData(double.NaN)] [InlineData(double.PositiveInfinity)]
    public void InvalidForecastTargetsCannotProduceNativeInputs(double rate)
    {
        var s = EclipseCompositionalSaveSourceReader.Read(Package()); Assert.NotNull(s);
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveSourceReader.BuildForecast(s, rate));
    }

    [Fact]
    public void ExplicitStartDateOverridesOnlyTheDocumentedDefaultWithoutRebasingElapsedHistory()
    {
        var package = Package(); var text = File.ReadAllText(package.MainFile);
        File.WriteAllText(package.MainFile, text.Replace("\r\nFIELD\r\n", "\r\nSTART\r\n1 JAN 2000 /\r\nFIELD\r\n", StringComparison.Ordinal));
        var s = EclipseCompositionalSaveSourceReader.Read(package); Assert.NotNull(s);
        Assert.Equal("2000-01-01", s.StartDate); Assert.Equal(3285, s.TimeDays); Assert.Equal(5475, s.EndTimeDays);
    }
}
