using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed partial record EclipseScheduleParameters(
    long BaselineRunId,
    string Well,
    DateOnly? Date,
    string Status,
    string? ControlMode = null,
    double? TargetOilRate = null,
    string? InjectionType = null,
    double? TargetInjectionRate = null,
    string SchemaVersion = "eclipse-schedule-parameters/1",
    string? Phase = null)
{
    internal const string Schema = "eclipse-schedule-parameters/1";
    internal const string ControlSchema = "eclipse-schedule-parameters/2";
    internal const string InjectionSchema = "eclipse-schedule-parameters/3";
    internal const string ProductionSchema = "eclipse-schedule-parameters/4";

    internal static bool TryParse(JsonElement value, out EclipseScheduleParameters? parameters)
    {
        parameters = null;
        if (value.ValueKind != JsonValueKind.Object
            || !value.TryGetProperty("schemaVersion", out var schema)
            || schema.ValueKind != JsonValueKind.String
            || !value.TryGetProperty("baselineRunId", out var baseline)
            || baseline.ValueKind != JsonValueKind.Number || !baseline.TryGetInt64(out var baselineId) || baselineId <= 0
            || !value.TryGetProperty("well", out var well) || well.ValueKind != JsonValueKind.String
            || !WellName().IsMatch(well.GetString() ?? string.Empty)) return false;
        var schemaName = schema.GetString();
        var names = value.EnumerateObject().Select(property => property.Name).ToHashSet(StringComparer.Ordinal);
        if (schemaName == Schema && !names.SetEquals(["schemaVersion", "baselineRunId", "well", "date", "status"])) return false;
        if (schemaName == ControlSchema && !names.SetEquals(["schemaVersion", "baselineRunId", "well", "date", "status", "controlMode", "targetOilRate"])) return false;
        if (schemaName == InjectionSchema && !names.SetEquals(["schemaVersion", "baselineRunId", "well", "date", "injectionType", "controlMode", "targetInjectionRate"])) return false;
        if (schemaName == ProductionSchema && !names.SetEquals(["schemaVersion", "baselineRunId", "well", "phase", "status", "controlMode", "targetOilRate"])) return false;
        if (schemaName is not (Schema or ControlSchema or InjectionSchema or ProductionSchema)) return false;
        DateOnly? parsedDate = null;
        if (schemaName != ProductionSchema)
        {
            if (!value.TryGetProperty("date", out var date) || date.ValueKind != JsonValueKind.String
                || !DateOnly.TryParseExact(date.GetString(), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var parsed)) return false;
            parsedDate = parsed;
        }
        string? statusValue = null;
        if (schemaName is not (InjectionSchema or ProductionSchema)
            && (!value.TryGetProperty("status", out var status) || status.ValueKind != JsonValueKind.String || status.GetString() is not ("OPEN" or "SHUT"))) return false;
        if (schemaName is not (InjectionSchema or ProductionSchema)) statusValue = value.GetProperty("status").GetString();
        string? controlMode = null;
        double? targetOilRate = null;
        string? injectionType = null;
        double? targetInjectionRate = null;
        if (schemaName == InjectionSchema
            && (!value.TryGetProperty("injectionType", out var injection) || injection.ValueKind != JsonValueKind.String || !InjectionTypePattern().IsMatch(injection.GetString() ?? string.Empty)
                || !value.TryGetProperty("controlMode", out var injectionMode) || injectionMode.ValueKind != JsonValueKind.String || injectionMode.GetString() != "RATE"
                || !value.TryGetProperty("targetInjectionRate", out var injectionTarget) || injectionTarget.ValueKind != JsonValueKind.Number
                || !injectionTarget.TryGetDouble(out var parsedInjectionRate) || !double.IsFinite(parsedInjectionRate) || parsedInjectionRate <= 0 || parsedInjectionRate > 100000000)) return false;
        if (schemaName == ControlSchema
            && (!value.TryGetProperty("controlMode", out var mode) || mode.ValueKind != JsonValueKind.String || mode.GetString() != "ORAT"
                || !value.TryGetProperty("targetOilRate", out var target) || target.ValueKind != JsonValueKind.Number
                || !target.TryGetDouble(out var parsedTarget) || !double.IsFinite(parsedTarget) || parsedTarget <= 0 || parsedTarget > 100000000)) return false;
        if (schemaName == ProductionSchema
            && (!value.TryGetProperty("phase", out var phase) || phase.ValueKind != JsonValueKind.String || phase.GetString() != "FORECAST_INITIAL"
                || !value.TryGetProperty("status", out var productionStatus) || productionStatus.ValueKind != JsonValueKind.String || productionStatus.GetString() is not ("OPEN" or "SHUT")
                || !value.TryGetProperty("controlMode", out var productionMode) || productionMode.ValueKind != JsonValueKind.String || productionMode.GetString() != "ORAT"
                || !value.TryGetProperty("targetOilRate", out var productionTarget) || productionTarget.ValueKind != JsonValueKind.Number
                || !productionTarget.TryGetDouble(out var parsedProductionRate) || !double.IsFinite(parsedProductionRate) || parsedProductionRate <= 0 || parsedProductionRate > 100000000)) return false;
        if (schemaName == ControlSchema)
        {
            controlMode = "ORAT";
            targetOilRate = value.GetProperty("targetOilRate").GetDouble();
        }
        if (schemaName == InjectionSchema)
        {
            controlMode = "RATE";
            injectionType = value.GetProperty("injectionType").GetString();
            targetInjectionRate = value.GetProperty("targetInjectionRate").GetDouble();
        }
        if (schemaName == ProductionSchema)
        {
            controlMode = "ORAT";
            targetOilRate = value.GetProperty("targetOilRate").GetDouble();
            statusValue = value.GetProperty("status").GetString();
        }
        parameters = new(baselineId, well.GetString()!, parsedDate, schemaName is InjectionSchema ? "OPEN" : statusValue!, controlMode, targetOilRate, injectionType, targetInjectionRate, schemaName!, schemaName == ProductionSchema ? "FORECAST_INITIAL" : null);
        return true;
    }

    [GeneratedRegex("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$", RegexOptions.CultureInvariant)]
    private static partial Regex WellName();
    [GeneratedRegex("^[A-Za-z][A-Za-z0-9_ -]{0,31}$", RegexOptions.CultureInvariant)]
    private static partial Regex InjectionTypePattern();
}
