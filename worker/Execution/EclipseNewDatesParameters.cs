using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseNewDatesParameters(long BaselineRunId, string Well, string SourceFile, int BeforeLineNumber,
    string BeforeDate, int AfterLineNumber, string AfterDate, string ShutDate, string OpenDate)
{
    internal const string Schema = "eclipse-new-dates-parameters/1";
    internal static bool TryParse(JsonElement v, out EclipseNewDatesParameters? p)
    {
        p = null;
        string[] fields = ["schemaVersion", "baselineRunId", "well", "sourceFile", "beforeLineNumber", "beforeDate", "afterLineNumber", "afterDate", "shutDate", "openDate"];
        if (v.ValueKind != JsonValueKind.Object || v.EnumerateObject().Count() != fields.Length || !v.EnumerateObject().Select(x => x.Name).ToHashSet().SetEquals(fields)
            || !Text(v, "schemaVersion", Schema) || !Pattern(v, "well", "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$")
            || !Pattern(v, "sourceFile", "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
            || v.GetProperty("baselineRunId").ValueKind != JsonValueKind.Number || !v.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline <= 0
            || !Integer(v, "beforeLineNumber", out var beforeLine) || !Integer(v, "afterLineNumber", out var afterLine) || afterLine <= beforeLine
            || !Date(v, "beforeDate", out var before) || !Date(v, "afterDate", out var after)
            || !Date(v, "shutDate", out var shut) || !Date(v, "openDate", out var open)
            || !(before < shut && shut < open && open < after)) return false;
        p = new(baseline, v.GetProperty("well").GetString()!, v.GetProperty("sourceFile").GetString()!, beforeLine, before.ToString("yyyy-MM-dd"), afterLine,
            after.ToString("yyyy-MM-dd"), shut.ToString("yyyy-MM-dd"), open.ToString("yyyy-MM-dd"));
        return true;
    }
    private static bool Text(JsonElement v, string key, string value) => v.GetProperty(key).ValueKind == JsonValueKind.String && v.GetProperty(key).GetString() == value;
    private static bool Pattern(JsonElement v, string key, string regex) => v.GetProperty(key).ValueKind == JsonValueKind.String && Regex.IsMatch(v.GetProperty(key).GetString()!, regex);
    private static bool Integer(JsonElement v, string key, out int n) { n = 0; return v.GetProperty(key).ValueKind == JsonValueKind.Number && v.GetProperty(key).TryGetInt32(out n) && n > 0; }
    private static bool Date(JsonElement v, string key, out DateOnly d) { d = default; return v.GetProperty(key).ValueKind == JsonValueKind.String && DateOnly.TryParseExact(v.GetProperty(key).GetString(), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out d); }
}
