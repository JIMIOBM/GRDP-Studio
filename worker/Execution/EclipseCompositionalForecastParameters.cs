using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseCompositionalForecastParameters(long HistoryRunId, string SourceFile, string SourceSha256,
    string SaveCatalogSha256, int SaveReport, double? TargetGasRate)
{
    internal const string Schema = "eclipse-compositional-forecast-parameters/1";
    internal static bool TryParse(JsonElement value, out EclipseCompositionalForecastParameters? parameters)
    {
        parameters = null;
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != 7
            || !value.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals([
                "schemaVersion", "historyRunId", "sourceFile", "sourceSha256", "saveCatalogSha256", "saveReport", "targetGasRate"])
            || value.GetProperty("schemaVersion").ValueKind != JsonValueKind.String || value.GetProperty("schemaVersion").GetString() != Schema
            || value.GetProperty("historyRunId").ValueKind != JsonValueKind.Number || !value.GetProperty("historyRunId").TryGetInt64(out var history) || history <= 0
            || value.GetProperty("sourceFile").ValueKind != JsonValueKind.String || !Regex.IsMatch(value.GetProperty("sourceFile").GetString() ?? "",
                @"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)
            || value.GetProperty("sourceSha256").ValueKind != JsonValueKind.String || !Hash(value.GetProperty("sourceSha256").GetString())
            || value.GetProperty("saveCatalogSha256").ValueKind != JsonValueKind.String || !Hash(value.GetProperty("saveCatalogSha256").GetString())
            || value.GetProperty("saveReport").ValueKind != JsonValueKind.Number || !value.GetProperty("saveReport").TryGetInt32(out var report) || report is < 1 or > 9999) return false;
        var rate = value.GetProperty("targetGasRate"); double? target = null;
        if (rate.ValueKind != JsonValueKind.Null)
        {
            if (rate.ValueKind != JsonValueKind.Number || !rate.TryGetDouble(out var number) || !double.IsFinite(number) || number is <= 0 or > 100_000_000) return false;
            target = number;
        }
        parameters = new(history, value.GetProperty("sourceFile").GetString()!, value.GetProperty("sourceSha256").GetString()!,
            value.GetProperty("saveCatalogSha256").GetString()!, report, target);
        return true;
    }
    internal bool MatchesSource(EclipseCompositionalSaveSourceReader.Snapshot? source) => source is not null
        && source.Production.SourceFile == SourceFile && source.Production.SourceSha256 == SourceSha256 && source.ReportNumber == SaveReport
        && (TargetGasRate is null || TargetGasRate != source.Production.Records.Single().OriginalGasRate);
    private static bool Hash(string? value) => value is not null && Regex.IsMatch(value, "^[a-f0-9]{64}$", RegexOptions.CultureInvariant);
}
