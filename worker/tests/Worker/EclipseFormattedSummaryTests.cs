using System.Security.Cryptography;
using System.Text;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseFormattedSummaryTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-formatted-summary", Guid.NewGuid().ToString("N"));
    public EclipseFormattedSummaryTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);

    [Fact]
    public void ReadsOfficialCardsWithRealPrecisionNamesAndBlankUnits()
    {
        var spec = Spec(["TIME", "WOPR", "WOPR", "WWCT"], ["FIELD", "G1", "P1:01", "G1"], ["  DAYS", "STB/DAY", "STB/DAY", ""]);
        var data = Block("SEQHDR", "INTE", "-714438032") + Block("MINISTEP", "INTE", "0") +
            Block("PARAMS", "REAL", "0", "0", "0", "0") + Block("PARAMS", "REAL", "14", "0.35686001E+02", "0.74960999E+03", "0.25");
        var series = EclipseFormattedSummaryParser.Parse(Write("CASE.FSMSPEC", spec), Write("CASE.FUNSMRY", data));
        Assert.Equal(3, series.Count);
        Assert.Equal("P1:01", series[1].ObjectName);
        Assert.Equal("STB/DAY", series[0].Unit);
        Assert.Equal((double)(float)35.686001, series[0].Points[1].Value);
        Assert.Equal(new[] { 0d, 14d }, series[0].Points.Select(point => point.TimeDays));
        Assert.Null(series[2].Unit);
        Assert.True(EclipseParsers.IsPublishableSummary(series));
    }

    [Fact]
    public void ReadsDoubleAndFortranExponentFormsWithoutGuessingTime()
    {
        var spec = ValidSpec();
        var data = Block("PARAMS", "DOUB", "0.10000000000000D+01", ".39684106+004") +
            Block("PARAMS", "DOUB", "2", "1.23456789012345D+01");
        var series = Assert.Single(EclipseFormattedSummaryParser.Parse(Write("CASE.FSMSPEC", spec), Write("CASE.FUNSMRY", data)));
        Assert.Equal(3968.4106, series.Points[0].Value);
        Assert.Equal(12.3456789012345, series.Points[1].Value);
    }

    [Fact]
    public void ReadsActualMidnightSimulationOriginWithoutRebasingSummaryTimes()
    {
        var path = Write("CASE.FSMSPEC", ValidSpec() + Block("STARTDAT", "INTE", "1", "1", "1970", "0", "0", "0"));
        Assert.Equal("1970-01-01", EclipseFormattedSummaryParser.ReadStartDate(path));
    }

    [Theory]
    [InlineData("missing")]
    [InlineData("duplicate")]
    [InlineData("clock")]
    [InlineData("invalid")]
    [InlineData("wrongType")]
    public void AmbiguousOrNonMidnightSummaryOriginCannotQualifyForecast(string fault)
    {
        var block = Block("STARTDAT", "INTE", "1", "1", "1970", "0", "0", "0");
        var text = fault switch { "missing" => ValidSpec(), "duplicate" => ValidSpec() + block + block,
            "clock" => ValidSpec() + Block("STARTDAT", "INTE", "1", "1", "1970", "1", "0", "0"),
            "invalid" => ValidSpec() + Block("STARTDAT", "INTE", "31", "2", "1970", "0", "0", "0"),
            _ => ValidSpec() + Block("STARTDAT", "DOUB", "1", "1", "1970", "0", "0", "0") };
        Assert.Throws<InvalidDataException>(() => EclipseFormattedSummaryParser.ReadStartDate(Write("CASE.FSMSPEC", text)));
    }

    [Theory]
    [InlineData("missing-units")]
    [InlineData("wrong-time-unit")]
    [InlineData("years-only")]
    [InlineData("duplicate-time-axis")]
    [InlineData("duplicate-series")]
    [InlineData("spatial-number")]
    [InlineData("wrong-column-count")]
    [InlineData("truncated-values")]
    [InlineData("extra-values")]
    [InlineData("unknown-type")]
    [InlineData("nonfinite")]
    [InlineData("overflow-real")]
    [InlineData("reverse-time")]
    [InlineData("duplicate-time")]
    [InlineData("overlong-card")]
    [InlineData("invalid-character-width")]
    [InlineData("missing-params")]
    public void BadOrAmbiguousFormatsFailInsteadOfPublishingPartialCurves(string error)
    {
        var spec = ValidSpec();
        var data = Block("PARAMS", "REAL", "1", "12.5");
        switch (error)
        {
            case "missing-units": spec = Block("KEYWORDS", "CHAR", "TIME", "WOPR") + Block("WGNAMES", "CHAR", "FIELD", "G1"); break;
            case "wrong-time-unit": spec = Spec(["TIME", "WOPR"], ["FIELD", "G1"], ["YEARS", "STB/DAY"]); break;
            case "years-only": spec = Spec(["YEARS", "WOPR"], ["FIELD", "G1"], ["YEARS", "STB/DAY"]); break;
            case "duplicate-time-axis": spec = Spec(["TIME", "DAYS"], ["FIELD", "FIELD"], ["DAYS", "DAYS"]); break;
            case "duplicate-series": spec = Spec(["TIME", "WOPR", "WOPR"], ["FIELD", "G1", "G1"], ["DAYS", "STB/DAY", "STB/DAY"]); break;
            case "spatial-number": spec += Block("NUMS", "INTE", "0", "42"); break;
            case "wrong-column-count": data = Block("PARAMS", "REAL", "1", "12.5", "2"); break;
            case "truncated-values": data = " 'PARAMS  '           2 'REAL'\n 1\n"; break;
            case "extra-values": data = " 'PARAMS  '           1 'REAL'\n 1 2\n"; break;
            case "unknown-type": data = " 'PARAMS  '           2 'C008'\n 1 2\n"; break;
            case "nonfinite": data = Block("PARAMS", "REAL", "1", "NaN"); break;
            case "overflow-real": data = Block("PARAMS", "REAL", "1", "1E+100"); break;
            case "reverse-time": data += Block("PARAMS", "REAL", "0", "2"); break;
            case "duplicate-time": data += Block("PARAMS", "REAL", "1", "2"); break;
            case "overlong-card": data += new string(' ', 81) + "\n"; break;
            case "invalid-character-width": spec = spec.Replace("'G1      '", "'G1'"); break;
            case "missing-params": data = Block("MINISTEP", "INTE", "0"); break;
        }
        Assert.Throws<InvalidDataException>(() => EclipseFormattedSummaryParser.Parse(Write("CASE.FSMSPEC", spec), Write("CASE.FUNSMRY", data)));
    }

    [Fact]
    public async Task ExistingSummaryBranchCanReadFormattedOutputWhenBinaryAndRsmAreAbsent()
    {
        var spec = Write("CASE.FSMSPEC", ValidSpec());
        var data = Write("CASE.FUNSMRY", Block("PARAMS", "REAL", "1", "12.5"));
        var result = await EclipseRunService.ReadSummaryAsync(null, null, null, formattedSmspec: spec, formattedUnsmry: data);
        Assert.Equal(12.5, Assert.Single(result!).Points[0].Value);
    }

    [Fact]
    public async Task RejectsBadFormattedOutputWithoutAFalseSuccessCurve()
    {
        var result = await EclipseRunService.ReadSummaryAsync(null, null, null,
            formattedSmspec: Write("CASE.FSMSPEC", ValidSpec()), formattedUnsmry: Write("CASE.FUNSMRY", "broken"));
        Assert.Null(result);
    }

    [Fact]
    public async Task PublishesOnlyNumericFormattedSummaryBytesWithTheOriginalChecksum()
    {
        var output = Path.Combine(root, "jobs", "1", "output");
        Directory.CreateDirectory(output);
        var source = Write("CASE.FUNSMRY", Block("PARAMS", "REAL", "1", "12.5"));
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));
        var descriptor = await store.CopyEclipseFormattedSummaryOutputAsync(output, source, TestContext.Current.CancellationToken);
        var bytes = File.ReadAllBytes(source);
        Assert.Equal("jobs/1/output/eclipse-output-CASE.FUNSMRY", descriptor.StorageKey);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(bytes)), descriptor.Sha256);
        Assert.Equal(bytes, File.ReadAllBytes(Path.Combine(output, "eclipse-output-CASE.FUNSMRY")));
        Assert.Equal("application/octet-stream", descriptor.ContentType);
    }

    [Theory]
    [InlineData("CASE.FSMSPEC", " 'RESTART '           1 'CHAR'\n 'secret  '\n")]
    [InlineData("CASE.FUNSMRY", " 'RESTART '           1 'CHAR'\n 'secret  '\n")]
    [InlineData("CASE.FUNSMRY", " 'OTHER   '           1 'REAL'\n 1\n")]
    public async Task SensitiveOrUnknownFormattedBlocksNeverBecomeDownloadable(string name, string content)
    {
        var output = Path.Combine(root, "jobs", "1", "output");
        Directory.CreateDirectory(output);
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));
        await Assert.ThrowsAsync<InvalidDataException>(() => store.CopyEclipseFormattedSummaryOutputAsync(output, Write(name, content), TestContext.Current.CancellationToken));
        Assert.Empty(Directory.EnumerateFiles(output));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(67108865)]
    public void EmptyOrOversizedFilesAreRejectedBeforeReading(long length)
    {
        var data = Path.Combine(root, "CASE.FUNSMRY");
        using (var stream = File.Create(data)) stream.SetLength(length);
        Assert.Throws<InvalidDataException>(() => EclipseFormattedSummaryParser.Parse(Write("CASE.FSMSPEC", ValidSpec()), data));
    }

    [Fact]
    public void OversizedBlockCountsAreRejectedBeforeAllocatingValues()
    {
        Assert.Throws<InvalidDataException>(() => EclipseFormattedSummaryParser.Parse(Write("CASE.FSMSPEC", ValidSpec()),
            Write("CASE.FUNSMRY", " 'PARAMS  '       65537 'REAL'\n")));
    }

    private string Write(string name, string text)
    {
        var path = Path.Combine(root, name);
        File.WriteAllText(path, text, Encoding.ASCII);
        return path;
    }
    private static string ValidSpec() => Spec(["TIME", "WOPR"], ["FIELD", "G1"], ["DAYS", "STB/DAY"]);
    private static string Spec(string[] keywords, string[] names, string[] units) =>
        Block("KEYWORDS", "CHAR", keywords) + Block("WGNAMES", "CHAR", names) + Block("UNITS", "CHAR", units);
    private static string Block(string keyword, string type, params string[] values)
    {
        var header = $" '{keyword.PadRight(8)}' {values.Length,11} '{type}'\n";
        var perLine = type == "CHAR" ? 7 : type == "DOUB" ? 3 : type == "REAL" ? 4 : 6;
        return header + string.Join("", values.Chunk(perLine).Select(row => string.Join(" ", row.Select(value =>
            type == "CHAR" ? "'" + value.PadRight(8) + "'" : value)) + "\n"));
    }
}
