using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseCompositionalFluidPropertyParameters(long BaselineRunId, string SourceFile, string SourceSha256,
    int LineNumber, string Field, int ComponentIndex, string ComponentName, string Unit, double OriginalValue, double TargetValue)
{
    internal const string Schema = "eclipse-compositional-fluid-property-parameters/1";
    internal const string TemperatureSchema = "eclipse-compositional-temperature-parameters/1";
    internal const string CriticalZSchema = "eclipse-compositional-critical-z-parameters/1";
    internal const string AcentricFactorSchema = "eclipse-compositional-acentric-factor-parameters/1";
    internal const string EosPropertySchema = "eclipse-compositional-eos-property-parameters/1";
    internal static bool IsEosField(string field) => field is "MW" or "OMEGAA" or "OMEGAB";
    internal static string PropertyUnit(string field) => field == "PCRIT" ? "psia" : field == "TCRIT" ? "degR" : field == "MW" ? "lb/lb-M" : "dimensionless";
    internal static bool PropertyValue(string field, double value) => field == "ACF" ? double.IsFinite(value) && Math.Abs(value) <= 100_000_000 : Positive(value);
    internal static bool Positive(double value) => double.IsFinite(value) && value is > 0 and <= 100_000_000;
    internal static bool TryParse(JsonElement input, out EclipseCompositionalFluidPropertyParameters? result)
    {
        result = null;
        string[] fields = ["schemaVersion", "baselineRunId", "sourceFile", "sourceSha256", "lineNumber", "field", "componentIndex", "componentName", "unit", "originalValue", "targetValue"];
        if (input.ValueKind != JsonValueKind.Object || input.EnumerateObject().Count() != fields.Length ||
            !input.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(fields) ||
            !Text(input.GetProperty("schemaVersion"), out var schema) || schema is not (Schema or TemperatureSchema or CriticalZSchema or AcentricFactorSchema or EosPropertySchema) ||
            !Integer(input.GetProperty("baselineRunId"), out var baseline) || baseline <= 0 ||
            !Text(input.GetProperty("sourceFile"), out var source) || !Regex.IsMatch(source, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$", RegexOptions.CultureInvariant) ||
            !Text(input.GetProperty("sourceSha256"), out var sha) || !Regex.IsMatch(sha, "^[a-f0-9]{64}$", RegexOptions.CultureInvariant) ||
            !Integer(input.GetProperty("lineNumber"), out var line) || line is < 1 or > int.MaxValue ||
            !Text(input.GetProperty("field"), out var field) || (schema == EosPropertySchema ? !IsEosField(field) : field != (schema == Schema ? "PCRIT" : schema == TemperatureSchema ? "TCRIT" : schema == CriticalZSchema ? "ZCRIT" : "ACF")) ||
            !Integer(input.GetProperty("componentIndex"), out var index) || index is < 1 or > 9 ||
            !Text(input.GetProperty("componentName"), out var name) || !Regex.IsMatch(name, "^[A-Za-z0-9][A-Za-z0-9+_.-]{0,15}$", RegexOptions.CultureInvariant) ||
            !Text(input.GetProperty("unit"), out var unit) || unit != PropertyUnit(field) ||
            !Number(input.GetProperty("originalValue"), field, out var original) || !Number(input.GetProperty("targetValue"), field, out var target) || original == target) return false;
        result = new(baseline, source, sha, (int)line, field, (int)index, name, unit, original, target); return true;
    }
    private static bool Text(JsonElement v, out string value) { value = ""; if (v.ValueKind != JsonValueKind.String) return false; value = v.GetString()!; return true; }
    private static bool Integer(JsonElement v, out long value) { value = 0; return v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out value); }
    private static bool Number(JsonElement v, string field, out double value) { value = 0; return v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out value) && PropertyValue(field, value); }
}
