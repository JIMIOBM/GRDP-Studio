using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public static class WellInspectionReader
{
    public static WellDataInspection? Read(JsonElement envelope, string? status, string? modelKind)
    {
        if (status != "READY" || modelKind is not ("basic_gas" or "black_oil_liquid" or "legacy_well") ||
            !envelope.TryGetProperty("inspection", out var data) || data.ValueKind != JsonValueKind.Object ||
            data.EnumerateObject().Count() is not (2 or 3) ||
            !data.TryGetProperty("schemaVersion", out var schema) || schema.ValueKind != JsonValueKind.String ||
            schema.GetString() is not ("pipesim-well-inspection/1" or "pipesim-well-inspection/2") ||
            !data.TryGetProperty("reservoirPressure", out var pressure)) return null;
        var schemaVersion = schema.GetString()!;
        IReadOnlyList<EclipsePackageFile>? packageFiles = null;
        if (schemaVersion.EndsWith("/2", StringComparison.Ordinal))
        {
            if (!data.TryGetProperty("packageFiles", out var packageFilesElement) ||
                packageFilesElement.ValueKind != JsonValueKind.Array) return null;
            try
            {
                packageFiles = JsonSerializer.Deserialize<IReadOnlyList<EclipsePackageFile>>(packageFilesElement.GetRawText(), new JsonSerializerOptions
                {
                    PropertyNameCaseInsensitive = true
                });
            }
            catch (JsonException) { return null; }
            if (packageFiles is null || !PipesimPackageIntegrity.IsValidManifest(packageFiles)) return null;
        }
        if (pressure.ValueKind == JsonValueKind.Null) return new(schemaVersion, null, packageFiles);
        if (pressure.ValueKind != JsonValueKind.Object || pressure.EnumerateObject().Count() != 2 ||
            !pressure.TryGetProperty("unit", out var unit) || unit.ValueKind != JsonValueKind.String || unit.GetString() != "psia" ||
            !pressure.TryGetProperty("value", out var value) || value.ValueKind != JsonValueKind.Number ||
            !value.TryGetDouble(out var number) || !double.IsFinite(number) || number <= 0 ||
            Math.Abs(number - 1.2345e25) <= 1.2345e25 * 1e-12) return null;
        return new(schemaVersion, new(number, "psia"), packageFiles);
    }
}
