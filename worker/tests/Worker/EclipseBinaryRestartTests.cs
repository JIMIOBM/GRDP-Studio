using System.Buffers.Binary;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseBinaryRestartTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-binary-restart", Guid.NewGuid().ToString("N"));
    public EclipseBinaryRestartTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private string Write(byte[] bytes) { var path = Path.Combine(root, "CASE.UNRST"); File.WriteAllBytes(path, bytes); return path; }
    // Synthetic reports encode the existing independent semantic fixtures as native Fortran records.
    internal static byte[] Report(int number, int days, Action<int[]>? change = null, bool big = true, string? fault = null)
    {
        var text = EclipseFormattedRestartTests.Report(number, days, change);
        var matches = Regex.Matches(text, "(?m)^ '(?<key>.{8})' (?<count>\\d+) '(?<type>.{4})'\\r?$").ToArray();
        using var output = new MemoryStream();
        void Int(Stream stream, int value) { Span<byte> b = stackalloc byte[4]; if (big) BinaryPrimitives.WriteInt32BigEndian(b, value); else BinaryPrimitives.WriteInt32LittleEndian(b, value); stream.Write(b); }
        void Record(byte[] data) { Int(output, data.Length); output.Write(data); Int(output, data.Length); }
        for (var i = 0; i < matches.Length; i++)
        {
            var m = matches[i]; var key = m.Groups["key"].Value; var type = m.Groups["type"].Value;
            var values = text[(m.Index + m.Length)..(i + 1 < matches.Length ? matches[i + 1].Index : text.Length)].Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (fault == "missing" && key.Trim() == "PRESSURE") continue;
            if (fault == "lgr" && key.Trim() == "PRESSURE") key = "LGR     ";
            if (fault == "markers") key = key.Trim() switch { "STARTSOL" => "ENDSOL  ", "ENDSOL" => "STARTSOL", _ => key };
            using var header = new MemoryStream(); header.Write(Encoding.ASCII.GetBytes(key)); Int(header, values.Length); header.Write(Encoding.ASCII.GetBytes(type)); Record(header.ToArray());
            using var data = new MemoryStream();
            foreach (var value in values)
            {
                if (type == "INTE") Int(data, int.Parse(value, CultureInfo.InvariantCulture));
                else if (type == "LOGI") Int(data, value == "T" ? -1 : 0);
                else if (type == "REAL") Int(data, BitConverter.SingleToInt32Bits(fault == "nonfinite" ? float.NaN : float.Parse(value, CultureInfo.InvariantCulture)));
                else if (type == "DOUB") { var b = new byte[8]; var n = BitConverter.DoubleToInt64Bits(double.Parse(value.Replace('D', 'E'), CultureInfo.InvariantCulture)); if (big) BinaryPrimitives.WriteInt64BigEndian(b, n); else BinaryPrimitives.WriteInt64LittleEndian(b, n); data.Write(b); }
            }
            if (data.Length > 0) Record(data.ToArray());
        }
        return output.ToArray();
    }
    [Theory]
    [InlineData(true)] [InlineData(false)]
    public void BothByteOrdersKeepExactSelectedReportAndSource(bool big)
    {
        var first = Report(0, 0, big: big); var selected = Report(80, 2190, big: big); var original = first.Concat(selected).ToArray();
        var path = Write(original); var catalog = EclipseRestartReader.Read(path);
        Assert.Equal("eclipse-restart-catalog/2", catalog.SchemaVersion); Assert.Equal("eclipse-output-CASE.UNRST", catalog.ArtifactName);
        Assert.Equal("1970-01-01", catalog.StartDate); Assert.Equal(2, catalog.Reports.Count);
        Assert.Equal(first.Length, catalog.Reports[1].ByteOffset); Assert.Equal("1975-12-31", catalog.Reports[1].Date);
        Assert.Equal(selected, EclipseRestartReader.Select(path, catalog, 80)); Assert.Equal(original, File.ReadAllBytes(path));
        Assert.Throws<InvalidDataException>(() => EclipseRestartReader.Select(path, catalog, 81));
        Assert.Throws<InvalidDataException>(() => EclipseBinaryRestartReader.Select(path, catalog with { Reports = [catalog.Reports[1] with { TimeDays = 2191 }] }, 80));
        Assert.Throws<InvalidDataException>(() => EclipseBinaryRestartReader.Select(path, catalog with { SchemaVersion = "eclipse-restart-catalog/1" }, 80));
        File.WriteAllBytes(path, Report(80, 2191, big: big));
        Assert.Throws<InvalidDataException>(() => EclipseBinaryRestartReader.Select(path, catalog, 80));
    }
    [Theory]
    [InlineData("solver")] [InlineData("unit")] [InlineData("grid")] [InlineData("active")] [InlineData("phase")]
    [InlineData("hour")] [InlineData("minute")] [InlineData("date")] [InlineData("origin")] [InlineData("wells")] [InlineData("groups")]
    public void InvalidOrIncompleteGlobalMetadataRejects(string fault)
    {
        var bytes = Report(0, 0).Concat(Report(80, 2190, ih => {
            switch (fault) { case "solver": ih[94] = 300; break; case "unit": ih[2] = 3; break; case "grid": ih[8] = 4; break;
                case "active": ih[11] = 0; break; case "phase": ih[14] = 8; break; case "hour": ih[206] = 1; break; case "minute": ih[207] = 1; break;
                case "date": ih[65] = 13; break; case "origin": ih[64] = 30; break; case "wells": ih[16] = 1; ih[24] = 155; break; case "groups": ih[20] = 1; ih[36] = 112; break; }
        })).ToArray();
        Assert.Throws<InvalidDataException>(() => EclipseBinaryRestartReader.Read(Write(bytes)));
    }
    [Theory]
    [InlineData("missing")] [InlineData("lgr")] [InlineData("markers")] [InlineData("nonfinite")]
    [InlineData("duplicate")] [InlineData("order")] [InlineData("truncated")] [InlineData("footer")] [InlineData("count")] [InlineData("type")]
    public void DamagedBinaryRecordsNeverProduceCatalog(string fault)
    {
        var bytes = Report(0, 0).Concat(Report(80, 2190, fault: fault)).ToArray();
        if (fault == "duplicate") bytes = bytes.Concat(Report(80, 2190)).ToArray();
        if (fault == "order") bytes = Report(80, 2190).Concat(Report(0, 0)).ToArray();
        if (fault == "truncated") bytes = bytes[..^1];
        if (fault == "footer") bytes[20] ^= 1;
        if (fault == "count") BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(12, 4), int.MaxValue);
        if (fault == "type") bytes[16] = (byte)'Q';
        Assert.Throws<InvalidDataException>(() => EclipseBinaryRestartReader.Read(Write(bytes)));
    }
    [Fact]
    public void ActualNativeReportAndSummaryMatchIndependentBinaryProof()
    {
        var fixture = Path.Combine(AppContext.BaseDirectory, "Fixtures", "BINARY-RESTART");
        var catalog = EclipseBinaryRestartReader.Read(fixture + ".UNRST"); var report = Assert.Single(catalog.Reports);
        Assert.Equal(80, report.ReportNumber); Assert.Equal(2190, report.TimeDays); Assert.Equal(315032, report.SizeBytes);
        Assert.Equal("dc529b9d0ea8e144b757a5132ecdca2694e7917c4306bcf65a552fa997d08667", report.Sha256);
        Assert.Equal(File.ReadAllBytes(fixture + ".UNRST"), EclipseBinaryRestartReader.Select(fixture + ".UNRST", catalog, 80));
        Assert.Equal("1970-01-01", EclipseBinarySummaryParser.ReadStartDate(fixture + ".SMSPEC"));
        // This original native example has six anonymous WOPT slots. Reject merging them into one curve.
        Assert.Throws<InvalidDataException>(() => EclipseBinarySummaryParser.Parse(fixture + ".SMSPEC", fixture + ".UNSMRY"));
    }
    [Fact]
    public void EveryActualBinaryBusinessCurveValueAndTimeMatchesIndependentNativeOracle()
    {
        var fixture = Path.Combine(AppContext.BaseDirectory, "Fixtures", "BINARY-RESTART-BUSINESS");
        var expected = System.Text.Json.JsonSerializer.Deserialize<EclipseSummarySeries[]>(File.ReadAllText(fixture + ".json"),
            new System.Text.Json.JsonSerializerOptions { PropertyNameCaseInsensitive = true })!;
        var actual = EclipseBinarySummaryParser.Parse(fixture + ".SMSPEC", fixture + ".UNSMRY");
        Assert.Equal(88, actual.Count); Assert.Equal(expected.Length, actual.Count);
        for (var i = 0; i < expected.Length; i++) {
            Assert.Equal(expected[i].Keyword, actual[i].Keyword); Assert.Equal(expected[i].ObjectName, actual[i].ObjectName); Assert.Equal(expected[i].Unit, actual[i].Unit);
            Assert.Equal(expected[i].Points.ToArray(), actual[i].Points.ToArray());
        }
    }
}
