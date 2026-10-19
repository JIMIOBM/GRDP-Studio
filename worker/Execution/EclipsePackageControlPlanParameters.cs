using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipsePackageControlPlanEdit(string Well, string SourceFile, string SourceSha256, int LineNumber,
    string Date, int TimeDays, string Unit, double OriginalOilRate, double TargetOilRate);
internal sealed record EclipsePackageControlPlanParameters(long BaselineRunId, IReadOnlyList<EclipsePackageControlPlanEdit> Edits)
{
    internal const string Schema = "eclipse-control-plan-parameters/2";
    internal static bool TryParse(JsonElement input, out EclipsePackageControlPlanParameters? result)
    {
        result = null;
        if (!Shape(input, ["schemaVersion", "baselineRunId", "edits"]) || input.GetProperty("schemaVersion").ValueKind != JsonValueKind.String
            || input.GetProperty("schemaVersion").GetString() != Schema || !Integer(input.GetProperty("baselineRunId"), out var baseline) || baseline <= 0
            || input.GetProperty("edits").ValueKind != JsonValueKind.Array || input.GetProperty("edits").GetArrayLength() is < 1 or > 24) return false;
        var edits = new List<EclipsePackageControlPlanEdit>(); var seen = new HashSet<(string, int)>(); var sources = new HashSet<(string, int)>();
        foreach (var edit in input.GetProperty("edits").EnumerateArray())
        {
            if (!Shape(edit, ["well", "sourceFile", "sourceSha256", "lineNumber", "date", "timeDays", "unit", "originalOilRate", "targetOilRate"])
                || !Text(edit.GetProperty("well"), out var well) || !Regex.IsMatch(well, "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$", RegexOptions.CultureInvariant)
                || !Text(edit.GetProperty("sourceFile"), out var source) || !EclipsePackageProductionTargetReader.SafePath(source)
                || !Text(edit.GetProperty("sourceSha256"), out var sha) || !Regex.IsMatch(sha, "^[a-f0-9]{64}$", RegexOptions.CultureInvariant)
                || !Integer(edit.GetProperty("lineNumber"), out var line) || line is < 1 or > int.MaxValue
                || !Integer(edit.GetProperty("timeDays"), out var time) || time is < 0 or > 3_000_000
                || !Text(edit.GetProperty("date"), out var date) || !DateOnly.TryParseExact(date, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _)
                || !Text(edit.GetProperty("unit"), out var unit) || unit is not ("STB/day" or "sm3/day")
                || !Positive(edit.GetProperty("originalOilRate"), out var original) || !Positive(edit.GetProperty("targetOilRate"), out var target) || original == target
                || !seen.Add((well, (int)time)) || !sources.Add((source, (int)line)) || edits.Count > 0 && edits[^1].TimeDays > time) return false;
            edits.Add(new(well, source, sha, (int)line, date, (int)time, unit, original, target));
        }
        result = new(baseline, edits); return true;
    }
    private static bool Shape(JsonElement value, string[] fields) => value.ValueKind == JsonValueKind.Object
        && value.EnumerateObject().Count() == fields.Length && value.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(fields);
    private static bool Text(JsonElement value, out string result) { result = ""; if (value.ValueKind != JsonValueKind.String) return false; result = value.GetString()!; return true; }
    private static bool Integer(JsonElement value, out long result) { result = 0; return value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out result); }
    private static bool Positive(JsonElement value, out double result) { result = 0; return value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out result) && double.IsFinite(result) && result is > 0 and <= 100_000_000; }
}
