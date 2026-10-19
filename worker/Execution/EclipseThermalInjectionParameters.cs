using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseThermalInjectionParameters(long BaselineRunId, string Well, string SourceFile, string SourceSha256,
    int LineNumber, string Field, string Unit, double OriginalValue, double TargetValue, [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? StartTimeDays = null)
{
    internal const string Schema = "eclipse-thermal-injection-parameters/1";
    internal const string ControlSchema = "eclipse-thermal-injection-parameters/2";
    internal const string StageSchema = "eclipse-thermal-injection-parameters/3";
    internal static bool Value(string field, string unit, double value) => double.IsFinite(value) && (field switch {
        "STEAM_QUALITY" => unit == "fraction" && value is >= 0 and <= 1,
        "INJECTION_TEMPERATURE" => (unit == "degF" && value > -459.67 || unit == "degC" && value > -273.15) && value <= 1_000_000,
        "SURFACE_INJECTION_RATE" => unit is "STB/day" or "sm3/day" && value is > 0 and <= 100_000_000,
        "BHP_UPPER_LIMIT" => unit is "psia" or "barsa" && value is > 0 and <= 100_000_000,
        _ => false
    });
    internal static bool TryParse(JsonElement input, out EclipseThermalInjectionParameters? result)
    {
        result = null;
        var staged = input.ValueKind == JsonValueKind.Object && input.TryGetProperty("schemaVersion", out var version) && version.ValueKind == JsonValueKind.String && version.GetString() == StageSchema;
        string[] fields = ["schemaVersion", "baselineRunId", "well", "sourceFile", "sourceSha256", "lineNumber", "field", "unit", "originalValue", "targetValue"];
        if (staged) fields = [.. fields, "startTimeDays"];
        if (input.ValueKind != JsonValueKind.Object || input.EnumerateObject().Count() != fields.Length
            || !input.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(fields)
            || !Text(input.GetProperty("schemaVersion"), out var schema) || schema is not (Schema or ControlSchema or StageSchema)
            || !Integer(input.GetProperty("baselineRunId"), out var baseline) || baseline <= 0
            || !Text(input.GetProperty("well"), out var well) || !Regex.IsMatch(well,"^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$",RegexOptions.CultureInvariant)
            || !Text(input.GetProperty("sourceFile"), out var source) || !Regex.IsMatch(source,"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$",RegexOptions.CultureInvariant)
            || !Text(input.GetProperty("sourceSha256"), out var sha) || !Regex.IsMatch(sha,"^[a-f0-9]{64}$",RegexOptions.CultureInvariant)
            || !Integer(input.GetProperty("lineNumber"), out var line) || line is < 1 or > int.MaxValue
            || !Text(input.GetProperty("field"), out var field) || schema == Schema && field is not ("STEAM_QUALITY" or "INJECTION_TEMPERATURE")
            || !Text(input.GetProperty("unit"), out var unit)
            || !Number(input.GetProperty("originalValue"), out var original) || !Number(input.GetProperty("targetValue"), out var target)
            || !Value(field, unit, original) || !Value(field, unit, target) || original == target) return false;
        int? start = null;
        if (staged) { if (field is not ("SURFACE_INJECTION_RATE" or "INJECTION_TEMPERATURE") || !Integer(input.GetProperty("startTimeDays"), out var day) || day is < 1 or > 1_000_000_000) return false; start = (int)day; }
        result = new(baseline,well,source,sha,(int)line,field,unit,original,target,start); return true;
    }
    private static bool Text(JsonElement value, out string text) { text = ""; if(value.ValueKind != JsonValueKind.String) return false; text = value.GetString()!; return true; }
    private static bool Integer(JsonElement value, out long n) { n=0; return value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out n); }
    private static bool Number(JsonElement value, out double n) { n=0; return value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out n) && double.IsFinite(n); }
}
