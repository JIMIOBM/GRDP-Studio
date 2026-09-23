using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed partial record EclipseCompletionFactorParameters(
    long BaselineRunId,
    string Well,
    string SourceFile,
    int LineNumber,
    string I,
    string J,
    string K1,
    string K2,
    double OriginalConnectionFactor,
    double TargetConnectionFactor)
{
    internal const string Schema = "eclipse-completion-factor-parameters/1";

    internal static bool TryParse(JsonElement value, out EclipseCompletionFactorParameters? parameters)
    {
        parameters = null;
        if (value.ValueKind != JsonValueKind.Object
            || !value.TryGetProperty("schemaVersion", out var schema)
            || schema.ValueKind != JsonValueKind.String || schema.GetString() != Schema
            || !value.TryGetProperty("baselineRunId", out var baseline)
            || baseline.ValueKind != JsonValueKind.Number || !baseline.TryGetInt64(out var baselineId) || baselineId <= 0
            || !value.TryGetProperty("well", out var well) || well.ValueKind != JsonValueKind.String
            || !WellName().IsMatch(well.GetString() ?? string.Empty)
            || !value.TryGetProperty("sourceFile", out var sourceFile) || sourceFile.ValueKind != JsonValueKind.String
            || !ValidRelativePath(sourceFile.GetString() ?? string.Empty)
            || !value.TryGetProperty("lineNumber", out var line) || line.ValueKind != JsonValueKind.Number
            || !line.TryGetInt32(out var lineNumber) || lineNumber <= 0 || lineNumber > 2_000_000)
            return false;

        var names = value.EnumerateObject().Select(property => property.Name).ToHashSet(StringComparer.Ordinal);
        if (!names.SetEquals(["schemaVersion", "baselineRunId", "well", "sourceFile", "lineNumber", "i", "j", "k1", "k2", "originalConnectionFactor", "targetConnectionFactor"])) return false;
        if (!TryIndex(value, "i", out var i, out _)
            || !TryIndex(value, "j", out var j, out _)
            || !TryIndex(value, "k1", out var k1, out var k1Value)
            || !TryIndex(value, "k2", out var k2, out var k2Value)
            || k1Value > k2Value
            || !TryPositiveFinite(value, "originalConnectionFactor", out var original)
            || !TryPositiveFinite(value, "targetConnectionFactor", out var target)
            || original == target)
            return false;

        parameters = new(baselineId, well.GetString()!, sourceFile.GetString()!, lineNumber,
            i, j, k1, k2, original, target);
        return true;
    }

    private static bool TryIndex(JsonElement value, string name, out string parsed, out int numeric)
    {
        parsed = string.Empty;
        numeric = 0;
        return value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.String
            && Index().IsMatch(item.GetString() ?? string.Empty)
            && int.TryParse(item.GetString(), out numeric) && numeric > 0
            && (parsed = item.GetString()!) is not null;
    }

    private static bool TryPositiveFinite(JsonElement value, string name, out double parsed)
    {
        parsed = 0;
        return value.TryGetProperty(name, out var item) && item.ValueKind == JsonValueKind.Number
            && item.TryGetDouble(out parsed) && double.IsFinite(parsed) && parsed > 0 && parsed <= 1_000_000_000;
    }

    [GeneratedRegex("^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$", RegexOptions.CultureInvariant)]
    private static partial Regex WellName();
    [GeneratedRegex("^[^\\\\/:][^\\\\:]{0,511}$", RegexOptions.CultureInvariant)]
    private static partial Regex RelativePath();
    [GeneratedRegex("^[1-9][0-9]{0,6}$", RegexOptions.CultureInvariant)]
    private static partial Regex Index();

    private static bool ValidRelativePath(string value) => RelativePath().IsMatch(value)
        && !value.EndsWith('/') && !value.Contains("//", StringComparison.Ordinal)
        && !value.Contains("..", StringComparison.Ordinal) && value.All(character => !char.IsControl(character));
}
