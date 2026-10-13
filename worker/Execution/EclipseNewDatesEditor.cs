using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseNewDatesEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseNewDatesParameters p, CancellationToken cancellation)
    {
        if (package.Files.Count != 1 || Path.GetFileName(package.MainFile) != p.SourceFile) Fail();
        var bytes = await File.ReadAllBytesAsync(package.MainFile, cancellation);
        var encoding = new UTF8Encoding(false, true);
        var text = encoding.GetString(bytes);
        if (!bytes.SequenceEqual(encoding.GetBytes(text)) || EclipseIncludeParser.Parse(text).Count != 0
            || Keyword(text, "TSTEP|TIME|RESTART|ACTION|ACTIONX|WELTARG|WELCNTL|WLIST|WELSEGS|GCONPROD|GCONINJE").Count != 0) Fail();
        var schedule = Keyword(text, "SCHEDULE"); var start = Keyword(text, "START"); var end = Keyword(text, "END");
        if (schedule.Count != 1 || start.Count != 1 || end.Count != 1 || start[0].Index >= schedule[0].Index || end[0].Index <= schedule[0].Index) Fail();
        var startRecord = Regex.Match(text[(start[0].Index + start[0].Length)..schedule[0].Index], "\\A(?:\\s|--[^\\r\\n]*)*(\\d{1,2}\\s+'?[A-Za-z]{3}'?\\s+\\d{4}\\s*/)");
        var startDate = default(DateOnly);
        if (!startRecord.Success || !ParseDate(startRecord.Groups[1].Value, out startDate)) Fail();
        var dates = Keyword(text, "DATES");
        if (dates.Count < 2 || dates.Count > 8000) Fail();
        var parsed = new List<(DateOnly Date, int Line, int Offset)>();
        foreach (Match date in dates)
        {
            if (date.Index <= schedule[0].Index || date.Index >= end[0].Index) Fail();
            var record = Regex.Match(text[(date.Index + date.Length)..], "\\A(?:\\s|--[^\\r\\n]*)*(\\d{1,2}\\s+'?[A-Za-z]{3}'?\\s+\\d{4}\\s*/)(?:\\s|--[^\\r\\n]*)*/");
            var value = default(DateOnly);
            if (!record.Success || !ParseDate(record.Groups[1].Value, out value) || value <= (parsed.Count > 0 ? parsed[^1].Date : startDate)) Fail();
            parsed.Add((value, 1 + Regex.Matches(text[..date.Index], "\\r\\n|\\r|\\n").Count, date.Index));
        }
        var index = parsed.FindIndex(d => d.Line == p.BeforeLineNumber && d.Date.ToString("yyyy-MM-dd") == p.BeforeDate);
        if (index < 0 || index + 1 >= parsed.Count || parsed[index + 1].Line != p.AfterLineNumber || parsed[index + 1].Date.ToString("yyyy-MM-dd") != p.AfterDate
            || !(parsed[index].Date < DateOnly.ParseExact(p.ShutDate, "yyyy-MM-dd", CultureInfo.InvariantCulture)
                 && p.ShutDate.CompareTo(p.OpenDate) < 0 && DateOnly.ParseExact(p.OpenDate, "yyyy-MM-dd", CultureInfo.InvariantCulture) < parsed[index + 1].Date)) Fail();
        var boundary = parsed[index + 1].Line;
        var metadata = EclipseDataInspector.InspectPackage(package.Files, p.SourceFile).ScheduleMetadata;
        if (metadata?.Wells.Count(w => w.Name == p.Well && w.SourceFile == p.SourceFile && w.LineNumber < boundary) != 1
            || !metadata.Completions.Any(c => c.Well == p.Well && c.SourceFile == p.SourceFile && c.LineNumber < boundary && c.Status == "OPEN")) Fail();
        var controls = metadata!.Records.Where(r => r.LineNumber < boundary && r.Keyword is "WCONPROD" or "WCONHIST" or "WCONINJE" or "WELOPEN").ToArray();
        if (controls.Any(r => r.Values.Count == 0 || r.Values[0].Contains('*') || r.Values[0].Contains('?'))) Fail();
        var production = controls.Where(r => r.Values[0] == p.Well).OrderBy(r => r.LineNumber).ToArray();
        if (production.Length == 0 || production.Any(r => r.Keyword is "WELOPEN" or "WCONINJE")
            || production[^1].Values.Count < 3 || production[^1].Values[1] != "OPEN") Fail();
        // Whole-well WELOPEN cannot open a completion. Reject later completion edits in this bounded phase.
        if (metadata.Records.Any(r => r.Keyword == "COMPDAT" && r.Values.Count > 0 && r.Values[0] == p.Well && r.LineNumber < boundary
            && (r.Values.Count < 6 || r.Values[5] != "OPEN"))) Fail();
        var newline = text.Contains("\r\n") ? "\r\n" : "\n";
        string Event(string iso, string status)
        {
            var d = DateOnly.ParseExact(iso, "yyyy-MM-dd", CultureInfo.InvariantCulture);
            return string.Join(newline, ["DATES", $" {d.Day} '{d.ToString("MMM", CultureInfo.InvariantCulture).ToUpperInvariant()}' {d.Year} /", "/", "WELOPEN", $" '{p.Well}' '{status}' /", "/", ""]);
        }
        var insertion = "-- GRDP-Studio new DATES; original controls and subsequent dates retained." + newline + Event(p.ShutDate, "SHUT") + Event(p.OpenDate, "OPEN");
        var offset = parsed[index + 1].Offset;
        var updated = text[..offset] + insertion + text[offset..];
        var temporary = package.MainFile + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try { await File.WriteAllBytesAsync(temporary, encoding.GetBytes(updated), cancellation); cancellation.ThrowIfCancellationRequested(); File.Move(temporary, package.MainFile, true); }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
        return $"Inserted DATES/WELOPEN {p.Well} SHUT {p.ShutDate}, OPEN {p.OpenDate} in {p.SourceFile} between {p.BeforeDate} and {p.AfterDate}; original records retained.";
    }
    private static MatchCollection Keyword(string text, string names) => Regex.Matches(text, "(?im)^[ \\t]*(" + names + ")[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$", RegexOptions.CultureInvariant);
    private static bool ParseDate(string raw, out DateOnly date)
    {
        date = default;
        var values = raw.Trim().TrimEnd('/').Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        if (values.Length != 3) return false;
        var month = values[1].Trim('\'').ToUpperInvariant(); if (month == "JLY") month = "JUL";
        return DateOnly.TryParseExact($"{values[0]} {month} {values[2]}", "d MMM yyyy", CultureInfo.InvariantCulture, DateTimeStyles.None, out date);
    }
    private static void Fail() => throw new EclipseDeckPackageException("ECLIPSE_NEW_DATES_MISMATCH", "New dates require one DATA with START and strictly increasing single-record DATES, adjacent source-bound dates, and an existing OPEN producer with OPEN completions. Mixed time steps, includes, restart and ambiguous overrides are not supported.");
}
