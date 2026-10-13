using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseCompletionGeometryParameters(long BaselineRunId, string Well, string SourceFile, int LineNumber,
    string I, string J, string K1, string K2, string Field, string Unit, double OriginalValue, double TargetValue, string[] RecordValues)
{
    internal const string Schema = "eclipse-completion-geometry-parameters/1";
    internal int ValueIndex => Field == "DIAMETER" ? 8 : Field == "KH" ? 9 : 10;
    internal static string? UnitFor(string? system, string field) => field == "SKIN" ? "1" : (system, field) switch
    {
        ("METRIC" or "PVT-M", "DIAMETER") => "m", ("FIELD", "DIAMETER") => "ft", ("LAB", "DIAMETER") => "cm",
        ("METRIC" or "PVT-M", "KH") => "mD.m", ("FIELD", "KH") => "mD.ft", ("LAB", "KH") => "mD.cm", _ => null
    };
    internal static bool TryParse(JsonElement value, out EclipseCompletionGeometryParameters? result)
    {
        result = null;
        string[] names = ["schemaVersion", "baselineRunId", "well", "sourceFile", "lineNumber", "i", "j", "k1", "k2", "field", "unit", "originalValue", "targetValue", "recordValues"];
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != names.Length
            || !value.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names)) return false;
        string Text(string key) => value.GetProperty(key).ValueKind == JsonValueKind.String ? value.GetProperty(key).GetString()! : "";
        if (Text("schemaVersion") != Schema || value.GetProperty("baselineRunId").ValueKind != JsonValueKind.Number
            || !value.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline <= 0
            || value.GetProperty("lineNumber").ValueKind != JsonValueKind.Number || !value.GetProperty("lineNumber").TryGetInt32(out var line) || line is <= 0 or > 2_000_000
            || !Regex.IsMatch(Text("well"), "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$", RegexOptions.CultureInvariant)
            || !Regex.IsMatch(Text("sourceFile"), @"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)) return false;
        foreach (var key in new[] { "i", "j", "k1", "k2" })
            if (!Regex.IsMatch(Text(key), "^[1-9][0-9]{0,6}$", RegexOptions.CultureInvariant)) return false;
        if (Text("k1") != Text("k2")) return false;
        var field = Text("field"); var unit = Text("unit");
        if (field is not ("DIAMETER" or "KH" or "SKIN") || !new[] { "FIELD", "METRIC", "LAB", "PVT-M" }.Any(s => UnitFor(s, field) == unit)) return false;
        if (!Numeric(value, "originalValue", out var original) || !Numeric(value, "targetValue", out var target) || original == target
            || !ValidScalar(field, unit, original) || !ValidScalar(field, unit, target)) return false;
        var raw = value.GetProperty("recordValues");
        if (raw.ValueKind != JsonValueKind.Array || raw.GetArrayLength() is < 11 or > 14
            || raw.EnumerateArray().Any(v => v.ValueKind != JsonValueKind.String || string.IsNullOrEmpty(v.GetString()) || v.GetString()!.Length > 64 || v.GetString()!.Any(char.IsControl))) return false;
        var records = raw.EnumerateArray().Select(v => v.GetString()!).ToArray();
        if (records[0] != Text("well") || records[1] != Text("i") || records[2] != Text("j") || records[3] != Text("k1") || records[4] != Text("k2")
            || records[5] != "OPEN" || !AutomaticFactor(records[7]) || !Number(records[field == "DIAMETER" ? 8 : field == "KH" ? 9 : 10], out var source) || source != original) return false;
        result = new(baseline, Text("well"), Text("sourceFile"), line, Text("i"), Text("j"), Text("k1"), Text("k2"), field, unit, original, target, records);
        return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection)
    {
        var dims = inspection.Dimensions; var records = inspection.ScheduleMetadata?.Records;
        if (dims is null || records is null || inspection.UnitSystem is not ("FIELD" or "METRIC" or "LAB" or "PVT-M") || UnitFor(inspection.UnitSystem, Field) != Unit
            || int.Parse(I, CultureInfo.InvariantCulture) > dims.Nx || int.Parse(J, CultureInfo.InvariantCulture) > dims.Ny || int.Parse(K1, CultureInfo.InvariantCulture) > dims.Nz) return false;
        foreach (var field in new[] { "DIAMETER", "KH", "SKIN" })
            if (!Number(RecordValues[field == "DIAMETER" ? 8 : field == "KH" ? 9 : 10], out var original)
                || !ValidScalar(field, UnitFor(inspection.UnitSystem, field)!, original)) return false;
        if (records.Any(r => r.Keyword is "COMPDAT" or "COMPDATM" && r.Values.Count > 0 && r.Values[0].IndexOfAny(['*', '?']) >= 0)) return false;
        var selected = records.Where(r => r.Keyword is "COMPDAT" or "COMPDATM" && r.Values.Count > 0 && r.Values[0].Trim() == Well.Trim()).ToArray();
        if (selected.Length != 1 || selected[0].Keyword != "COMPDAT" || selected[0].SourceFile != SourceFile || selected[0].LineNumber != LineNumber
            || !selected[0].Values.SequenceEqual(RecordValues)) return false;
        return inspection.ScheduleTimeline?.All(e => e.SourceFile == SourceFile && e.LineNumber.HasValue && e.LineNumber > LineNumber) != false;
    }
    internal static bool ValidScalar(string field, string unit, double value) => double.IsFinite(value) && (field switch
    {
        "SKIN" => value is >= 0 and <= 100,
        "KH" => value is > 0 and <= 1_000_000_000,
        "DIAMETER" => value > 0 && value * (unit == "ft" ? 0.3048 : unit == "cm" ? 0.01 : 1) <= 2,
        _ => false
    });
    internal static bool Number(string text, out double value) => double.TryParse(text.Replace('D', 'E').Replace('d', 'e'), NumberStyles.Float, CultureInfo.InvariantCulture, out value) && double.IsFinite(value);
    private static bool AutomaticFactor(string token) => token is "*" or "1*" || Number(token, out var n) && n == 0;
    private static bool Numeric(JsonElement value, string key, out double number)
    {
        number = 0; var item = value.GetProperty(key);
        return item.ValueKind == JsonValueKind.Number && item.TryGetDouble(out number) && double.IsFinite(number);
    }
}
