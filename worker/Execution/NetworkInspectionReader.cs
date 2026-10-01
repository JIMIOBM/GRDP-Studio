using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public static class NetworkInspectionReader
{
    private static readonly HashSet<string> FlowRateTypes = ["GasFlowRate", "LiquidFlowRate", "MassFlowRate"];

    public static NetworkDataInspection? Read(JsonElement envelope, string? status, string? modelKind)
    {
        if (status != "READY" || modelKind != "network" ||
            !envelope.TryGetProperty("inspection", out var data) || data.ValueKind != JsonValueKind.Object)
            return null;
        try
        {
            var inspection = JsonSerializer.Deserialize<NetworkDataInspection>(data.GetRawText(), new JsonSerializerOptions
            {
                PropertyNameCaseInsensitive = true
            });
            if (inspection is null || inspection.SchemaVersion is not ("pipesim-network-inspection/1" or "pipesim-network-inspection/2" or "pipesim-network-inspection/3") ||
                (inspection.SchemaVersion.EndsWith("/2", StringComparison.Ordinal) &&
                 (inspection.PackageFiles is null || !PipesimPackageIntegrity.IsValidManifest(inspection.PackageFiles))) ||
                (inspection.SchemaVersion.EndsWith("/3", StringComparison.Ordinal) &&
                 (inspection.PackageFiles is null || !PipesimPackageIntegrity.IsValidManifest(inspection.PackageFiles) || inspection.Chokes is null || inspection.Chokes.Count > 128)) ||
                inspection.Studies.Count is < 1 or > 64)
                return null;
            foreach (var study in inspection.Studies)
            {
                if (!SafeText(study.Study, 255) || study.Boundaries.Count is < 1 or > 256 ||
                    study.Boundaries.Select(item => item.Node).Distinct(StringComparer.Ordinal).Count() != study.Boundaries.Count)
                    return null;
                foreach (var boundary in study.Boundaries)
                {
                    if (!SafeText(boundary.Node, 255) || !SafeText(boundary.BoundaryNodeType, 64) ||
                        (boundary.FlowRateType is not null && !FlowRateTypes.Contains(boundary.FlowRateType)) ||
                        !ValidNumber(boundary.Pressure, 0, 100000) || !ValidNumber(boundary.Temperature, -1000, 100000) ||
                        !ValidNumber(boundary.GasFlowRate, 0, 1000000000) || !ValidNumber(boundary.LiquidFlowRate, 0, 1000000000) ||
                        !ValidNumber(boundary.MassFlowRate, 0, 1000000000))
                        return null;
                }
            }
            if (inspection.SchemaVersion.EndsWith("/3", StringComparison.Ordinal))
            {
                var chokeNames = new HashSet<string>(StringComparer.Ordinal);
                foreach (var choke in inspection.Chokes!)
                {
                    if (!SafeText(choke.Name, 255) || !SafeText(choke.Unit, 32) ||
                        !double.IsFinite(choke.BeanSize) || choke.BeanSize <= 0 || choke.BeanSize > 1_000_000 ||
                        !chokeNames.Add(choke.Name)) return null;
                }
            }
            return inspection;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static bool SafeText(string? value, int maxLength) =>
        !string.IsNullOrWhiteSpace(value) && value.Length <= maxLength &&
        value.All(character => !char.IsControl(character));

    private static bool ValidNumber(double? value, double lowerExclusive, double upperInclusive) =>
        value is null || double.IsFinite(value.Value) && value.Value > lowerExclusive && value.Value <= upperInclusive;
}
