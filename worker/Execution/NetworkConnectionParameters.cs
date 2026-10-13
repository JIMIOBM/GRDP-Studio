using System.Text.Json;
using System.Text.RegularExpressions;

internal static partial class NetworkConnectionParameters
{
    private const string Schema = "pipesim-network-connection-parameters/1";
    private static readonly HashSet<string> Fields =
        ["schemaVersion", "baselineRunId", "source", "sourcePort", "originalDestination", "newDestination"];

    public static bool Valid(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != Fields.Count ||
            value.EnumerateObject().Any(property => !Fields.Contains(property.Name)) ||
            !String(value, "schemaVersion", Schema) || !Integer(value, "baselineRunId", 1, long.MaxValue) ||
            !Name(value, "source") || !Name(value, "originalDestination") || !Name(value, "newDestination") ||
            !Text(value, "sourcePort", 64, allowEmpty: true)) return false;

        var source = value.GetProperty("source").GetString();
        var original = value.GetProperty("originalDestination").GetString();
        var target = value.GetProperty("newDestination").GetString();
        var port = value.GetProperty("sourcePort").GetString();
        return source != original && source != target && original != target &&
               (port == "" || port is "Top Port" or "Bottom Port");
    }

    private static bool String(JsonElement value, string name, string expected) =>
        value.TryGetProperty(name, out var property) && property.ValueKind == JsonValueKind.String &&
        property.GetString() == expected;

    private static bool Integer(JsonElement value, string name, long minimum, long maximum) =>
        value.TryGetProperty(name, out var property) && property.ValueKind == JsonValueKind.Number &&
        property.TryGetInt64(out var number) && number >= minimum && number <= maximum;

    private static bool Name(JsonElement value, string name)
    {
        if (!value.TryGetProperty(name, out var property) || property.ValueKind != JsonValueKind.String) return false;
        var text = property.GetString();
        return text is { Length: > 0 and <= 255 } && NamePattern().IsMatch(text) &&
               !text.Contains("..", StringComparison.Ordinal) && !text.Contains("://", StringComparison.Ordinal);
    }

    private static bool Text(JsonElement value, string name, int maximum, bool allowEmpty) =>
        value.TryGetProperty(name, out var property) && property.ValueKind == JsonValueKind.String &&
        property.GetString() is { } text && text.Length <= maximum && (allowEmpty || !string.IsNullOrWhiteSpace(text)) &&
        !text.Any(char.IsControl);

    [GeneratedRegex("^[^\\p{Cc}/\\\\]{1,255}$", RegexOptions.CultureInvariant)]
    private static partial Regex NamePattern();
}
