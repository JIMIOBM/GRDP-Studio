using System.Buffers.Binary;
using System.Text;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseBinarySummaryTests
{
    [Fact]
    public void ParsesSmspecAndUnsmryIntoTheExistingSummaryContract()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-binary-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var smspec = Path.Combine(root, "CASE.SMSPEC");
            var unsmry = Path.Combine(root, "CASE.UNSMRY");
            File.WriteAllBytes(smspec, Section("FILEHEAD", Array.Empty<byte>())
                .Concat(Section("KEYWORDS", Strings("TIME", "FOPR")))
                .Concat(Section("WGNAMES", Strings(":+:+:+:+", "FIELD")))
                .Concat(Section("UNITS", Strings("  DAYS", "STB/DAY"))).ToArray());
            File.WriteAllBytes(unsmry, Section("SEQHDR", Ints(1))
                .Concat(Section("PARAMS", Floats(0, 12.5f)))
                .Concat(Section("MINISTEP", Ints(1, 1)))
                .Concat(Section("PARAMS", Floats(1, 11.25f))).ToArray());

            var result = EclipseBinarySummaryParser.Parse(smspec, unsmry);
            var series = Assert.Single(result);
            Assert.Equal("FOPR", series.Keyword);
            Assert.Equal("STB/DAY", series.Unit);
            Assert.Equal(new[] { 0d, 1d }, series.Points.Select(point => point.TimeDays));
            Assert.Equal(new[] { 12.5d, 11.25d }, series.Points.Select(point => point.Value));
            Assert.True(EclipseParsers.IsPublishableSummary(result));
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void ParsesMultipleStepSummaryFilesInOrderWhenUnsmryIsAbsent()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-binary-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var smspec = Path.Combine(root, "CASE.SMSPEC");
            var first = Path.Combine(root, "CASE.S0001");
            var second = Path.Combine(root, "CASE.S0002");
            File.WriteAllBytes(smspec, Section("KEYWORDS", Strings("TIME", "FOPR"))
                .Concat(Section("WGNAMES", Strings("FIELD", "FIELD")))
                .Concat(Section("UNITS", Strings("DAYS", "STB/DAY"))).ToArray());
            File.WriteAllBytes(first, Section("SEQHDR", Ints(1))
                .Concat(Section("PARAMS", Floats(0, 12.5f))).ToArray());
            File.WriteAllBytes(second, Section("SEQHDR", Ints(2))
                .Concat(Section("PARAMS", Floats(1, 11.25f))).ToArray());

            var result = EclipseBinarySummaryParser.Parse(smspec, new[] { second, first }.OrderBy(path => path));
            var series = Assert.Single(result);
            Assert.Equal(new[] { 0d, 1d }, series.Points.Select(point => point.TimeDays));
            Assert.Equal(new[] { 12.5d, 11.25d }, series.Points.Select(point => point.Value));
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task FallsBackToSplitSummaryWhenUnsmryIsMalformed()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-binary-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var smspec = Path.Combine(root, "CASE.SMSPEC");
            var unsmry = Path.Combine(root, "CASE.UNSMRY");
            var step = Path.Combine(root, "CASE.S0001");
            File.WriteAllBytes(smspec, Section("KEYWORDS", Strings("TIME", "FOPR"))
                .Concat(Section("WGNAMES", Strings("FIELD", "FIELD")))
                .Concat(Section("UNITS", Strings("DAYS", "STB/DAY"))).ToArray());
            File.WriteAllBytes(unsmry, [1, 2, 3]);
            File.WriteAllBytes(step, Section("SEQHDR", Ints(1))
                .Concat(Section("PARAMS", Floats(0, 12.5f))).ToArray());

            var result = await EclipseRunService.ReadSummaryAsync(smspec, unsmry, null, [step]);
            var series = Assert.Single(result!);
            Assert.Equal(new[] { 12.5d }, series.Points.Select(point => point.Value));
        }
        finally { Directory.Delete(root, true); }
    }

    [Theory]
    [InlineData("YEARS", "YEARS")]
    [InlineData("TIME", "YEARS")]
    [InlineData("FOPR", "DAYS")]
    public void RejectsAbsentOrNonDayTimeAxesRatherThanInventingDays(string axis, string unit)
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-binary-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var spec = Path.Combine(root, "CASE.SMSPEC");
            var data = Path.Combine(root, "CASE.UNSMRY");
            File.WriteAllBytes(spec, Section("KEYWORDS", Strings(axis, "FOPR"))
                .Concat(Section("WGNAMES", Strings("FIELD", "FIELD")))
                .Concat(Section("UNITS", Strings(unit, "STB/DAY"))).ToArray());
            File.WriteAllBytes(data, Section("PARAMS", Floats(1, 12.5f)));
            Assert.Throws<InvalidDataException>(() => EclipseBinarySummaryParser.Parse(spec, data));
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void OfficialBlankDimensionlessUnitsRemainNullWithoutDiscardingOtherSeries()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-binary-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var spec = Path.Combine(root, "CASE.SMSPEC");
            var data = Path.Combine(root, "CASE.UNSMRY");
            File.WriteAllBytes(spec, Section("KEYWORDS", Strings("TIME", "WWCT"))
                .Concat(Section("WGNAMES", Strings("FIELD", "G1")))
                .Concat(Section("UNITS", Strings("DAYS", ""))).ToArray());
            File.WriteAllBytes(data, Section("PARAMS", Floats(1, 0.25f)));
            var result = EclipseBinarySummaryParser.Parse(spec, data);
            Assert.Null(Assert.Single(result).Unit);
            Assert.True(EclipseParsers.IsPublishableSummary(result));
        }
        finally { Directory.Delete(root, true); }
    }

    private static byte[] Section(string keyword, byte[] data)
    {
        var type = keyword is "KEYWORDS" or "WGNAMES" or "UNITS" ? "CHAR" : keyword == "PARAMS" ? "REAL" : "INTE";
        var size = type == "CHAR" ? 8 : 4;
        var header = Encoding.ASCII.GetBytes(keyword.PadRight(8)[..8]).Concat(Ints(data.Length / size))
            .Concat(Encoding.ASCII.GetBytes(type)).ToArray();
        return data.Length == 0 ? Record(header) : Record(header).Concat(Record(data)).ToArray();
    }

    private static byte[] Strings(params string[] values) => values.SelectMany(value => Encoding.ASCII.GetBytes(value.PadRight(8)[..8])).ToArray();
    private static byte[] Ints(params int[] values) => values.SelectMany(value => { var data = new byte[4]; BinaryPrimitives.WriteInt32LittleEndian(data, value); return data; }).ToArray();
    private static byte[] Floats(params float[] values) => values.SelectMany(value => { var data = new byte[4]; BinaryPrimitives.WriteInt32LittleEndian(data, BitConverter.SingleToInt32Bits(value)); return data; }).ToArray();
    private static byte[] Record(byte[] data)
    {
        var result = new byte[data.Length + 8];
        BinaryPrimitives.WriteInt32LittleEndian(result.AsSpan(0, 4), data.Length);
        data.CopyTo(result, 4);
        BinaryPrimitives.WriteInt32LittleEndian(result.AsSpan(data.Length + 4, 4), data.Length);
        return result;
    }
}
