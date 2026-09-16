using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public static class WellInspectionReader
{
    public static WellDataInspection? Read(JsonElement envelope, string? status, string? modelKind)
    {
        if (status != "READY" || modelKind is not ("basic_gas" or "black_oil_liquid") ||
            !envelope.TryGetProperty("inspection", out var data) || data.ValueKind != JsonValueKind.Object ||
            data.EnumerateObject().Count() != 2 ||
            !data.TryGetProperty("schemaVersion", out var schema) || schema.ValueKind != JsonValueKind.String ||
            schema.GetString() != "pipesim-well-inspection/1" ||
            !data.TryGetProperty("reservoirPressure", out var pressure)) return null;
        if (pressure.ValueKind == JsonValueKind.Null) return new("pipesim-well-inspection/1", null);
        if (pressure.ValueKind != JsonValueKind.Object || pressure.EnumerateObject().Count() != 2 ||
            !pressure.TryGetProperty("unit", out var unit) || unit.ValueKind != JsonValueKind.String || unit.GetString() != "psia" ||
            !pressure.TryGetProperty("value", out var value) || value.ValueKind != JsonValueKind.Number ||
            !value.TryGetDouble(out var number) || !double.IsFinite(number) || number <= 0 ||
            Math.Abs(number - 1.2345e25) <= 1.2345e25 * 1e-12) return null;
        return new("pipesim-well-inspection/1", new(number, "psia"));
    }
}
