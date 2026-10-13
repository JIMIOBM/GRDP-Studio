using System.Globalization;
using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseFormattedRestartTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-restart-reader", Guid.NewGuid().ToString("N"));
    public EclipseFormattedRestartTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private string Write(string text) { var path = Path.Combine(root, "CASE.FUNRST"); File.WriteAllText(path, text, System.Text.Encoding.ASCII); return path; }
    private static string Block(string keyword, string type, IEnumerable<string> values)
    {
        var array = values.ToArray();
        return $" '{keyword.PadRight(8)}' {array.Length} '{type}'\r\n" + string.Join("\r\n", array.Chunk(4).Select(chunk => " " + string.Join(" ", chunk))) + "\r\n";
    }
    internal static string Report(int number, int days, Action<int[]>? change = null)
    {
        var date = new DateOnly(1970, 1, 1).AddDays(days);
        var ih = new int[208]; ih[1] = 202401; ih[2] = 2; ih[8] = 3; ih[9] = 1; ih[10] = 1; ih[11] = 3; ih[14] = 7;
        ih[64] = date.Day; ih[65] = date.Month; ih[66] = date.Year; ih[94] = 100; change?.Invoke(ih);
        return Block("SEQNUM", "INTE", [number.ToString()]) + Block("INTEHEAD", "INTE", ih.Select(n => n.ToString(CultureInfo.InvariantCulture))) +
            Block("LOGIHEAD", "LOGI", ["T", "F"]) + Block("DOUBHEAD", "DOUB", [days.ToString(), "1.0D+00"]) +
            Block("STARTSOL", "MESS", []) + Block("PRESSURE", "REAL", ["4000", "3999", "3998"]) +
            Block("SWAT", "REAL", ["0.1", "0.2", "0.3"]) + Block("SGAS", "REAL", ["0.4", "0.5", "0.6"]) + Block("ENDSOL", "MESS", []);
    }
    [Fact]
    public void ReadsFullReportCatalogAndExtractsExactPortableBlock()
    {
        var first = Report(0, 0); var selected = Report(80, 2190); var path = Write(first + selected);
        var before = File.ReadAllBytes(path); var catalog = EclipseFormattedRestartReader.Read(path);
        Assert.Equal("1970-01-01", catalog.StartDate); Assert.Equal(2, catalog.Reports.Count);
        var report = catalog.Reports[1]; Assert.Equal(80, report.ReportNumber); Assert.Equal(2190, report.TimeDays); Assert.Equal("1975-12-31", report.Date);
        Assert.Equal("FIELD", report.UnitSystem); Assert.Equal(3, report.ActiveCells); Assert.Equal(7, report.PhaseMask); Assert.Equal(100, report.Solver);
        var bytes = EclipseFormattedRestartReader.Select(path, catalog, 80);
        Assert.Equal(System.Text.Encoding.ASCII.GetBytes(selected), bytes); Assert.Equal(before, File.ReadAllBytes(path));
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(bytes)), report.Sha256);
        var portable = Path.Combine(root, "portable.FUNRST"); File.WriteAllBytes(portable, bytes);
        var next = EclipseFormattedRestartReader.Read(portable); Assert.Equal("1970-01-01", next.StartDate); Assert.Single(next.Reports);
    }
    [Fact]
    public void SourceChangeAndMissingOrForgedReportAreRejected()
    {
        var original = Report(0, 0) + Report(80, 2190); var path = Write(original); var catalog = EclipseFormattedRestartReader.Read(path);
        Assert.Throws<InvalidDataException>(() => EclipseFormattedRestartReader.Select(path, catalog, 81));
        var forged = catalog with { Reports = [catalog.Reports[1] with { TimeDays = 2191 }] };
        Assert.Throws<InvalidDataException>(() => EclipseFormattedRestartReader.Select(path, forged, 80));
        Assert.Throws<InvalidDataException>(() => EclipseFormattedRestartReader.Select(path, catalog with { CaseName = "OTHER" }, 80));
        Assert.Throws<InvalidDataException>(() => EclipseFormattedRestartReader.Select(path, catalog with { ArtifactName = "eclipse-output-OTHER.FUNRST" }, 80));
        Assert.Throws<InvalidDataException>(() => EclipseFormattedRestartReader.Select(path, catalog with { SchemaVersion = "eclipse-restart-catalog/2" }, 80));
        Write(original.Replace("3998", "3997"));
        Assert.Throws<InvalidDataException>(() => EclipseFormattedRestartReader.Select(path, catalog, 80));
    }
    [Theory]
    [InlineData("solver")]
    [InlineData("unit")]
    [InlineData("grid")]
    [InlineData("active")]
    [InlineData("phase")]
    [InlineData("hour")]
    [InlineData("minute")]
    [InlineData("date")]
    [InlineData("origin")]
    public void IncompatibleMetadataCannotProduceCatalog(string fault)
    {
        var path = Write(Report(0, 0) + Report(80, 2190, ih => {
            switch (fault) {
                case "solver": ih[94] = 300; break; case "unit": ih[2] = 3; break; case "grid": ih[8] = 4; break;
                case "active": ih[11] = 0; break; case "phase": ih[14] = 8; break; case "hour": ih[206] = 1; break;
                case "minute": ih[207] = 1; break; case "date": ih[65] = 13; break; case "origin": ih[64] = 30; break;
            }
        }));
        Assert.Throws<InvalidDataException>(() => EclipseFormattedRestartReader.Read(path));
    }
    [Theory]
    [InlineData("duplicate")]
    [InlineData("order")]
    [InlineData("fraction")]
    [InlineData("missing")]
    [InlineData("truncate")]
    [InlineData("nonfinite")]
    [InlineData("lgr")]
    [InlineData("garbage")]
    [InlineData("markers")]
    [InlineData("calendarUnderflow")]
    [InlineData("missingWells")]
    [InlineData("missingGroups")]
    public void DamagedReportsCannotProduceCatalog(string fault)
    {
        var text = Report(0, 0) + Report(80, 2190);
        switch (fault) {
            case "duplicate": text += Report(80, 2190); break;
            case "order": text = Report(80, 2190) + Report(0, 0); break;
            case "fraction": text = text.Replace(" 2190 1.0D+00", " 2190.5 1.0D+00"); break;
            case "missing": text = text.Replace("'ENDSOL  '", "'OTHER   '"); break;
            case "truncate": text = text.Replace("4000 3999 3998", "4000 3999"); break;
            case "nonfinite": text = text.Replace("3998", "NaN"); break;
            case "lgr": text += Block("LGR", "CHAR", ["'LOCAL   '"]); break;
            case "garbage": text = "unexpected\r\n" + text; break;
            case "markers": text = text.Replace("'STARTSOL'", "'TMP     '").Replace("'ENDSOL  '", "'STARTSOL'").Replace("'TMP     '", "'ENDSOL  '"); break;
            case "calendarUnderflow": text = text.Replace(" 2190 1.0D+00", " 3000000 1.0D+00"); break;
            case "missingWells": text = Report(80, 2190, ih => { ih[16] = 1; ih[24] = 155; }); break;
            case "missingGroups": text = Report(80, 2190, ih => { ih[20] = 1; ih[36] = 112; }); break;
        }
        Assert.Throws<InvalidDataException>(() => EclipseFormattedRestartReader.Read(Write(text)));
    }
}
