using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed partial record EclipseGridFieldParameters(
    long BaselineRunId,
    string FieldFile,
    string FieldArtifactSha256,
    int Nx,
    int Ny,
    int Nz,
    int I,
    int J,
    int K,
    double OriginalValue,
    double Multiplier)
{
    internal const string Schema = "eclipse-grid-field-parameters/1";

    internal static bool TryParse(JsonElement value, out EclipseGridFieldParameters? parameters)
    {
        parameters = null;
        if (value.ValueKind != JsonValueKind.Object
            || !value.TryGetProperty("schemaVersion", out var schema)
            || schema.ValueKind != JsonValueKind.String || schema.GetString() != Schema
            || !value.TryGetProperty("baselineRunId", out var baseline) || baseline.ValueKind != JsonValueKind.Number || !baseline.TryGetInt64(out var baselineId) || baselineId <= 0
            || !value.TryGetProperty("fieldFile", out var fieldFile) || fieldFile.ValueKind != JsonValueKind.String
            || !FieldFileName().IsMatch(fieldFile.GetString() ?? string.Empty)
            || !value.TryGetProperty("fieldArtifactSha256", out var sha) || sha.ValueKind != JsonValueKind.String
            || !Sha256().IsMatch(sha.GetString() ?? string.Empty)
            || !TryDimension(value, "nx", out var nx) || !TryDimension(value, "ny", out var ny) || !TryDimension(value, "nz", out var nz)
            || !TryIndex(value, "i", out var i) || !TryIndex(value, "j", out var j) || !TryIndex(value, "k", out var k)
            || i > nx || j > ny || k > nz
            || !TryFinite(value, "originalValue", out var original) || original <= 0
            || !TryFinite(value, "multiplier", out var multiplier) || multiplier is < 0.000001 or > 100 || multiplier == 1
            || !double.IsFinite(original * multiplier))
            return false;

        var names = value.EnumerateObject().Select(property => property.Name).ToHashSet(StringComparer.Ordinal);
        if (!names.SetEquals(["schemaVersion", "baselineRunId", "fieldFile", "fieldArtifactSha256", "nx", "ny", "nz", "i", "j", "k", "originalValue", "multiplier"]))
            return false;

        parameters = new(baselineId, fieldFile.GetString()!, sha.GetString()!, nx, ny, nz, i, j, k, original, multiplier);
        return true;
    }

    private static bool TryDimension(JsonElement value, string name, out int number)
    {
        number = 0;
        return value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.Number
            && item.TryGetInt32(out number) && number is > 0 and <= 2_000_000;
    }

    private static bool TryIndex(JsonElement value, string name, out int number)
    {
        number = 0;
        return value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.Number
            && item.TryGetInt32(out number) && number is > 0 and <= 2_000_000;
    }

    private static bool TryFinite(JsonElement value, string name, out double number)
    {
        number = 0;
        return value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.Number
            && item.TryGetDouble(out number) && double.IsFinite(number);
    }

    [GeneratedRegex("^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.INIT$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex FieldFileName();
    [GeneratedRegex("^[0-9a-f]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex Sha256();
}
