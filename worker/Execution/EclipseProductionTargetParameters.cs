using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseProductionTargetParameters(long BaselineRunId, string Well, string SourceFile,
    int LineNumber, double OriginalOilRate, double TargetOilRate)
{
    internal const string Schema = "eclipse-production-target-parameters/1";
    internal static bool TryParse(JsonElement value, out EclipseProductionTargetParameters? parameters)
    {
        parameters = null;
        string[] fields = ["schemaVersion", "baselineRunId", "well", "phase", "sourceFile", "lineNumber", "unit", "originalOilRate", "targetOilRate"];
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != fields.Length
            || !value.EnumerateObject().Select(p => p.Name).ToHashSet().SetEquals(fields)
            || value.GetProperty("schemaVersion").ValueKind != JsonValueKind.String || value.GetProperty("schemaVersion").GetString() != Schema
            || value.GetProperty("phase").ValueKind != JsonValueKind.String || value.GetProperty("phase").GetString() != "INITIAL"
            || value.GetProperty("unit").ValueKind != JsonValueKind.String || value.GetProperty("unit").GetString() != "STB/day"
            || value.GetProperty("baselineRunId").ValueKind != JsonValueKind.Number || !value.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline <= 0
            || value.GetProperty("lineNumber").ValueKind != JsonValueKind.Number || !value.GetProperty("lineNumber").TryGetInt32(out var line) || line <= 0
            || value.GetProperty("well").ValueKind != JsonValueKind.String || !Regex.IsMatch(value.GetProperty("well").GetString()!, "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$")
            || value.GetProperty("sourceFile").ValueKind != JsonValueKind.String || !Regex.IsMatch(value.GetProperty("sourceFile").GetString()!, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
            || !Rate(value.GetProperty("originalOilRate"), out var original) || !Rate(value.GetProperty("targetOilRate"), out var target) || target == original) return false;
        parameters = new(baseline, value.GetProperty("well").GetString()!, value.GetProperty("sourceFile").GetString()!, line, original, target);
        return true;
    }
    private static bool Rate(JsonElement value, out double rate)
    {
        rate = 0;
        return value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out rate) && double.IsFinite(rate) && rate > 0 && rate <= 100_000_000;
    }
}
