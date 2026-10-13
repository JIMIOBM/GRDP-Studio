using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipsePressureControlEditor
{
    private sealed record Token(string Value, int Start, int Length, int Count = 1);
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipsePressureControlParameters p, CancellationToken cancellation)
    {
        if (package.Files.Count != 1 || !Path.GetFileName(package.MainFile).Equals(p.SourceFile, StringComparison.Ordinal)) Fail();
        var bytes = await File.ReadAllBytesAsync(package.MainFile, cancellation);
        var encoding = new UTF8Encoding(false, true);
        var text = encoding.GetString(bytes);
        if (EclipseIncludeParser.Parse(text).Count != 0 || Keyword(text, "FIELD").Count != 1
            || Keyword(text, "METRIC|LAB|PVT-M|RESTART").Count != 0) Fail();
        var schedule = Keyword(text, "SCHEDULE");
        if (schedule.Count != 1) Fail();
        var start = schedule[0].Index + schedule[0].Length;
        var later = Keyword(text[start..], "DATES|TSTEP|TIME|END");
        var end = later.Count > 0 ? start + later[0].Index : text.Length;
        var initial = text[start..end];
        if (Keyword(initial, "WCONHIST|WELOPEN|WELTARG|WELCNTL|WELPI|ACTION|ACTIONX|WCONINJH|GCONPROD|GCONINJE|WELSEGS|WHISTCTL").Count != 0) Fail();
        Token? mode = null, pressure = null;
        foreach (Match block in Keyword(initial, "WCONPROD|WCONINJE"))
        {
            var keyword = block.Groups[1].Value.ToUpperInvariant();
            var record = new List<Token>();
            var terminated = false;
            foreach (Match lex in Regex.Matches(text[(start + block.Index + block.Length)..end], """--[^\r\n]*|'(?:[^']|'')*'|"(?:[^"]|"")*"|/|[^\s/]+"""))
            {
                if (lex.Value.StartsWith("--", StringComparison.Ordinal)) continue;
                if (lex.Value != "/")
                {
                    var value = Unquote(lex.Value);
                    var count = 1;
                    if (record.Count > 0 && Regex.Match(value, "^(\\d+)\\*(.*)$") is { Success: true } repeat)
                    {
                        if (!int.TryParse(repeat.Groups[1].Value, out count) || count < 1 || count > 64) Fail();
                        value = repeat.Groups[2].Value;
                    }
                    if (record.Count + count > 64) Fail();
                    var token = new Token(value, start + block.Index + block.Length + lex.Index, lex.Length, count);
                    for (var i = 0; i < count; i++) record.Add(token);
                    continue;
                }
                if (record.Count == 0) { terminated = true; break; }
                if (record[0].Value.Contains('*') || record[0].Value.Contains('?')) Fail();
                if (record[0].Value == p.Well)
                {
                    var modeIndex = keyword == "WCONPROD" ? 2 : 3;
                    var pressureIndex = keyword == "WCONPROD" ? 8 : 6;
                    var statusIndex = keyword == "WCONPROD" ? 1 : 2;
                    if (keyword != p.Keyword || mode is not null || record.Count <= pressureIndex
                        || record[statusIndex].Value != "OPEN" || record[modeIndex].Value != p.OriginalControlMode || record[modeIndex].Count != 1
                        || (keyword == "WCONINJE" && record[1].Value != p.InjectionType)
                        || record[pressureIndex].Count != 1
                        || 1 + Regex.Matches(text[..record[0].Start], "\r\n|\r|\n").Count != p.LineNumber
                        || !double.TryParse(record[pressureIndex].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var original)
                        || !double.IsFinite(original) || original != p.OriginalBhp) Fail();
                    mode = record[modeIndex]; pressure = record[pressureIndex];
                }
                record.Clear();
            }
            if (!terminated) Fail();
        }
        if (mode is null || pressure is null || !encoding.GetBytes(text).SequenceEqual(bytes)) Fail();
        var updated = text[..pressure!.Start] + p.TargetBhp.ToString("G17", CultureInfo.InvariantCulture) + text[(pressure.Start + pressure.Length)..];
        updated = updated[..mode!.Start] + "'BHP'" + updated[(mode.Start + mode.Length)..];
        cancellation.ThrowIfCancellationRequested();
        await File.WriteAllBytesAsync(package.MainFile, encoding.GetBytes(updated), cancellation);
        return $"Applied initial {p.Keyword} {p.Well} OPEN {p.OriginalControlMode}->BHP {p.OriginalBhp:G17}->{p.TargetBhp:G17} psia at {p.SourceFile}:{p.LineNumber}; other limits preserved.";
    }
    private static MatchCollection Keyword(string text, string alternatives) => Regex.Matches(text,
        "(?im)^[ \\t]*(" + alternatives + ")[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$", RegexOptions.CultureInvariant);
    private static string Unquote(string value) => value.Length >= 2 && value[0] is '\'' or '"' && value[^1] == value[0] ? value[1..^1] : value;
    private static void Fail() => throw new EclipseDeckPackageException("ECLIPSE_PRESSURE_CONTROL_MISMATCH",
        "Initial BHP requires one FIELD DATA, a unique OPEN literal source record, matching mode/pressure/fluid and no ambiguous initial overrides. Defaulted or repeated BHP is not supported.");
}
