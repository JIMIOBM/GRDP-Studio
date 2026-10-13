using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public static class GasLiftCapabilityReader
{
    // Full well/Study coverage is required, so a missing tuple is never support.
    public static IReadOnlyList<GasLiftCapabilityInspection>? Read(JsonElement data,
        IReadOnlyList<WellEspContextInspection> wells, IReadOnlyList<string> studies)
    {
        if (studies.Count is 0 or > 64 || studies.Distinct(StringComparer.Ordinal).Count() != studies.Count ||
            data.ValueKind != JsonValueKind.Array || data.GetArrayLength() != wells.Count * studies.Count) return null;
        var result = new List<GasLiftCapabilityInspection>();
        var seen = new HashSet<(string, string)>();
        foreach (var item in data.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object || item.EnumerateObject().Count() != 6 ||
                !Text(item, "well", out var well) || !Text(item, "study", out var study) ||
                !Text(item, "state", out var state) || !Text(item, "reason", out var reason) ||
                !Flag(item, "useGasliftValve", out var valve) || !Flag(item, "targetInjectionRateAvailable", out var axis) ||
                !wells.Any(value => value.Context == well) || !studies.Contains(study, StringComparer.Ordinal) ||
                !seen.Add((well, study))) return null;
            var consistent = state switch
            {
                "supported" => reason == "GAS_LIFT_VALVE_SUPPORTED" && valve == true && axis == true,
                "unsupported" => valve == false && axis.HasValue && reason == "GAS_LIFT_VALVE_REQUIRED" ||
                                 valve == true && axis == false && reason == "GAS_LIFT_TARGET_AXIS_UNAVAILABLE",
                "unknown" => reason == "GAS_LIFT_CAPABILITY_UNKNOWN" && (!valve.HasValue || !axis.HasValue),
                _ => false
            };
            if (!consistent) return null;
            result.Add(new(well, study, state, reason, valve, axis));
        }
        return result;
    }

    private static bool Text(JsonElement item, string name, out string value)
    {
        value = "";
        if (!item.TryGetProperty(name, out var field) || field.ValueKind != JsonValueKind.String) return false;
        value = field.GetString()!;
        return !string.IsNullOrWhiteSpace(value) && value.Length <= 255 && !value.Any(char.IsControl);
    }

    private static bool Flag(JsonElement item, string name, out bool? value)
    {
        value = null;
        if (!item.TryGetProperty(name, out var field)) return false;
        if (field.ValueKind == JsonValueKind.Null) return true;
        if (field.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) return false;
        value = field.GetBoolean();
        return true;
    }
}
