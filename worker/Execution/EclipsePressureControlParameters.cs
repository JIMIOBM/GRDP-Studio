using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipsePressureControlParameters(long BaselineRunId, string Well, string SourceFile, int LineNumber,
    string Keyword, string OriginalControlMode, double OriginalBhp, double TargetBhp, string? InjectionType)
{
    internal const string Schema = "eclipse-pressure-control-parameters/1";
    internal static bool TryParse(JsonElement value, out EclipsePressureControlParameters? parameters)
    {
        parameters = null;
        string[] fields = ["schemaVersion", "baselineRunId", "well", "phase", "sourceFile", "lineNumber", "keyword", "originalControlMode", "originalBhp", "targetBhp", "unit", "injectionType"];
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != fields.Length
            || !value.EnumerateObject().Select(p => p.Name).ToHashSet().SetEquals(fields)
            || !Text(value, "schemaVersion", out var schema) || schema != Schema
            || !Text(value, "phase", out var phase) || phase != "INITIAL"
            || !Text(value, "unit", out var unit) || unit != "psia"
            || !Text(value, "keyword", out var keyword) || keyword is not ("WCONPROD" or "WCONINJE")
            || !Text(value, "originalControlMode", out var mode)
            || !(keyword == "WCONPROD" ? new[] { "ORAT", "WRAT", "GRAT", "LRAT", "BHP" } : new[] { "RATE", "RESV", "BHP" }).Contains(mode)
            || !Text(value, "well", out var well) || !Regex.IsMatch(well, "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$")
            || !Text(value, "sourceFile", out var file) || !Regex.IsMatch(file, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
            || value.GetProperty("baselineRunId").ValueKind != JsonValueKind.Number || !value.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline <= 0
            || value.GetProperty("lineNumber").ValueKind != JsonValueKind.Number || !value.GetProperty("lineNumber").TryGetInt32(out var line) || line <= 0
            || !Pressure(value.GetProperty("originalBhp"), out var original) || !Pressure(value.GetProperty("targetBhp"), out var target)
            || (original == target && mode == "BHP")) return false;
        var fluid = value.GetProperty("injectionType");
        if (keyword == "WCONPROD" ? fluid.ValueKind != JsonValueKind.String || fluid.GetString() != "NONE"
            : fluid.ValueKind != JsonValueKind.String || fluid.GetString() is not ("OIL" or "WATER" or "WAT" or "GAS")) return false;
        parameters = new(baseline, well, file, line, keyword, mode, original, target, keyword == "WCONINJE" ? fluid.GetString() : null);
        return true;
    }
    private static bool Text(JsonElement p, string name, out string value)
    {
        value = p.GetProperty(name).ValueKind == JsonValueKind.String ? p.GetProperty(name).GetString()! : string.Empty;
        return p.GetProperty(name).ValueKind == JsonValueKind.String;
    }
    private static bool Pressure(JsonElement value, out double pressure)
    {
        pressure = 0;
        return value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out pressure) && double.IsFinite(pressure) && pressure > 0 && pressure <= 100_000;
    }
}
