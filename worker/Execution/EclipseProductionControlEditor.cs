using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseProductionControlEditor
{
    private sealed record Token(string Value, int Start, int Length, int Count = 1);
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseProductionControlParameters p, CancellationToken cancellation)
    {
        if (package.Files.Count != 1 || !Path.GetFileName(package.MainFile).Equals(p.SourceFile, StringComparison.Ordinal)) Fail();
        var bytes = await File.ReadAllBytesAsync(package.MainFile, cancellation);
        var encoding = new UTF8Encoding(false, true);
        var text = encoding.GetString(bytes);
        if (EclipseIncludeParser.Parse(text).Count != 0 || Keyword(text, "FIELD|METRIC").Count != 1
            || Keyword(text, "LAB|PVT-M|RESTART").Count != 0
            || p.Unit != (Keyword(text, "METRIC").Count == 1 ? "sm3/day" : p.ControlMode == "GRAT" ? "Mscf/day" : "STB/day")) Fail();
        var schedule = Keyword(text, "SCHEDULE");
        if (schedule.Count != 1) Fail();
        var start = schedule[0].Index + schedule[0].Length;
        var later = Keyword(text[start..], "DATES|TSTEP|TIME|END");
        var end = later.Count > 0 ? start + later[0].Index : text.Length;
        var initial = text[start..end];
        if (Keyword(initial, "WCONHIST|WELOPEN|WELTARG|WELCNTL|WELPI|ACTION|ACTIONX|WCONINJH|GCONPROD|GCONINJE|WELSEGS|WHISTCTL").Count != 0) Fail();
        Token? rate = null;
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
                    var rateIndex = p.ControlMode == "WRAT" ? 4 : p.ControlMode == "GRAT" ? 5 : 6;
                    if (keyword != "WCONPROD" || rate is not null || record.Count <= rateIndex
                        || record[1].Value != "OPEN" || record[2].Value != p.ControlMode || record[2].Count != 1
                        || record[rateIndex].Count != 1
                        || 1 + Regex.Matches(text[..record[0].Start], "\r\n|\r|\n").Count != p.LineNumber
                        || !double.TryParse(record[rateIndex].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var original)
                        || !double.IsFinite(original) || original != p.OriginalTargetRate) Fail();
                    rate = record[rateIndex];
                }
                record.Clear();
            }
            if (!terminated) Fail();
        }
        if (rate is null || !encoding.GetBytes(text).SequenceEqual(bytes)) Fail();
        var updated = text[..rate!.Start] + p.TargetRate.ToString("G17", CultureInfo.InvariantCulture) + text[(rate.Start + rate.Length)..];
        cancellation.ThrowIfCancellationRequested();
        await File.WriteAllBytesAsync(package.MainFile, encoding.GetBytes(updated), cancellation);
        return $"Applied initial WCONPROD {p.Well} OPEN {p.ControlMode} {p.OriginalTargetRate:G17}->{p.TargetRate:G17} {p.Unit} at {p.SourceFile}:{p.LineNumber}; other limits preserved.";
    }
    private static MatchCollection Keyword(string text, string alternatives) => Regex.Matches(text,
        "(?im)^[ \\t]*(" + alternatives + ")[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$", RegexOptions.CultureInvariant);
    private static string Unquote(string value) => value.Length >= 2 && value[0] is '\'' or '"' && value[^1] == value[0] ? value[1..^1] : value;
    private static void Fail() => throw new EclipseDeckPackageException("ECLIPSE_PRODUCTION_CONTROL_MISMATCH",
        "Initial rate control requires one FIELD/METRIC DATA, a unique OPEN literal source record, matching mode/rate/unit and no ambiguous initial overrides. Defaulted or repeated target is not supported.");
}
