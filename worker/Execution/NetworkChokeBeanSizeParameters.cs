using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class NetworkChokeBeanSizeParameters
{
    internal static bool Valid(JsonElement value)
    {
        var fields = new[] { "schemaVersion", "baselineRunId", "choke", "originalBeanSize", "targetBeanSize" };
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != fields.Length ||
            fields.Any(field => !value.TryGetProperty(field, out _)) ||
            !value.TryGetProperty("schemaVersion", out var schema) || schema.ValueKind != JsonValueKind.String ||
            schema.GetString() != "pipesim-network-choke-bean-size-parameters/1" ||
            !value.TryGetProperty("baselineRunId", out var baseline) || baseline.ValueKind != JsonValueKind.Number ||
            !baseline.TryGetInt64(out var baselineId) || baselineId <= 0 ||
            !SafeName(value.GetProperty("choke")) ||
            !Number(value, "originalBeanSize") || !Number(value, "targetBeanSize")) return false;

        var original = value.GetProperty("originalBeanSize").GetDouble();
        var target = value.GetProperty("targetBeanSize").GetDouble();
        return Math.Abs(target - original) > Math.Max(1e-12, Math.Abs(original) * 1e-12);
    }

    private static bool SafeName(JsonElement value) => value.ValueKind == JsonValueKind.String &&
        !string.IsNullOrWhiteSpace(value.GetString()) && value.GetString()!.Length <= 255 &&
        !value.GetString()!.Contains("..", StringComparison.Ordinal) &&
        value.GetString()!.IndexOfAny(['/', '\\', '\0']) < 0;

    private static bool Number(JsonElement value, string name) =>
        value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.Number &&
        item.TryGetDouble(out var number) && double.IsFinite(number) && number > 0 && number <= 1_000_000;
}
