using System.Buffers.Binary;
using System.Text;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalSummaryTests
{
    [Fact]
    public void KeepsDuplicateColumnsRawNumbersAndUndefinedSlotsWithoutMerging()
    {
        WithFiles((spec, data) => {
            var result = EclipseCompositionalSummaryParser.Parse(spec, [data]);
            Assert.Equal("1980-01-01", result.StartDate);
            Assert.Equal(3, result.Series.Count);
            Assert.Equal(new[] { 1, 2, 3 }, result.Series.Select(s => s.VectorIndex));
            Assert.Equal(new[] { "WXMF_1", "WXMF_1", "WXMF_2" }, result.Series.Select(s => s.Keyword));
            Assert.Equal(new[] { 1, 1, 7 }, result.Series.Select(s => s.Number));
            Assert.Equal(":+:+:+:+", result.Series[2].ObjectName);
            Assert.Null(result.Series[0].Unit);
            Assert.Equal(new[] { 1d, 2.5d }, result.Series[0].Points.Select(p => p.TimeDays));
            Assert.Equal(new[] { .25d, .5d }, result.Series[0].Points.Select(p => p.Value));
            Assert.Equal(new[] { .75d, .125d }, result.Series[1].Points.Select(p => p.Value));
        });
    }
    [Theory]
    [InlineData(300, "FIELD", 1, 1, 1)]
    [InlineData(500, "METRIC", 1, 1, 1)]
    [InlineData(300, "METRIC", 2, 1, 1)]
    [InlineData(100, "METRIC", 1, 1, 1)]
    public void NativeProgramUnitsAndGridCannotBeMixed(int program, string unit, int nx, int ny, int nz)
    {
        WithFiles((spec, data) => Assert.Throws<InvalidDataException>(() =>
            EclipseCompositionalSummaryParser.ParseForModel(spec, [data], program, unit, nx, ny, nz)));
    }

    [Fact]
    public void Thermal500HasSeparateBindingAndCannotEnterFrozenCompositionalParser()
    {
        WithFiles((spec, data) => {
            Assert.Equal(3, EclipseCompositionalSummaryParser.ParseForModel(spec, [data], 500, "METRIC", 1, 1, 1).Series.Count);
            Assert.Throws<InvalidDataException>(() => EclipseCompositionalSummaryParser.Parse(spec, [data]));
        }, "thermalSolver");
        WithFiles((spec, data) => Assert.Equal(3, EclipseCompositionalSummaryParser.ParseForModel(spec, [data], 300, "METRIC", 1, 1, 1).Series.Count));
    }

    [Theory]
    [InlineData("solver")]
    [InlineData("nums")]
    [InlineData("columns")]
    [InlineData("date")]
    [InlineData("axis")]
    [InlineData("local")]
    [InlineData("nonfinite")]
    [InlineData("clock")]
    [InlineData("params")]
    [InlineData("unknown")]
    [InlineData("missing")]
    [InlineData("duplicate")]
    public void RejectsIncompleteIdentityAndMalformedNumericalRecords(string fault)
    {
        WithFiles((spec, data) => Assert.Throws<InvalidDataException>(() => EclipseCompositionalSummaryParser.Parse(spec, [data])), fault);
    }

    private static void WithFiles(Action<string, string> verify, string fault = "")
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-e300-summary", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var spec = Path.Combine(root, "CASE.SMSPEC"); var data = Path.Combine(root, "CASE.UNSMRY");
            var sections = new List<byte[]> {
                Section("INTEHEAD", "INTE", Ints(1, fault == "solver" ? 100 : fault == "thermalSolver" ? 500 : 300)),
                Section("DIMENS", "INTE", Ints(4, 1, 1, 1, 0, 0)),
                Section("KEYWORDS", "CHAR", Strings(fault == "axis" ? "YEARS" : "TIME", "WXMF_1", "WXMF_1", "WXMF_2")),
                Section("WGNAMES", "CHAR", fault == "columns" ? Strings("", "P") : Strings("", "P", "P", ":+:+:+:+")),
                Section("UNITS", "CHAR", Strings("DAYS", "", "", "")),
                Section("STARTDAT", "INTE", Ints(1, fault == "date" ? 13 : 1, 1980, 0, 0, 0)) };
            if (fault != "missing") sections.Add(Section("NUMS", "INTE", fault == "nums" ? Ints(0, 1) : Ints(0, 1, 1, 7)));
            if (fault == "duplicate") sections.Add(Section("NUMS", "INTE", Ints(0, 1, 1, 7)));
            if (fault == "local") sections.Add(Section("NUMLX", "INTE", Ints(0, 1, 0, 0)));
            File.WriteAllBytes(spec, sections.SelectMany(b => b).ToArray());
            var records = new List<byte[]> { Section("SEQHDR", "INTE", Ints(1)),
                Section("PARAMS", "REAL", fault == "params" ? Floats(1, .25f) : Floats(1, .25f, .75f, .625f)),
                Section("MINISTEP", "INTE", Ints(1)),
                Section("PARAMS", "REAL", Floats(fault == "clock" ? 1 : 2.5f, fault == "nonfinite" ? float.NaN : .5f, .125f, .875f)) };
            if (fault == "unknown") records.Add(Section("UNKNOWN", "INTE", Ints(1)));
            File.WriteAllBytes(data, records.SelectMany(b => b).ToArray());
            verify(spec, data);
        }
        finally { Directory.Delete(root, recursive: true); }
    }
    private static byte[] Section(string name, string type, byte[] data)
    {
        var header = Encoding.ASCII.GetBytes(name.PadRight(8)).Concat(Ints(data.Length / (type == "CHAR" ? 8 : 4))).Concat(Encoding.ASCII.GetBytes(type)).ToArray();
        return Ints(16).Concat(header).Concat(Ints(16)).Concat(Ints(data.Length)).Concat(data).Concat(Ints(data.Length)).ToArray();
    }
    private static byte[] Strings(params string[] values) => values.SelectMany(v => Encoding.ASCII.GetBytes(v.PadRight(8))).ToArray();
    private static byte[] Ints(params int[] values) => values.SelectMany(v => { var b = new byte[4]; BinaryPrimitives.WriteInt32BigEndian(b, v); return b; }).ToArray();
    private static byte[] Floats(params float[] values) => Ints(values.Select(BitConverter.SingleToInt32Bits).ToArray());
}
