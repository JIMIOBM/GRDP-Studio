using System.Globalization;
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

    internal static void ValidateRestartBinding(EclipseDeckPackage forecastPackage, EclipseHistoryForecastParameters parameters)
    {
        var declarations = new List<(string BaseName, int Report)>();
        foreach (var file in forecastPackage.Files)
        {
            var source = WithoutComments(EclipseDeckPackageResolver.ReadUtf8(file));
            foreach (Match match in RestartDeclaration().Matches(source))
            {
                var baseName = match.Groups["single"].Success ? match.Groups["single"].Value
                    : match.Groups["double"].Success ? match.Groups["double"].Value
                    : match.Groups["bare"].Value;
                if (!int.TryParse(match.Groups["report"].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var report))
                    throw new EclipseDeckPackageException("ECLIPSE_FORECAST_RESTART_INVALID", "The forecast RESTART report number is invalid.");
                declarations.Add((baseName, report));
            }
        }

        if (declarations.Count != 1)
            throw new EclipseDeckPackageException(declarations.Count == 0 ? "ECLIPSE_FORECAST_RESTART_MISSING" : "ECLIPSE_FORECAST_RESTART_AMBIGUOUS",
                declarations.Count == 0 ? "The selected forecast DATA package has no unambiguous RESTART declaration." : "The selected forecast DATA package contains multiple RESTART declarations.");

        var declaration = declarations[0];
        var artifactBaseName = parameters.RestartArtifactName["eclipse-output-".Length..^".FUNRST".Length];
        var deckBaseName = declaration.BaseName.EndsWith(".FUNRST", StringComparison.OrdinalIgnoreCase)
            ? declaration.BaseName[..^".FUNRST".Length]
            : declaration.BaseName;
        if (!string.Equals(deckBaseName, artifactBaseName, StringComparison.OrdinalIgnoreCase)
            || declaration.Report != parameters.RestartReport)
            throw new EclipseDeckPackageException("ECLIPSE_FORECAST_RESTART_MISMATCH",
                "The selected FUNRST Artifact and report must exactly match the forecast DATA RESTART declaration.");
    }

    private static string WithoutComments(string source)
    {
        var characters = source.ToCharArray();
        var quote = '\0';
        var inComment = false;
        for (var index = 0; index < characters.Length; index++)
        {
            var current = characters[index];
            if (inComment)
            {
                if (current is '\r' or '\n') inComment = false;
                else characters[index] = ' ';
                continue;
            }
            if (quote != '\0')
            {
                if (current == quote)
                {
                    if (quote == '\'' && index + 1 < characters.Length && characters[index + 1] == '\'') { index++; continue; }
                    quote = '\0';
                }
                continue;
            }
            if (current is '\'' or '"') { quote = current; continue; }
            if (current == '-' && index + 1 < characters.Length && characters[index + 1] == '-')
            {
                characters[index++] = ' ';
                characters[index] = ' ';
                inComment = true;
            }
        }
        return new string(characters);
    }

    [GeneratedRegex(@"^[A-Za-z0-9][A-Za-z0-9._ /-]{0,511}\.DATA$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex SafeDataPath();
    [GeneratedRegex(@"^eclipse-output-[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.FUNRST$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex RestartArtifact();
    [GeneratedRegex("^[a-f0-9]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex Sha256();
    [GeneratedRegex("(?im)^[ \\t]*RESTART\\b[ \\t\\r\\n]+(?:'(?<single>[^'\\r\\n]+)'|\"(?<double>[^\"\\r\\n]+)\"|(?<bare>[A-Za-z0-9_.-]+))[ \\t\\r\\n]+(?<report>[0-9]+)[ \\t\\r\\n]*/[ \\t]*$", RegexOptions.CultureInvariant)]
    private static partial Regex RestartDeclaration();
}
