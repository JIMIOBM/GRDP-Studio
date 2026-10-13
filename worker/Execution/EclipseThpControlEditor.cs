using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseThpControlEditor
{
    private sealed record Token(string Value, int Start, int Length, int Count = 1);
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseThpControlParameters p, CancellationToken cancellation)
    {
        if (package.Files.Count != 1 || !Path.GetFileName(package.MainFile).Equals(p.SourceFile, StringComparison.Ordinal)) Fail();
        var bytes = await File.ReadAllBytesAsync(package.MainFile, cancellation);
        var encoding = new UTF8Encoding(false, true);
        var text = encoding.GetString(bytes);
        if (EclipseIncludeParser.Parse(text).Count != 0 || Keyword(text, "FIELD").Count != 1
            || Keyword(text, "METRIC|LAB|PVT-M|RESTART|WELSEGS").Count != 0
            || p.Unit != "psia") Fail();
        var schedule = Keyword(text, "SCHEDULE");
        if (schedule.Count != 1) Fail();
        var start = schedule[0].Index + schedule[0].Length;
        var later = Keyword(text[start..], "DATES|TSTEP|TIME|END");
        var end = later.Count > 0 ? start + later[0].Index : text.Length;
        var initial = text[start..end];
        if (Keyword(initial, "WCONHIST|WELOPEN|WELTARG|WELCNTL|WELPI|ACTION|ACTIONX|WCONINJH|GCONPROD|GCONINJE|WELSEGS|WHISTCTL").Count != 0) Fail();
        var tables = EclipseVfpTableReader.Read(text, p.SourceFile);
        var table = tables.SingleOrDefault(t => t.Number == p.VfpTableNumber);
        if (table is null || table.Sha256 != p.VfpTableSha256 || table.FlowType != "LIQ" || table.WaterFractionType != "WCT" || table.GasFractionType != "GOR" || table.AlqType != "" || p.TargetThp < table.ThpAxis[0] || p.TargetThp > table.ThpAxis[^1]
            || p.OriginalThp < table.ThpAxis[0] || p.OriginalThp > table.ThpAxis[^1]
            || p.OriginalAlq < table.AlqAxis[0] || p.OriginalAlq > table.AlqAxis[^1]) Fail();
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
                    var rateIndex = 9;
                    if (keyword != "WCONPROD" || rate is not null || record.Count <= 10
                        || record[1].Value != "OPEN" || record[2].Value != "THP" || record[2].Count != 1
                        || record[rateIndex].Count != 1
                        || 1 + Regex.Matches(text[..record[0].Start], "\r\n|\r|\n").Count != p.LineNumber
                        || !double.TryParse(record[rateIndex].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var original)
                        || !double.IsFinite(original) || original != p.OriginalThp) Fail();
                    if (record[10].Count != 1 || !int.TryParse(record[10].Value, out var boundTable) || boundTable != p.VfpTableNumber) Fail();
                    var alq = 0d;
                    if (record.Count > 11 && record[11].Value != "" && (!double.TryParse(record[11].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out alq) || !double.IsFinite(alq))) Fail();
                    if (alq != p.OriginalAlq) Fail();
                    rate = record[rateIndex];
                }
                record.Clear();
            }
            if (!terminated) Fail();
        }
        if (rate is null || !encoding.GetBytes(text).SequenceEqual(bytes)) Fail();
        var updated = text[..rate!.Start] + p.TargetThp.ToString("G17", CultureInfo.InvariantCulture) + text[(rate.Start + rate.Length)..];
        cancellation.ThrowIfCancellationRequested();
        await File.WriteAllBytesAsync(package.MainFile, encoding.GetBytes(updated), cancellation);
        return $"Applied initial WCONPROD {p.Well} OPEN THP {p.OriginalThp:G17}->{p.TargetThp:G17} {p.Unit} at {p.SourceFile}:{p.LineNumber}; other limits preserved.";
    }
    private static MatchCollection Keyword(string text, string alternatives) => Regex.Matches(text,
        "(?im)^[ \\t]*(" + alternatives + ")[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$", RegexOptions.CultureInvariant);
    private static string Unquote(string value) => value.Length >= 2 && value[0] is '\'' or '"' && value[^1] == value[0] ? value[1..^1] : value;
    private static void Fail() => throw new EclipseDeckPackageException("ECLIPSE_THP_CONTROL_MISMATCH",
        "Initial THP control requires one FIELD DATA, a complete source-bound initial BHP VFPPROD table, THP and ALQ within table axes, matching OPEN THP record and no ambiguous overrides.");
}
