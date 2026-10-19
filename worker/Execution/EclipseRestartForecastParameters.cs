using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseRestartForecastParameters(EclipseHistoryForecastParameters Reference,
    int RestartTimeDays, string RestartDate, string PhysicsSha256, bool Binary = false)
{
    internal const string Schema = "eclipse-history-forecast-parameters/2";
    internal const string BinarySchema = "eclipse-history-forecast-parameters/3";
    internal static bool TryParse(JsonElement value, out EclipseRestartForecastParameters? parameters)
    {
        parameters = null;
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != 9
            || !value.TryGetProperty("schemaVersion", out var schema) || schema.ValueKind != JsonValueKind.String || schema.GetString() is not (Schema or BinarySchema)
            || !value.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(new[] {
                "schemaVersion", "historyRunId", "forecastDataFile", "restartArtifactName", "restartArtifactSha256", "restartReport", "restartTimeDays", "restartDate", "physicsSha256" })
            || !value.TryGetProperty("restartTimeDays", out var time) || time.ValueKind != JsonValueKind.Number || !time.TryGetInt32(out var days) || days < 0 || days > 3_000_000
            || !value.TryGetProperty("restartDate", out var date) || date.ValueKind != JsonValueKind.String
            || !DateOnly.TryParseExact(date.GetString(), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _)
            || !value.TryGetProperty("physicsSha256", out var physics) || physics.ValueKind != JsonValueKind.String || !Regex.IsMatch(physics.GetString() ?? "", "^[a-f0-9]{64}$")) return false;
        var legacy = value.EnumerateObject().Where(p => p.Name is not ("restartTimeDays" or "restartDate" or "physicsSha256"))
            .ToDictionary(p => p.Name, p => p.Name == "schemaVersion" ? JsonSerializer.SerializeToElement(EclipseHistoryForecastParameters.Schema) : p.Value);
        if (!EclipseHistoryForecastParameters.TryParse(JsonSerializer.SerializeToElement(legacy), out var reference, schema.GetString() == BinarySchema)) return false;
        parameters = new(reference!, days, date.GetString()!, physics.GetString()!, schema.GetString() == BinarySchema);
        return true;
    }
    internal void Validate(EclipseDeckPackage history, EclipseDeckPackage future, EclipseRestartCatalog catalog)
    {
        var historical = EclipseForecastSourceReader.Read(history, false);
        var forecast = EclipseForecastSourceReader.Read(future, true, Binary);
        EclipseHistoryForecastParameters.ValidateRestartBinding(future, Reference);
        var report = catalog.Reports.SingleOrDefault(r => r.ReportNumber == Reference.RestartReport);
        if (EclipseRestartReader.IsBinary(catalog) != Binary || (forecast.RestartFormat == "BINARY") != Binary
            || catalog.ArtifactName != Reference.RestartArtifactName || catalog.ArtifactSha256 != Reference.RestartArtifactSha256
            || report is null || report.TimeDays != RestartTimeDays || report.Date != RestartDate
            || historical.StartDate != catalog.StartDate || forecast.StartDate != catalog.StartDate
            || forecast.RestartCase != catalog.CaseName || historical.PhysicsSha256 != PhysicsSha256 || forecast.PhysicsSha256 != PhysicsSha256
            || forecast.FutureDates.Any(d => string.CompareOrdinal(d, RestartDate) <= 0))
            throw new EclipseDeckPackageException("ECLIPSE_RESTART_SOURCE_MISMATCH", "The selected report, time, state source and exact static physical model must match the verified history.");
    }
}
