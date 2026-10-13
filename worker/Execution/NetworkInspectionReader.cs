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
        return ReadData(data);
    }

    public static NetworkCapabilityInspection? ReadCapability(
        JsonElement envelope,
        string? status,
        string? modelKind,
        IReadOnlyList<string> modelStudies,
        IReadOnlyList<EclipsePackageFile> packageFiles)
    {
        if (status != "READY" || modelKind is not ("basic_gas" or "black_oil_liquid" or "legacy_well") ||
            !envelope.TryGetProperty("networkCapability", out var capability) || capability.ValueKind != JsonValueKind.Object ||
            capability.EnumerateObject().Count() != 3 ||
            !capability.TryGetProperty("schemaVersion", out var schema) || schema.ValueKind != JsonValueKind.String ||
            schema.GetString() != "pipesim-network-capability/1" ||
            !capability.TryGetProperty("studies", out var studiesElement) || studiesElement.ValueKind != JsonValueKind.Array ||
            studiesElement.GetArrayLength() is < 1 or > 64 ||
            !capability.TryGetProperty("networkInspection", out var networkElement) || networkElement.ValueKind != JsonValueKind.Object)
            return null;
        var allowedStudies = new HashSet<string>(modelStudies, StringComparer.Ordinal);
        var capabilityStudies = new List<string>();
        var seenStudies = new HashSet<string>(StringComparer.Ordinal);
        foreach (var item in studiesElement.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.String || !SafeText(item.GetString(), 255) ||
                !allowedStudies.Contains(item.GetString()!) || !seenStudies.Add(item.GetString()!)) return null;
            capabilityStudies.Add(item.GetString()!);
        }
        var networkInspection = ReadData(networkElement, packageFiles);
        if (networkInspection is null || networkInspection.SchemaVersion is not ("pipesim-network-inspection/3" or "pipesim-network-inspection/4" or "pipesim-network-inspection/5") ||
            !seenStudies.SetEquals(networkInspection.Studies.Select(item => item.Study))) return null;
        return new("pipesim-network-capability/1", capabilityStudies, networkInspection);
    }

    public static NetworkDataInspection? ReadData(JsonElement data, IReadOnlyList<EclipsePackageFile>? packageFiles = null)
    {
        if (data.ValueKind != JsonValueKind.Object) return null;
        try
        {
            var inspection = JsonSerializer.Deserialize<NetworkDataInspection>(data.GetRawText(), new JsonSerializerOptions
            {
                PropertyNameCaseInsensitive = true
            });
            if (inspection is not null && packageFiles is not null) inspection = inspection with { PackageFiles = packageFiles };
            if (inspection is null || inspection.SchemaVersion is not ("pipesim-network-inspection/1" or "pipesim-network-inspection/2" or "pipesim-network-inspection/3" or "pipesim-network-inspection/4" or "pipesim-network-inspection/5") ||
                (inspection.SchemaVersion.EndsWith("/2", StringComparison.Ordinal) &&
                 (inspection.PackageFiles is null || !PipesimPackageIntegrity.IsValidManifest(inspection.PackageFiles))) ||
                (inspection.SchemaVersion.EndsWith("/3", StringComparison.Ordinal) &&
                 (inspection.PackageFiles is null || !PipesimPackageIntegrity.IsValidManifest(inspection.PackageFiles) || inspection.Chokes is null || inspection.Chokes.Count > 128)) ||
                (inspection.SchemaVersion.EndsWith("/4", StringComparison.Ordinal) &&
                 (inspection.PackageFiles is null || !PipesimPackageIntegrity.IsValidManifest(inspection.PackageFiles) || inspection.Chokes is null || inspection.Chokes.Count > 128 ||
                  !ValidContextList(inspection.WellContexts) || !ValidContextList(inspection.FlowlineContexts))) ||
                (inspection.SchemaVersion.EndsWith("/5", StringComparison.Ordinal) &&
                 (inspection.PackageFiles is null || !PipesimPackageIntegrity.IsValidManifest(inspection.PackageFiles) || inspection.Chokes is null || inspection.Chokes.Count > 128 ||
                  !ValidContextList(inspection.WellContexts) || !ValidContextList(inspection.FlowlineContexts) || !ValidFlowlines(inspection.Flowlines))) ||
                inspection.Studies.Count is < 1 or > 64)
                return null;
            if (inspection.SchemaVersion.EndsWith("/5", StringComparison.Ordinal) &&
                !inspection.Flowlines!.Select(flowline => flowline.Name).ToHashSet(StringComparer.Ordinal)
                    .SetEquals(inspection.FlowlineContexts!)) return null;
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
            if (inspection.SchemaVersion.EndsWith("/3", StringComparison.Ordinal) || inspection.SchemaVersion.EndsWith("/4", StringComparison.Ordinal) || inspection.SchemaVersion.EndsWith("/5", StringComparison.Ordinal))
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

    private static bool SafeText(string? value, int maxLength, bool allowEmpty) =>
        allowEmpty && value is { Length: 0 } || SafeText(value, maxLength);

    private static bool ValidContextList(IReadOnlyList<string>? contexts)
    {
        if (contexts is null || contexts.Count > 128) return false;
        var seen = new HashSet<string>(StringComparer.Ordinal);
        return contexts.All(context => SafeText(context, 255) && !context.Contains("..", StringComparison.Ordinal)
            && !context.Contains('/') && !context.Contains('\\') && seen.Add(context));
    }

    private static bool ValidFlowlines(IReadOnlyList<NetworkFlowlineInspection>? flowlines)
    {
        if (flowlines is null || flowlines.Count > 128) return false;
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var flowline in flowlines)
        {
            if (!SafeText(flowline.Name, 255) || flowline.Name.Contains("..", StringComparison.Ordinal) ||
                flowline.Name.Contains('/') || flowline.Name.Contains('\\') || !SafeText(flowline.Unit, 32, allowEmpty: true) ||
                !seen.Add(flowline.Name) || flowline.InnerDiameter is { } diameter &&
                (!double.IsFinite(diameter) || diameter <= 0 || diameter > 1000) ||
                flowline.InnerDiameter is null && flowline.Unit.Length != 0) return false;
        }
        return true;
    }

    private static bool ValidNumber(double? value, double lowerExclusive, double upperInclusive) =>
        value is null || double.IsFinite(value.Value) && value.Value > lowerExclusive && value.Value <= upperInclusive;
}
