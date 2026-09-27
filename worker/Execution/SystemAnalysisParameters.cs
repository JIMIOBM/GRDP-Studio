using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class SystemAnalysisParameters
{
    internal static bool Valid(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Object
            || value.EnumerateObject().Count() != 6
            || !Text(value, "schemaVersion", "pipesim-system-analysis-parameters/1")
            || !Text(value, "producer", "Well")
            || !Text(value, "scanVariable", "liquidFlowRate")
            || !value.TryGetProperty("branchTerminator", out var branch)
            || !SafeName(branch)
            || !Number(value, "outletPressurePsi", 0, 100000)
            || !value.TryGetProperty("values", out var values)
            || values.ValueKind != JsonValueKind.Array
            || values.GetArrayLength() is < 2 or > 8)
        {
            return false;
        }

        var previous = double.NegativeInfinity;
        foreach (var item in values.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Number || !item.TryGetDouble(out var number)
                || !double.IsFinite(number) || number <= 0 || number <= previous || number > 1000000000)
            {
                return false;
            }
            previous = number;
        }
        return true;
    }

    private static bool Text(JsonElement value, string name, string expected) =>
        value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.String && item.GetString() == expected;

    private static bool SafeName(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.String) return false;
        var text = value.GetString();
        return !string.IsNullOrWhiteSpace(text) && text.Length <= 255
            && !text.Contains("..", StringComparison.Ordinal)
            && text.IndexOfAny(['/', '\\', '\0']) < 0;
    }

    private static bool Number(JsonElement value, string name, double lowerExclusive, double upperInclusive) =>
        value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.Number
        && item.TryGetDouble(out var number) && double.IsFinite(number)
        && number > lowerExclusive && number <= upperInclusive;
}
