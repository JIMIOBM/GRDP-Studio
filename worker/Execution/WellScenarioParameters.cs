using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class WellScenarioParameters
{
    internal static bool Valid(JsonElement value, string task)
    {
        if (value.ValueKind == JsonValueKind.Null) return task is not "sensitivity" and not "system-analysis" and not "network-optimizer" and not "gas-lift-performance" and not "gas-lift-diagnostics" and not "vfp-tables" and not "trajectory";
        if (task == "esp-curves") return value.ValueKind == JsonValueKind.Object && value.EnumerateObject().Count() == 1 &&
            value.TryGetProperty("schemaVersion", out var espSchema) && espSchema.ValueKind == JsonValueKind.String &&
            espSchema.GetString() == "pipesim-esp-curves-parameters/1";
        if (task == "trajectory") return value.ValueKind == JsonValueKind.Object && value.EnumerateObject().Count() == 1 &&
            value.TryGetProperty("schemaVersion", out var trajectorySchema) && trajectorySchema.ValueKind == JsonValueKind.String &&
            trajectorySchema.GetString() == "pipesim-well-trajectory-parameters/1";
        if (task == "sensitivity") return SensitivityValid(value);
        if (task == "gas-lift-performance") return GasLiftPerformanceValid(value);
        if (task == "gas-lift-diagnostics") return GasLiftDiagnosticsValid(value);
        if (task == "vfp-tables") return VfpTablesValid(value);
        if (task == "network") return NetworkScenarioParameters.Valid(value) || NetworkChokeBeanSizeParameters.Valid(value);
        if (task == "network-optimizer") return NetworkOptimizerValid(value);
        if (task == "system-analysis") return SystemAnalysisParameters.Valid(value);
        if (task is ("profile" or "combined") && value.ValueKind == JsonValueKind.Object && value.EnumerateObject().Count() == 2
            && value.TryGetProperty("schemaVersion", out var profileSchema) && profileSchema.ValueKind == JsonValueKind.String
            && profileSchema.GetString() == "pipesim-well-profile-parameters/1")
        {
            return Number(value, "outletPressurePsi", 0, 100000)
                && value.GetProperty("outletPressurePsi").GetDouble() > 0;
        }
        if (task is not ("nodal" or "profile" or "combined") || value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != 2) return false;
        return value.TryGetProperty("schemaVersion", out var schema) && schema.ValueKind == JsonValueKind.String
            && schema.GetString() == "pipesim-well-parameters/1"
            && value.TryGetProperty("reservoirPressurePsi", out var pressure) && pressure.ValueKind == JsonValueKind.Number
            && pressure.TryGetDouble(out var number) && double.IsFinite(number) && number > 0 && number <= 100000;
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
            || !value.TryGetProperty("reservoirSimulator", out var simulator) || simulator.GetString() != "ECLIPSE"
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
        && value.GetString()!.IndexOfAny(['/', '\\', '\0']) < 0;

    private static bool Number(JsonElement value, string name, double lowerInclusive, double upperInclusive) =>
        value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.Number
        && item.TryGetDouble(out var number) && double.IsFinite(number)
        && number >= lowerInclusive && number <= upperInclusive;
}
