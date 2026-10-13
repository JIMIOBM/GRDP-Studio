using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class WellScenarioParameters
{
    internal static bool Valid(JsonElement value, string task)
    {
        if (value.ValueKind == JsonValueKind.Null)
            return task is "nodal" or "profile" or "combined" or "network" or "esp-curves";
        // Check the common JSON shape before task-specific validators read strings.
        if (value.ValueKind != JsonValueKind.Object || !value.TryGetProperty("schemaVersion", out var schema)
            || schema.ValueKind != JsonValueKind.String) return false;

        return task switch
        {
            "esp-curves" => EspCurvesValid(value, schema.GetString()),
            "esp-parameter" => EspParameterValid(value),
            "trajectory" => TrajectoryValid(value, schema.GetString()),
            "profile" when schema.GetString() == "pipesim-well-tubing-diameter-parameters/1" => TubingDiameterValid(value),
            "nodal" or "profile" or "combined" => FlowParametersValid(value, task, schema.GetString()),
            "sensitivity" => SensitivityValid(value),
            "gas-lift-performance" => GasLiftPerformanceValid(value),
            "gas-lift-diagnostics" => GasLiftDiagnosticsValid(value),
            "vfp-tables" => VfpTablesValid(value),
            "network" => NetworkScenarioParameters.Valid(value) || NetworkChokeBeanSizeParameters.Valid(value)
                || NetworkFlowlineDiameterParameters.Valid(value) || NetworkConnectionParameters.Valid(value),
            "network-well-performance-curves" => NetworkWellPerformanceParameters.Valid(value),
            "network-optimizer" => NetworkOptimizerValid(value),
            "system-analysis" => SystemAnalysisParameters.Valid(value),
            _ => false
        };
    }

    private static bool EspCurvesValid(JsonElement value, string? schema) => schema switch
    {
        "pipesim-esp-curves-parameters/1" => HasExactlyProperties(value, "schemaVersion"),
        "pipesim-esp-curves-parameters/2" => HasExactlyProperties(value, "schemaVersion", "wellContext", "espContext")
            && SafeName(value.GetProperty("wellContext")) && SafeName(value.GetProperty("espContext")),
        _ => false
    };

    private static bool TrajectoryValid(JsonElement value, string? schema) => schema switch
    {
        "pipesim-well-trajectory-parameters/1" => HasExactlyProperties(value, "schemaVersion"),
        "pipesim-well-trajectory-parameters/2" => HasExactlyProperties(value, "schemaVersion", "wellContext")
            && SafeName(value.GetProperty("wellContext")),
        "pipesim-well-trajectory-parameters/3" => HasExactlyProperties(value, "schemaVersion", "wellContext", "stationIndex", "targetInclinationDeg")
            && SafeName(value.GetProperty("wellContext")) && Integer(value, "stationIndex", 1, 4094)
            && Number(value, "targetInclinationDeg", 0, 90),
        _ => false
    };

    private static bool FlowParametersValid(JsonElement value, string task, string? schema)
    {
        var field = schema switch
        {
            "pipesim-well-profile-parameters/1" when task is "profile" or "combined" => "outletPressurePsi",
            "pipesim-well-parameters/1" => "reservoirPressurePsi",
            _ => null
        };
        return field is not null && HasExactlyProperties(value, "schemaVersion", field)
            && Number(value, field, 0, 100000) && value.GetProperty(field).GetDouble() > 0;
    }

    private static bool TubingDiameterValid(JsonElement value)
    {
        if (!HasExactlyProperties(value, "schemaVersion", "baselineRunId", "wellContext", "tubingContext", "originalInnerDiameter", "unit", "targetInnerDiameter")
            || value.GetProperty("baselineRunId").ValueKind != JsonValueKind.Number
            || !value.GetProperty("baselineRunId").TryGetInt64(out var baselineRunId) || baselineRunId <= 0
            || !SafeName(value.GetProperty("wellContext")) || !SafeName(value.GetProperty("tubingContext"))
            || value.GetProperty("unit").ValueKind != JsonValueKind.String) return false;
        var unit = value.GetProperty("unit").GetString();
        if (string.IsNullOrWhiteSpace(unit) || unit.Length > 32 || unit.Any(char.IsControl)
            || !Number(value, "originalInnerDiameter", 0, 1000) || !Number(value, "targetInnerDiameter", 0, 1000)) return false;
        var original = value.GetProperty("originalInnerDiameter").GetDouble();
        var target = value.GetProperty("targetInnerDiameter").GetDouble();
        return original > 0 && target > 0 && Math.Abs(original - target) > Math.Max(1e-12, Math.Abs(original) * 1e-12);
    }

    private static bool SensitivityValid(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != 3
            || !value.TryGetProperty("schemaVersion", out var schema)
            || schema.ValueKind != JsonValueKind.String
            || schema.GetString() != "pipesim-well-sensitivity-parameters/1"
            || !value.TryGetProperty("targetVariable", out var target)
            || target.ValueKind != JsonValueKind.String
            || target.GetString() is not ("reservoirPressure" or "waterCut" or "gor" or "tubingInnerDiameter")
            || !value.TryGetProperty("values", out var values)
            || values.ValueKind != JsonValueKind.Array
            || values.GetArrayLength() is < 2 or > 12)
        {
            return false;
        }

        var previous = double.NegativeInfinity;
        foreach (var valueItem in values.EnumerateArray())
        {
            if (valueItem.ValueKind != JsonValueKind.Number || !valueItem.TryGetDouble(out var number)
                || !double.IsFinite(number) || number <= 0 || number <= previous)
            {
                return false;
            }
            previous = number;
        }

        return target.GetString() switch
        {
            "reservoirPressure" => previous <= 100000,
            "waterCut" => previous <= 100,
            "gor" => previous <= 1000000,
            "tubingInnerDiameter" => previous <= 100,
            _ => false
        };
    }

    private static bool GasLiftPerformanceValid(JsonElement value)
    {
        var fields = new[] { "schemaVersion", "producer", "outletPressurePsi", "surfaceInjectionTemperatureF",
            "targetInjectionRateMmscfd", "reservoirPressurePsi", "gorScfPerStb", "waterCutPercent", "valuesMmscfd" };
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != fields.Length
            || fields.Any(field => !value.TryGetProperty(field, out _))
            || !value.TryGetProperty("schemaVersion", out var schema) || schema.GetString() != "pipesim-gas-lift-performance-parameters/1"
            || !value.TryGetProperty("producer", out var producer) || !SafeName(producer)
            || !Number(value, "outletPressurePsi", 0, 100000)
            || !Number(value, "surfaceInjectionTemperatureF", -1000, 100000)
            || !Number(value, "targetInjectionRateMmscfd", 0, 100000)
            || !Number(value, "reservoirPressurePsi", 0, 100000)
            || !Number(value, "gorScfPerStb", 0, 1000000)
            || !Number(value, "waterCutPercent", 0, 100)
            || !value.TryGetProperty("valuesMmscfd", out var values) || values.ValueKind != JsonValueKind.Array
            || values.GetArrayLength() is < 2 or > 16)
        {
            return false;
        }
        var previous = double.NegativeInfinity;
        foreach (var item in values.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Number || !item.TryGetDouble(out var number)
                || !double.IsFinite(number) || number < 0 || number <= previous || number > 100000) return false;
            previous = number;
        }
        return true;
    }

    private static bool GasLiftDiagnosticsValid(JsonElement value)
    {
        var fields = new[] { "schemaVersion", "producer", "outletPressurePsi", "surfaceInjectionTemperatureF",
            "targetInjectionRateMmscfd", "reservoirPressurePsi", "gorScfPerStb", "waterCutPercent" };
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != fields.Length
            || fields.Any(field => !value.TryGetProperty(field, out _))
            || !value.TryGetProperty("schemaVersion", out var schema) || schema.GetString() != "pipesim-gas-lift-diagnostics-parameters/1"
            || !value.TryGetProperty("producer", out var producer) || !SafeName(producer)
            || !Number(value, "outletPressurePsi", 0, 100000)
            || !Number(value, "surfaceInjectionTemperatureF", -1000, 100000)
            || !Number(value, "targetInjectionRateMmscfd", 0, 100000)
            || !Number(value, "reservoirPressurePsi", 0, 100000)
            || !Number(value, "gorScfPerStb", 0, 1000000)
            || !Number(value, "waterCutPercent", 0, 100))
        {
            return false;
        }
        return true;
    }

    private static bool VfpTablesValid(JsonElement value)
    {
        var fields = new[] { "schemaVersion", "producer", "reservoirSimulator", "tableNumber", "includeTemperature",
            "bottomHoleDatumDepth", "liquidRatesStbPerDay", "outletPressuresPsi", "waterCutFraction",
            "gorMscfPerStb", "artificialLiftInjectionDpPsi" };
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != fields.Length
            || fields.Any(field => !value.TryGetProperty(field, out _))
            || !value.TryGetProperty("schemaVersion", out var schema) || schema.GetString() != "pipesim-vfp-tables-parameters/1"
            || !value.TryGetProperty("producer", out var producer) || !SafeName(producer)
            || !value.TryGetProperty("reservoirSimulator", out var simulator) || simulator.ValueKind != JsonValueKind.String || simulator.GetString() != "ECLIPSE"
            || !value.TryGetProperty("tableNumber", out var tableNumber) || tableNumber.ValueKind != JsonValueKind.Number
            || !tableNumber.TryGetInt32(out var number) || number < 1 || number > 100000
            || !value.TryGetProperty("includeTemperature", out var includeTemperature) || includeTemperature.ValueKind is not (JsonValueKind.True or JsonValueKind.False)
            || !Number(value, "bottomHoleDatumDepth", 0, 100000)) return false;
        return Increasing(value.GetProperty("liquidRatesStbPerDay"), 0, 1000000)
            && Increasing(value.GetProperty("outletPressuresPsi"), 0, 1000000)
            && Increasing(value.GetProperty("waterCutFraction"), 0, 1)
            && Increasing(value.GetProperty("gorMscfPerStb"), 0, 1000000)
            && Increasing(value.GetProperty("artificialLiftInjectionDpPsi"), 0, 1000000);
    }

    private static bool EspParameterValid(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Object || !value.TryGetProperty("schemaVersion", out var schema) || schema.ValueKind != JsonValueKind.String
            || !value.TryGetProperty("espContext", out var context) || !SafeName(context)
            || !value.TryGetProperty("parameter", out var parameter) || parameter.ValueKind != JsonValueKind.String) return false;
        return schema.GetString() switch
        {
            "pipesim-esp-parameter-parameters/1" =>
                HasExactlyProperties(value, "schemaVersion", "espContext", "parameter", "targetValueHz")
                && parameter.GetString() == "operatingFrequency"
                && Number(value, "targetValueHz", 0, 200)
                && value.GetProperty("targetValueHz").GetDouble() > 0,
            "pipesim-esp-parameter-parameters/2" =>
                HasExactlyProperties(value, "schemaVersion", "espContext", "parameter", "targetValue")
                && parameter.GetString() == "headFactor"
                && Number(value, "targetValue", 0, 2)
                && value.GetProperty("targetValue").GetDouble() > 0,
            "pipesim-esp-parameter-parameters/3" =>
                HasExactlyProperties(value, "schemaVersion", "wellContext", "espContext", "parameter", "targetValue")
                && SafeName(value.GetProperty("wellContext")) && SafeName(value.GetProperty("espContext"))
                && parameter.GetString() is "headFactor" or "operatingFrequency"
                && Number(value, "targetValue", 0, parameter.GetString() == "headFactor" ? 2 : 200)
                && value.GetProperty("targetValue").GetDouble() > 0,
            _ => false
        };
    }

    private static bool NetworkOptimizerValid(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != 2 ||
            !value.TryGetProperty("schemaVersion", out var schema) || schema.ValueKind != JsonValueKind.String ||
            !value.TryGetProperty("applyResults", out var applyResults)) return false;
        return schema.GetString() switch
        {
            "pipesim-network-optimizer-parameters/1" => applyResults.ValueKind == JsonValueKind.False,
            "pipesim-network-optimizer-parameters/2" => applyResults.ValueKind == JsonValueKind.True,
            _ => false
        };
    }

    private static bool Increasing(JsonElement values, double lowerInclusive, double upperInclusive)
    {
        if (values.ValueKind != JsonValueKind.Array || values.GetArrayLength() is < 1 or > 16) return false;
        var previous = double.NegativeInfinity;
        foreach (var item in values.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Number || !item.TryGetDouble(out var value)
                || !double.IsFinite(value) || value < lowerInclusive || value > upperInclusive || value <= previous) return false;
            previous = value;
        }
        return true;
    }

    private static bool SafeName(JsonElement value) => value.ValueKind == JsonValueKind.String
        && !string.IsNullOrWhiteSpace(value.GetString()) && value.GetString()!.Length <= 255
        && !value.GetString()!.Contains("..", StringComparison.Ordinal)
        && value.GetString()!.IndexOfAny(['/', '\\']) < 0
        && !value.GetString()!.Any(char.IsControl);

    private static bool HasExactlyProperties(JsonElement value, params string[] names) =>
        value.ValueKind == JsonValueKind.Object && value.EnumerateObject().Count() == names.Length
        && names.All(name => value.TryGetProperty(name, out _));

    private static bool Number(JsonElement value, string name, double lowerInclusive, double upperInclusive) =>
        value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.Number
        && item.TryGetDouble(out var number) && double.IsFinite(number)
        && number >= lowerInclusive && number <= upperInclusive;

    private static bool Integer(JsonElement value, string name, int lowerInclusive, int upperInclusive) =>
        value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.Number
        && item.TryGetInt32(out var number) && number >= lowerInclusive && number <= upperInclusive;
}
