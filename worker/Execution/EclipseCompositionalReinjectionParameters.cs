using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseCompositionalReinjectionParameters(long BaselineRunId, string Group, string Phase, string SourceFile,
    string SourceSha256, int LineNumber, string Unit, double OriginalFraction, double TargetFraction)
{
    internal const string Schema = "eclipse-compositional-reinjection-parameters/1";
    internal static bool Fraction(double n) => double.IsFinite(n) && n is >= 0 and <= 1;
    internal static bool TryParse(JsonElement input, out EclipseCompositionalReinjectionParameters? result)
    {
        result = null;
        string[] names = ["schemaVersion", "baselineRunId", "group", "phase", "sourceFile", "sourceSha256", "lineNumber", "unit", "originalFraction", "targetFraction"];
        if (input.ValueKind != JsonValueKind.Object || input.EnumerateObject().Count() != names.Length ||
            !input.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names) ||
            !Text(input.GetProperty("schemaVersion"), out var schema) || schema != Schema ||
            !Integer(input.GetProperty("baselineRunId"), out var baseline) || baseline <= 0 ||
            !Text(input.GetProperty("group"), out var group) || group != "FIELD" || !Text(input.GetProperty("phase"), out var phase) || phase != "GAS" ||
            !Text(input.GetProperty("sourceFile"), out var source) || !Regex.IsMatch(source, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$", RegexOptions.CultureInvariant) ||
            !Text(input.GetProperty("sourceSha256"), out var sha) || !Regex.IsMatch(sha, "^[a-f0-9]{64}$", RegexOptions.CultureInvariant) ||
            !Integer(input.GetProperty("lineNumber"), out var line) || line is < 1 or > int.MaxValue ||
            !Text(input.GetProperty("unit"), out var unit) || unit != "fraction" ||
            !Number(input.GetProperty("originalFraction"), out var original) || !Number(input.GetProperty("targetFraction"), out var target) || original == target) return false;
        result = new(baseline, group, phase, source, sha, (int)line, unit, original, target); return true;
    }
    private static bool Text(JsonElement v, out string s) { s = ""; if (v.ValueKind != JsonValueKind.String) return false; s = v.GetString()!; return true; }
    private static bool Integer(JsonElement v, out long n) { n = 0; return v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out n); }
    private static bool Number(JsonElement v, out double n) { n = 0; return v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out n) && Fraction(n); }
}
