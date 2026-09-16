using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class WellScenarioParameters
{
    internal static bool Valid(JsonElement value, string task)
    {
        if (value.ValueKind == JsonValueKind.Null) return true;
        if (task != "nodal" || value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != 2) return false;
        return value.TryGetProperty("schemaVersion", out var schema) && schema.ValueKind == JsonValueKind.String
            && schema.GetString() == "pipesim-well-parameters/1"
            && value.TryGetProperty("reservoirPressurePsi", out var pressure) && pressure.ValueKind == JsonValueKind.Number
            && pressure.TryGetDouble(out var number) && double.IsFinite(number) && number > 0 && number <= 100000;
    }
}
