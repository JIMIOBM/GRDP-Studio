using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.IO.Compression;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseForecastSourceTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-forecast-source", Guid.NewGuid().ToString("N"));
    public EclipseForecastSourceTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Static = "RUNSPEC\nTITLE\n SOURCE TITLE\nDIMENS\n 3 1 1 /\nOIL\nWATER\nGAS\nFIELD\nSTART\n 1 'JAN' 1970 /\nFMTOUT\nUNIFOUT\nGRID\nINCLUDE\n 'GRID.INC' /\nPROPS\nDENSITY\n 50 60 0.01 /\n";
    private const string Future = "SOLUTION\nRESTART\n 'CASE' 80 /\nSUMMARY\nFOPT\nSCHEDULE\nDATES\n 31 'DEC' 1976 /\n/\nDATES\n 31 'DEC' 1977 /\n/\nEND\n";
    private EclipseDeckPackage Package(string name, bool future, string? text = null)
    {
        File.WriteAllText(Path.Combine(root, "GRID.INC"), "PORO\n 3*0.2 /\nPERMX\n 3*100 /\n");
        var source = text ?? (Static.Replace("FMTOUT\n", future ? "FMTIN\nUNIFIN\nFMTOUT\n" : "FMTOUT\n") + (future ? Future : "SOLUTION\nEQUIL\n 1000 3000 /\nRPTSOL\n PRES\n RESTART\n/\nSUMMARY\nFOPT\nSCHEDULE\nEND\n"));
        var path = Path.Combine(root, name); File.WriteAllText(path, source);
        return new EclipseDeckPackageResolver().Resolve(path, root);
    }
    private static EclipseRestartCatalog Catalog() => new("eclipse-restart-catalog/1", "CASE", "1970-01-01", "eclipse-output-CASE.FUNRST", new('a', 64), 100,
        [new(80, 2190, "1975-12-31", "FIELD", new(3, 1, 1), 3, 7, 100, 0, 100, new('b', 64))]);
    private JsonElement Parameters(string physics) => JsonSerializer.SerializeToElement(new { schemaVersion = EclipseRestartForecastParameters.Schema,
        historyRunId = 4, forecastDataFile = "FORE.DATA", restartArtifactName = "eclipse-output-CASE.FUNRST", restartArtifactSha256 = new string('a', 64), restartReport = 80,
        restartTimeDays = 2190, restartDate = "1975-12-31", physicsSha256 = physics });
    [Fact]
    public void ExactStaticModelRecognizesDateOriginAndRptsolOutputMnemonic()
    {
        var history = Package("CASE.DATA", false); var forecast = Package("FORE.DATA", true);
        var source = EclipseForecastSourceReader.Read(history, false); var future = EclipseForecastSourceReader.Read(forecast, true);
        Assert.Equal(source.PhysicsSha256, future.PhysicsSha256); Assert.Equal("1970-01-01", source.StartDate);
        Assert.Equal(new[] { "1976-12-31", "1977-12-31" }, future.FutureDates);
        var described = EclipseForecastSourceReader.Describe(history, Catalog()); Assert.Single(described.ForecastInputs);
        Assert.True(EclipseRestartForecastParameters.TryParse(Parameters(source.PhysicsSha256), out var parameters));
        parameters!.Validate(history, forecast, Catalog());
    }
    [Theory]
    [InlineData("grid")]
    [InlineData("props")]
    [InlineData("phase")]
    [InlineData("units")]
    [InlineData("start")]
    public void DifferentStaticPhysicalInputsAreNeverCompatible(string fault)
    {
        var history = Package("CASE.DATA", false); var expected = EclipseForecastSourceReader.Read(history, false);
        var text = Static.Replace("FMTOUT\n", "FMTIN\nUNIFIN\nFMTOUT\n") + Future;
        text = fault switch { "grid" => text.Replace("3 1 1", "4 1 1"), "props" => text.Replace("50 60", "49 60"),
            "phase" => text.Replace("GAS\n", ""), "units" => text.Replace("FIELD", "METRIC"), _ => text.Replace("1 'JAN' 1970", "2 'JAN' 1970") };
        var forecast = Package("FORE.DATA", true, text);
        Assert.True(EclipseRestartForecastParameters.TryParse(Parameters(expected.PhysicsSha256), out var parameters));
        Assert.Throws<EclipseDeckPackageException>(() => parameters!.Validate(history, forecast, Catalog()));
        Assert.Empty(EclipseForecastSourceReader.Describe(history, Catalog()).ForecastInputs);
    }
    [Theory]
    [InlineData("initialize")]
    [InlineData("format")]
    [InlineData("tstep")]
    [InlineData("clock")]
    [InlineData("reversed")]
    [InlineData("invalidDate")]
    [InlineData("outside")]
    public void UnsupportedOrUnsafeForecastInputsCannotBeOffered(string fault)
    {
        var text = Static.Replace("FMTOUT\n", "FMTIN\nUNIFIN\nFMTOUT\n") + Future;
        text = fault switch { "initialize" => text.Replace("SUMMARY", "AQUFETP\n 1 2 3 /\nSUMMARY"), "format" => text.Replace("FMTIN\n", ""),
            "tstep" => text.Replace("END", "TSTEP\n 30 /\nEND"), "clock" => text.Replace("1976 /", "1976 '12:00:00' /"),
            "reversed" => text.Replace("1977 /", "1975 /"), "invalidDate" => text.Replace("31 'DEC' 1976", "31 'FEB' 1976"),
            _ => text.Replace("'CASE'", "'../CASE'") };
        Assert.Throws<InvalidDataException>(() => EclipseForecastSourceReader.Read(Package("FORE.DATA", true, text), true));
    }
    [Theory]
    [InlineData("restartReport", "81")]
    [InlineData("restartTimeDays", "2191")]
    [InlineData("restartDate", "\"1975-12-30\"")]
    [InlineData("physicsSha256", "\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"")]
    public void StaleReportDateTimeAndPhysicsRejectBeforeSolver(string key, string json)
    {
        var history = Package("CASE.DATA", false); var forecast = Package("FORE.DATA", true);
        var source = EclipseForecastSourceReader.Read(history, false);
        var node = System.Text.Json.Nodes.JsonNode.Parse(Parameters(source.PhysicsSha256).GetRawText())!; node[key] = System.Text.Json.Nodes.JsonNode.Parse(json);
        Assert.True(EclipseRestartForecastParameters.TryParse(JsonSerializer.SerializeToElement(node), out var parameters));
        Assert.Throws<EclipseDeckPackageException>(() => parameters!.Validate(history, forecast, Catalog()));
    }
    [Theory]
    [InlineData("restartTimeDays", "\"2190\"")]
    [InlineData("restartTimeDays", "2190.5")]
    [InlineData("restartDate", "\"1975-02-31\"")]
    [InlineData("extra", "true")]
    [InlineData("physicsSha256", "\"bad\"")]
    public void StrictContractRejectsWrongTypesAndUnknownFields(string key, string json)
    {
        var node = System.Text.Json.Nodes.JsonNode.Parse(Parameters(new('a', 64)).GetRawText())!; node[key] = System.Text.Json.Nodes.JsonNode.Parse(json);
        Assert.False(EclipseRestartForecastParameters.TryParse(JsonSerializer.SerializeToElement(node), out _));
    }
    [Fact]
    public void CatalogOriginCannotDisagreeWithSourceStart()
    {
        var history = Package("CASE.DATA", false);
        Assert.Throws<InvalidDataException>(() => EclipseForecastSourceReader.Describe(history, Catalog() with { StartDate = "1970-01-02" }));
    }
    [Fact]
    public void CommentsTitleAndOutputSwitchesDoNotChangePhysicalInputFingerprint()
    {
        var history = Package("CASE.DATA", false); var original = EclipseForecastSourceReader.Read(history, false);
        var text = File.ReadAllText(history.MainFile).Replace("SOURCE TITLE", "DIFFERENT TITLE").Replace(" 3 1 1 /", " 3   1  1 / -- comment\n");
        var other = Package("OTHER.DATA", false, text);
        Assert.Equal(original.PhysicsSha256, EclipseForecastSourceReader.Read(other, false).PhysicsSha256);
    }
}
