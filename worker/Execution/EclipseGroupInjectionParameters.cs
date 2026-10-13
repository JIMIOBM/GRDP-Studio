using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseGroupInjectionParameters(long BaselineRunId, string Group, string SourceFile,
    int LineNumber, string InjectionPhase, string Unit, double OriginalTargetRate, double TargetRate, IReadOnlyList<string> Members)
{
    internal const string Schema = "eclipse-group-injection-parameters/1";
    internal static bool TryParse(JsonElement p, out EclipseGroupInjectionParameters? result)
    {
        result = null;
        string[] fields = ["schemaVersion", "baselineRunId", "group", "phase", "sourceFile", "lineNumber", "controlMode", "injectionPhase", "unit", "originalTargetRate", "targetRate", "members"];
        if (p.ValueKind != JsonValueKind.Object || p.EnumerateObject().Count() != fields.Length || !p.EnumerateObject().Select(f => f.Name).ToHashSet().SetEquals(fields)
            || !Text(p, "schemaVersion", Schema) || !Text(p, "phase", "INITIAL") || !Text(p, "controlMode", "RATE")
            || p.GetProperty("injectionPhase").ValueKind != JsonValueKind.String || p.GetProperty("injectionPhase").GetString() != "WATER"
            || p.GetProperty("unit").ValueKind != JsonValueKind.String || p.GetProperty("unit").GetString() is not ("STB/day" or "sm3/day")
            || p.GetProperty("baselineRunId").ValueKind != JsonValueKind.Number || !p.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline <= 0
            || p.GetProperty("lineNumber").ValueKind != JsonValueKind.Number || !p.GetProperty("lineNumber").TryGetInt32(out var line) || line <= 0
            || !Name(p.GetProperty("group")) || p.GetProperty("group").GetString() == "FIELD"
            || p.GetProperty("sourceFile").ValueKind != JsonValueKind.String || !Regex.IsMatch(p.GetProperty("sourceFile").GetString()!, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
            || !Rate(p.GetProperty("originalTargetRate"), out var original) || !Rate(p.GetProperty("targetRate"), out var target) || original == target
            || p.GetProperty("members").ValueKind != JsonValueKind.Array) return false;
        var members = p.GetProperty("members").EnumerateArray().ToArray();
        if (members.Length < 2 || members.Length > 1000 || members.Any(v => !Name(v)) || members.Select(v => v.GetString()).Distinct().Count() != members.Length) return false;
        result = new(baseline, p.GetProperty("group").GetString()!, p.GetProperty("sourceFile").GetString()!, line,
            p.GetProperty("injectionPhase").GetString()!, p.GetProperty("unit").GetString()!, original, target, members.Select(v => v.GetString()!).ToArray());
        return true;
    }
    private static bool Text(JsonElement p, string key, string value) => p.GetProperty(key).ValueKind == JsonValueKind.String && p.GetProperty(key).GetString() == value;
    private static bool Name(JsonElement v) => v.ValueKind == JsonValueKind.String && Regex.IsMatch(v.GetString()!, "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static bool Rate(JsonElement v, out double n) { n = 0; return v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out n) && double.IsFinite(n) && n > 0 && n <= 100_000_000; }
}
