using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record TemplateCreationRequest(Guid RequestId, string Well, JsonElement Inputs);

public static partial class TemplateCreationParameters
{
    private static readonly Dictionary<string, (double Min, double Max, bool Inclusive)> Ranges = new()
    {
        ["oilApi"] = (0, 100, false), ["gasSpecificGravity"] = (0, 10, false),
        ["waterSpecificGravity"] = (0, 10, false), ["gorScfStb"] = (0, 1_000_000, true),
        ["waterCutPercent"] = (0, 100, true), ["reservoirPressurePsia"] = (0, 100_000, false),
        ["reservoirTemperatureDegF"] = (-459.67, 1000, false),
        ["outletPressurePsia"] = (0, 100_000, false), ["liquidRateStbDay"] = (0, 1_000_000, false)
    };

    public static bool IsValid(TemplateCreationRequest request)
    {
        if (request.RequestId == Guid.Empty || request.Well is null || !WellName().IsMatch(request.Well)) return false;
        var input = request.Inputs;
        if (input.ValueKind != JsonValueKind.Object || input.EnumerateObject().Count() != 12) return false;
        var names = new HashSet<string>(StringComparer.Ordinal);
        foreach (var property in input.EnumerateObject())
        {
            if (!names.Add(property.Name)) return false;
            if (Ranges.TryGetValue(property.Name, out var range))
            {
                if (!property.Value.TryGetDoubleSafe(out var number) || !double.IsFinite(number) || number > range.Max ||
                    (range.Inclusive ? number < range.Min : number <= range.Min)) return false;
            }
            else if (property.Name == "schemaVersion")
            {
                if (property.Value.ValueKind != JsonValueKind.String || property.Value.GetString() != "pipesim-template-profile-inputs/1") return false;
            }
            else if (property.Name == "unitsSystem")
            {
                if (property.Value.ValueKind != JsonValueKind.String || property.Value.GetString() != "PIPESIM_FIELD") return false;
            }
            else if (property.Name == "study")
            {
                if (property.Value.ValueKind != JsonValueKind.String || property.Value.GetString() is not { } study ||
                    string.IsNullOrWhiteSpace(study) || study.Length > 64 || study.Any(char.IsControl)) return false;
            }
            else return false;
        }
        return names.IsSupersetOf(Ranges.Keys);
    }

    public static string Fingerprint(TemplateCreationRequest request)
    {
        if (!IsValid(request)) throw new ArgumentException("Invalid template creation contract.");
        var canonical = new SortedDictionary<string, object>(StringComparer.Ordinal) { ["well"] = request.Well };
        foreach (var property in request.Inputs.EnumerateObject())
            canonical[property.Name] = property.Value.ValueKind == JsonValueKind.Number
                ? property.Value.GetDouble() : property.Value.GetString()!;
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(canonical)))).ToLowerInvariant();
    }

    private static bool TryGetDoubleSafe(this JsonElement value, out double number)
    {
        number = 0;
        return value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out number);
    }

    [GeneratedRegex("\\A[A-Za-z][A-Za-z0-9_-]{0,63}\\z", RegexOptions.CultureInvariant)]
    private static partial Regex WellName();
}
