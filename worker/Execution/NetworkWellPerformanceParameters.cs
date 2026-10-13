using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class NetworkWellPerformanceParameters
{
    internal static bool Valid(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != 5
            || !value.TryGetProperty("schemaVersion", out var schema) || schema.GetString() != "pipesim-network-well-performance-parameters/1"
            || !value.TryGetProperty("producerContext", out var producer) || !SafeName(producer)
            || !value.TryGetProperty("branchTerminator", out var branch) || !SafeName(branch)
            || !value.TryGetProperty("sensitivityUnit", out var unit) || unit.GetString() != "in"
            || !value.TryGetProperty("sensitivityValues", out var values) || values.ValueKind != JsonValueKind.Array
            || values.GetArrayLength() is < 2 or > 8) return false;

        var previous = double.NegativeInfinity;
        foreach (var item in values.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Number || !item.TryGetDouble(out var number)
                || !double.IsFinite(number) || number <= 0 || number > 1000 || number <= previous) return false;
            previous = number;
        }
        return true;
    }

    private static bool SafeName(JsonElement value) => value.ValueKind == JsonValueKind.String
        && !string.IsNullOrWhiteSpace(value.GetString()) && value.GetString()!.Length <= 255
        && !value.GetString()!.Contains("..", StringComparison.Ordinal)
        && value.GetString()!.IndexOfAny(['/', '\\']) < 0
        && !value.GetString()!.Any(char.IsControl);
}
