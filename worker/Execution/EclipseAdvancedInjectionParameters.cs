using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseAdvancedInjectionParameters(long BaselineRunId, string Group, string SourceFile,
    int LineNumber, string ControlMode, string InjectionPhase, string Unit, double OriginalTargetRate, double TargetRate, IReadOnlyList<string> Members, string? ReferenceGroup, IReadOnlyList<string> ReferenceMembers)
{
    internal const string Schema = "eclipse-advanced-injection-parameters/1";
    internal static bool TryParse(JsonElement p, out EclipseAdvancedInjectionParameters? result)
    {
        result = null;
        string[] fields = ["schemaVersion", "baselineRunId", "group", "phase", "sourceFile", "lineNumber", "controlMode", "injectionPhase", "unit", "originalTargetRate", "targetRate", "members", "referenceGroup", "referenceMembers"];
        if (p.ValueKind != JsonValueKind.Object || p.EnumerateObject().Count() != fields.Length || !p.EnumerateObject().Select(f => f.Name).ToHashSet().SetEquals(fields)
            || !Text(p, "schemaVersion", Schema) || !Text(p, "phase", "INITIAL") || !Supported(p)
            || p.GetProperty("unit").ValueKind != JsonValueKind.String || !Units(p).Contains(p.GetProperty("unit").GetString())
            || p.GetProperty("baselineRunId").ValueKind != JsonValueKind.Number || !p.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline <= 0
            || p.GetProperty("lineNumber").ValueKind != JsonValueKind.Number || !p.GetProperty("lineNumber").TryGetInt32(out var line) || line <= 0
            || !Name(p.GetProperty("group")) || p.GetProperty("group").GetString() == "FIELD"
            || p.GetProperty("sourceFile").ValueKind != JsonValueKind.String || !Regex.IsMatch(p.GetProperty("sourceFile").GetString()!, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
            || !Rate(p.GetProperty("originalTargetRate"), out var original) || !Rate(p.GetProperty("targetRate"), out var target) || original == target
            || p.GetProperty("members").ValueKind != JsonValueKind.Array) return false;
        var members = p.GetProperty("members").EnumerateArray().ToArray();
        if (members.Length < 2 || members.Length > 1000 || members.Any(v => !Name(v)) || members.Select(v => v.GetString()).Distinct().Count() != members.Length) return false;
        var refGroup = p.GetProperty("referenceGroup"); var refMembers = p.GetProperty("referenceMembers");
        bool ratio = p.GetProperty("controlMode").GetString() is "REIN" or "VREP";
        if (refMembers.ValueKind != JsonValueKind.Array || (ratio ? !Name(refGroup) || refGroup.GetString() == "FIELD" || refGroup.GetString() == p.GetProperty("group").GetString() : refGroup.ValueKind != JsonValueKind.Null)) return false;
        var refs = refMembers.EnumerateArray().ToArray();
        if (ratio ? refs.Length < 1 || refs.Length > 1000 || refs.Any(v => !Name(v)) || refs.Select(v => v.GetString()).Distinct().Count() != refs.Length : refs.Length != 0) return false;
        result = new(baseline, p.GetProperty("group").GetString()!, p.GetProperty("sourceFile").GetString()!, line,
            p.GetProperty("controlMode").GetString()!, p.GetProperty("injectionPhase").GetString()!, p.GetProperty("unit").GetString()!, original, target, members.Select(v => v.GetString()!).ToArray(), ratio ? refGroup.GetString() : null, refs.Select(v => v.GetString()!).ToArray());
        return true;
    }
    private static bool Supported(JsonElement p) => p.GetProperty("controlMode").ValueKind == JsonValueKind.String && p.GetProperty("injectionPhase").ValueKind == JsonValueKind.String &&
        (Text(p, "injectionPhase", "OIL") && Text(p, "controlMode", "RATE") || Text(p, "injectionPhase", "GAS") && p.GetProperty("controlMode").GetString() is "RESV" or "REIN" or "VREP");
    private static string[] Units(JsonElement p) => p.GetProperty("controlMode").GetString() switch { "REIN" or "VREP" => ["fraction"], "RESV" => ["RB/day", "rm3/day"], _ => ["STB/day", "sm3/day"] };
    private static bool Text(JsonElement p, string key, string value) => p.GetProperty(key).ValueKind == JsonValueKind.String && p.GetProperty(key).GetString() == value;
    private static bool Name(JsonElement v) => v.ValueKind == JsonValueKind.String && Regex.IsMatch(v.GetString()!, "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static bool Rate(JsonElement v, out double n) { n = 0; return v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out n) && double.IsFinite(n) && n > 0 && n <= 100_000_000; }
}
