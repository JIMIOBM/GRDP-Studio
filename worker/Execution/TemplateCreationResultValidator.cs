using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public static class TemplateCreationResultValidator
{
    public static bool IsValid(JsonElement result, TemplateCreationRequest request)
    {
        if (result.ValueKind != JsonValueKind.Object || !Text(result, "schemaVersion", "pipesim-template-profile-preflight/1") ||
            !Text(result, "template", "Simple vertical") || !Text(result, "well", request.Well) ||
            !Text(result, "nativeState", "Completed") || !Text(result, "geometryOrigin", "official-template-inherited") ||
            !Text(result, "fluidOrigin", "explicit-scalar-inputs-with-installed-SDK-default-correlations") ||
            !result.TryGetProperty("calculationVerified", out var completed) || completed.ValueKind != JsonValueKind.True ||
            !result.TryGetProperty("platformVerified", out var platform) || platform.ValueKind != JsonValueKind.False ||
            !result.TryGetProperty("modelDiagnostics", out var issues) || issues.ValueKind != JsonValueKind.Array || issues.GetArrayLength() != 0 ||
            !result.TryGetProperty("inputs", out var inputs)) return false;
        var echoed = request with { Inputs = inputs };
        if (!TemplateCreationParameters.IsValid(echoed) || TemplateCreationParameters.Fingerprint(echoed) != TemplateCreationParameters.Fingerprint(request)) return false;
        if (!result.TryGetProperty("readback", out var readback) || readback.ValueKind != JsonValueKind.Object ||
            !readback.TryGetProperty("fluid", out var fluid) || fluid.ValueKind != JsonValueKind.Object ||
            !readback.TryGetProperty("completion", out var completion) || completion.ValueKind != JsonValueKind.Object) return false;
        foreach (var (actual, expected) in new[] { ("API", "oilApi"), ("GasSpecificGravity", "gasSpecificGravity"),
                     ("WaterSpecificGravity", "waterSpecificGravity"), ("GOR", "gorScfStb"), ("WaterCut", "waterCutPercent") })
            if (!Matches(fluid, actual, request.Inputs.GetProperty(expected).GetDouble())) return false;
        if (!Matches(completion, "ReservoirPressure", request.Inputs.GetProperty("reservoirPressurePsia").GetDouble()) ||
            !Matches(completion, "ReservoirTemperature", request.Inputs.GetProperty("reservoirTemperatureDegF").GetDouble())) return false;
        if (!result.TryGetProperty("conditionsReadback", out var conditions) || conditions.ValueKind != JsonValueKind.Object ||
            !Text(conditions, "Producer", request.Well) || !Text(conditions, "CalculationVariableType", "calcInletPressure") ||
            !Text(conditions, "FlowRateType", "LiquidFlowRate") ||
            !Matches(conditions, "OutletPressure", request.Inputs.GetProperty("outletPressurePsia").GetDouble()) ||
            !Matches(conditions, "LiquidFlowRate", request.Inputs.GetProperty("liquidRateStbDay").GetDouble())) return false;
        if (!result.TryGetProperty("profile", out var profile) || profile.ValueKind != JsonValueKind.Array || profile.GetArrayLength() is < 1 or > 4096) return false;
        foreach (var point in profile.EnumerateArray())
        {
            if (point.ValueKind != JsonValueKind.Object) return false;
            foreach (var key in new[] { "depth", "pressure", "temperature" })
                if (!Finite(point, key, out _)) return false;
        }
        return true;
    }

    private static bool Text(JsonElement value, string key, string expected) =>
        value.TryGetProperty(key, out var item) && item.ValueKind == JsonValueKind.String && item.GetString() == expected;
    private static bool Matches(JsonElement value, string key, double expected) =>
        Finite(value, key, out var actual) && Math.Abs(actual - expected) <= 1e-9 * Math.Max(1, Math.Abs(expected));
    private static bool Finite(JsonElement value, string key, out double number)
    {
        number = 0;
        return value.TryGetProperty(key, out var item) && item.ValueKind == JsonValueKind.Number && item.TryGetDouble(out number) && double.IsFinite(number);
    }
}
