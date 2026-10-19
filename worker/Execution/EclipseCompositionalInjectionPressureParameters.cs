using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseCompositionalInjectionPressureParameters(long BaselineRunId, string Well, string SourceFile,
    string SourceSha256, int LineNumber, string Unit, double OriginalBhpUpperLimit, double TargetBhpUpperLimit)
{
    internal const string Schema = "eclipse-compositional-injection-pressure-parameters/1";
    internal static bool TryParse(JsonElement input, out EclipseCompositionalInjectionPressureParameters? result)
    {
        result = null;
        string[] fields = ["schemaVersion", "baselineRunId", "well", "sourceFile", "sourceSha256", "lineNumber", "unit", "originalBhpUpperLimit", "targetBhpUpperLimit"];
        if (input.ValueKind != JsonValueKind.Object || input.EnumerateObject().Count() != fields.Length
            || !input.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(fields)
            || !Text(input.GetProperty("schemaVersion"), out var schema) || schema != Schema
            || !Integer(input.GetProperty("baselineRunId"), out var baseline) || baseline <= 0
            || !Text(input.GetProperty("well"), out var well) || !Regex.IsMatch(well, "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$", RegexOptions.CultureInvariant)
            || !Text(input.GetProperty("sourceFile"), out var source) || !Regex.IsMatch(source, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$", RegexOptions.CultureInvariant)
            || !Text(input.GetProperty("sourceSha256"), out var sha) || !Regex.IsMatch(sha, "^[a-f0-9]{64}$", RegexOptions.CultureInvariant)
            || !Integer(input.GetProperty("lineNumber"), out var line) || line is < 1 or > int.MaxValue
            || !Text(input.GetProperty("unit"), out var unit) || unit != "psia"
            || !Positive(input.GetProperty("originalBhpUpperLimit"), out var original) || !Positive(input.GetProperty("targetBhpUpperLimit"), out var target)
            || target == original) return false;
        result = new(baseline, well, source, sha, (int)line, unit, original, target);
        return true;
    }
    private static bool Text(JsonElement value, out string text) { text = ""; if (value.ValueKind != JsonValueKind.String) return false; text = value.GetString()!; return true; }
    private static bool Integer(JsonElement value, out long number) { number = 0; return value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out number); }
    private static bool Positive(JsonElement value, out double number) { number = 0; return value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out number) && double.IsFinite(number) && number is > 0 and <= 100_000_000; }
}
