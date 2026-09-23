using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class NetworkScenarioParameters
{
    private static readonly HashSet<string> BoundaryFields = new(StringComparer.Ordinal)
    {
        "node", "pressure", "temperature", "flowRateType", "gasFlowRate", "liquidFlowRate", "massFlowRate"
    };

    internal static bool Valid(JsonElement value)
    {
        if (value.ValueKind == JsonValueKind.Null) return true;
        if (value.ValueKind != JsonValueKind.Object
            || value.EnumerateObject().Count() != 2
            || !value.TryGetProperty("schemaVersion", out var schema)
            || schema.ValueKind != JsonValueKind.String
            || schema.GetString() != "pipesim-network-parameters/1"
            || !value.TryGetProperty("boundaries", out var boundaries)
            || boundaries.ValueKind != JsonValueKind.Array
            || boundaries.GetArrayLength() is < 1 or > 64)
        {
            return false;
        }

        var nodes = new HashSet<string>(StringComparer.Ordinal);
        foreach (var boundary in boundaries.EnumerateArray())
        {
            if (boundary.ValueKind != JsonValueKind.Object
                || boundary.EnumerateObject().Any(item => !BoundaryFields.Contains(item.Name))
                || !boundary.TryGetProperty("node", out var node)
                || node.ValueKind != JsonValueKind.String)
            {
                return false;
            }

            var nodeName = node.GetString();
            if (string.IsNullOrWhiteSpace(nodeName) || nodeName.Length > 255
                || nodeName.Contains("..", StringComparison.Ordinal)
                || nodeName.IndexOfAny(['/', '\\', '\0']) >= 0
                || !nodes.Add(nodeName)) return false;

            foreach (var field in new[] { "pressure", "temperature", "gasFlowRate", "liquidFlowRate", "massFlowRate" })
            {
                if (!boundary.TryGetProperty(field, out var number)) continue;
                if (number.ValueKind != JsonValueKind.Number || !number.TryGetDouble(out var parsed)
                    || !double.IsFinite(parsed) || (field == "temperature" ? parsed <= -1000 : parsed <= 0)
                    || parsed > (field == "pressure" ? 100000 : field == "temperature" ? 100000 : 1000000000)) return false;
            }

            if (boundary.TryGetProperty("flowRateType", out var flowType))
            {
                if (flowType.ValueKind != JsonValueKind.String) return false;
                var type = flowType.GetString();
                var rateField = type switch
                {
                    "GasFlowRate" => "gasFlowRate",
                    "LiquidFlowRate" => "liquidFlowRate",
                    "MassFlowRate" => "massFlowRate",
                    _ => null
                };
                if (rateField is null || !boundary.TryGetProperty(rateField, out _)) return false;
            }
        }

        return true;
    }
}
