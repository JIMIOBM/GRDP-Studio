using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class NetworkFlowlineDiameterParameters
{
    internal static bool Valid(JsonElement value)
    {
        var fields = new[] { "schemaVersion", "baselineRunId", "flowline", "originalInnerDiameter", "unit", "targetInnerDiameter" };
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != fields.Length ||
            fields.Any(field => !value.TryGetProperty(field, out _)) ||
            !value.TryGetProperty("schemaVersion", out var schema) || schema.ValueKind != JsonValueKind.String ||
            schema.GetString() != "pipesim-network-flowline-diameter-parameters/1" ||
            !value.TryGetProperty("baselineRunId", out var baseline) || baseline.ValueKind != JsonValueKind.Number ||
            !baseline.TryGetInt64(out var baselineId) || baselineId <= 0 ||
            !SafeName(value.GetProperty("flowline")) || !SafeUnit(value.GetProperty("unit")) ||
            !Number(value, "originalInnerDiameter") || !Number(value, "targetInnerDiameter")) return false;

        var original = value.GetProperty("originalInnerDiameter").GetDouble();
        var target = value.GetProperty("targetInnerDiameter").GetDouble();
        return Math.Abs(target - original) > Math.Max(1e-12, Math.Abs(original) * 1e-12);
    }

    private static bool SafeName(JsonElement value) => value.ValueKind == JsonValueKind.String &&
        !string.IsNullOrWhiteSpace(value.GetString()) && value.GetString()!.Length <= 255 &&
        !value.GetString()!.Contains("..", StringComparison.Ordinal) &&
        value.GetString()!.IndexOfAny(['/', '\\', '\0']) < 0;

    private static bool SafeUnit(JsonElement value) => value.ValueKind == JsonValueKind.String &&
        !string.IsNullOrWhiteSpace(value.GetString()) && value.GetString()!.Length <= 32 &&
        !value.GetString()!.Any(char.IsControl);

    private static bool Number(JsonElement value, string name) =>
        value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.Number &&
        item.TryGetDouble(out var number) && double.IsFinite(number) && number > 0 && number <= 1000;
}
