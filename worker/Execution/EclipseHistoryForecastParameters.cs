using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed partial record EclipseHistoryForecastParameters(
    long HistoryRunId,
    string ForecastDataFile,
    string RestartArtifactName,
    string RestartArtifactSha256,
    int RestartReport)
{
    internal const string Schema = "eclipse-history-forecast-parameters/1";

    internal static bool TryParse(JsonElement value, out EclipseHistoryForecastParameters? parameters)
    {
        parameters = null;
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != 6
            || !value.TryGetProperty("schemaVersion", out var schema) || schema.ValueKind != JsonValueKind.String || schema.GetString() != Schema
            || !value.TryGetProperty("historyRunId", out var history) || history.ValueKind != JsonValueKind.Number || !history.TryGetInt64(out var historyId) || historyId <= 0
            || !value.TryGetProperty("forecastDataFile", out var forecast) || forecast.ValueKind != JsonValueKind.String
            || !SafeDataPath().IsMatch(forecast.GetString() ?? string.Empty)
            || !value.TryGetProperty("restartArtifactName", out var artifact) || artifact.ValueKind != JsonValueKind.String
            || !RestartArtifact().IsMatch(artifact.GetString() ?? string.Empty)
            || !value.TryGetProperty("restartArtifactSha256", out var sha) || sha.ValueKind != JsonValueKind.String
            || !Sha256().IsMatch(sha.GetString() ?? string.Empty)
            || !value.TryGetProperty("restartReport", out var report) || report.ValueKind != JsonValueKind.Number || !report.TryGetInt32(out var reportNumber) || reportNumber <= 0)
            return false;
        var names = value.EnumerateObject().Select(property => property.Name).ToHashSet(StringComparer.Ordinal);
        if (!names.SetEquals(["schemaVersion", "historyRunId", "forecastDataFile", "restartArtifactName", "restartArtifactSha256", "restartReport"])) return false;
        var forecastPath = forecast.GetString()!;
        if (forecastPath.Contains('\\') || forecastPath.StartsWith('/') || forecastPath.Contains(':') || forecastPath.Split('/').Any(part => part is "" or "." or "..")) return false;
        parameters = new(historyId, forecastPath, artifact.GetString()!, sha.GetString()!, reportNumber);
        return true;
    }

    [GeneratedRegex(@"^[A-Za-z0-9][A-Za-z0-9._ /-]{0,511}\.DATA$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex SafeDataPath();
    [GeneratedRegex(@"^eclipse-output-[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.FUNRST$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex RestartArtifact();
    [GeneratedRegex("^[a-f0-9]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex Sha256();
}
