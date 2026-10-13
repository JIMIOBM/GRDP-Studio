using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseThpControlParameters(long BaselineRunId, string Well, string SourceFile, int LineNumber,
    string Unit, double OriginalThp, double TargetThp, int VfpTableNumber, string VfpTableSha256, double OriginalAlq)
{
    internal const string Schema = "eclipse-thp-control-parameters/1";
    internal static bool TryParse(JsonElement v, out EclipseThpControlParameters? p)
    {
        p = null;
        string[] fields = ["schemaVersion", "baselineRunId", "well", "phase", "sourceFile", "lineNumber", "unit", "originalThp", "targetThp", "vfpTableNumber", "vfpTableSha256", "originalAlq"];
        if (v.ValueKind != JsonValueKind.Object || v.EnumerateObject().Count() != fields.Length || !v.EnumerateObject().Select(x => x.Name).ToHashSet().SetEquals(fields)
            || !String(v, "schemaVersion", Schema) || !String(v, "phase", "INITIAL") || !String(v, "unit", "psia")
            || v.GetProperty("baselineRunId").ValueKind != JsonValueKind.Number || !v.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline <= 0
            || v.GetProperty("lineNumber").ValueKind != JsonValueKind.Number || !v.GetProperty("lineNumber").TryGetInt32(out var line) || line <= 0
            || v.GetProperty("vfpTableNumber").ValueKind != JsonValueKind.Number || !v.GetProperty("vfpTableNumber").TryGetInt32(out var table) || table <= 0 || table > 100000
            || !Pattern(v, "well", "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$")
            || !Pattern(v, "sourceFile", "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
            || !Pattern(v, "vfpTableSha256", "^[a-f0-9]{64}$")
            || !Number(v, "originalThp", out var original) || original <= 0 || !Number(v, "targetThp", out var target) || target <= 0 || target == original
            || !Number(v, "originalAlq", out var alq) || alq < 0) return false;
        p = new(baseline, v.GetProperty("well").GetString()!, v.GetProperty("sourceFile").GetString()!, line, "psia", original, target, table, v.GetProperty("vfpTableSha256").GetString()!, alq);
        return true;
    }
    private static bool String(JsonElement v, string key, string expected) => v.GetProperty(key).ValueKind == JsonValueKind.String && v.GetProperty(key).GetString() == expected;
    private static bool Pattern(JsonElement v, string key, string pattern) => v.GetProperty(key).ValueKind == JsonValueKind.String && Regex.IsMatch(v.GetProperty(key).GetString()!, pattern);
    private static bool Number(JsonElement v, string key, out double number)
    {
        number = 0;
        return v.GetProperty(key).ValueKind == JsonValueKind.Number && v.GetProperty(key).TryGetDouble(out number) && double.IsFinite(number) && number <= 1e8;
    }
}
