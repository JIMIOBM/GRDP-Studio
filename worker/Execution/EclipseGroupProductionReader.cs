using System.Globalization;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

// A deliberately bounded initial, direct-group ORAT subset. Unsupported decks retain normal inspection.
internal static class EclipseGroupProductionReader
{
    internal sealed record Target(EclipseGroupProductionControl Control, int Start, int Length);
    private sealed record Token(string Value, int Start, int Length, int Count = 1);
    private sealed record Record(List<Token> Values, int Line);
    private static MatchCollection Keywords(string text, string names) => Regex.Matches(text,
        "(?im)^[ \\t]*(" + names + ")[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$", RegexOptions.CultureInvariant);
    private static bool Name(string s) => Regex.IsMatch(s, "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static bool Number(string s, out double n) => double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out n) && double.IsFinite(n) && n > 0 && n <= 100_000_000;

    internal static IReadOnlyList<Target> Read(string text, string source)
    {
        try { return ReadCore(text, source); }
        catch (FormatException) { return []; }
    }
    private static IReadOnlyList<Target> ReadCore(string text, string source)
    {
        if (text.Length > 4 * 1024 * 1024) return [];
        if (EclipseIncludeParser.Parse(text).Count != 0 || Keywords(text, "FIELD|METRIC").Count != 1
            || Keywords(text, "LAB|PVT-M|RESTART|GRUPTREE|WELSPECL|WGRUPCON|GUIDERAT|GEFAC|WEFAC|GCONINJE|GCONSALE|GCONPRI|GCONCAL|GECON|GCONTOL|GRUPTARG|WELTARG|WELOPEN|WCONHIST|WCONINJH|WELCNTL|WELPI|WHISTCTL|ACTION|ACTIONX|UDQ|UDADIMS|PRORDER|WLIST|GRUPRIG|WDRILTIM|WELSEGS|VFPPROD|NETWORK|BRANPROP|NODEPROP|GRUPNET|GNETPUMP|GLIFTOPT|WLIFTOPT|GSSCPTST|GCUTBACK|WCUTBACK").Count != 0) return [];
        var schedule = Keywords(text, "SCHEDULE");
        if (schedule.Count != 1) return [];
        var start = schedule[0].Index + schedule[0].Length;
        if (Keywords(text[start..], "G[A-Z0-9-]+|COMPDATM|WECON|WECONINJ|WECONCMF|WTEST|WELDRAW|WMULT|WSEGITER|WELLOPEN")
            .Cast<Match>().Any(m => m.Groups[1].Value.ToUpperInvariant() != "GCONPROD")) return [];
        var times = Keywords(text[start..], "DATES|TSTEP|TIME|END");
        var end = times.Count == 0 ? text.Length : start + times[0].Index;
        foreach (var name in new[] { "WELSPECS", "COMPDAT", "WCONPROD", "WCONINJE", "GCONPROD" })
            if (Keywords(text[end..], name).Count != 0) return [];
        var specs = Records(text, start, end, "WELSPECS");
        var producers = Records(text, start, end, "WCONPROD");
        var injectors = Records(text, start, end, "WCONINJE");
        var completions = Records(text, start, end, "COMPDAT");
        var groups = Records(text, start, end, "GCONPROD");
        if (specs.Count == 0 || groups.Count == 0 || groups.Count > 1000
            || specs.Any(r => r.Values.Count < 2 || !Name(r.Values[0].Value) || !Name(r.Values[1].Value))
            || specs.Select(r => r.Values[0].Value).Distinct(StringComparer.Ordinal).Count() != specs.Count
            || groups.Any(r => r.Values.Count < 3 || !Name(r.Values[0].Value) || r.Values[0].Value == "FIELD")
            || groups.Select(r => r.Values[0].Value).Distinct(StringComparer.Ordinal).Count() != groups.Count
            || producers.Concat(injectors).Any(r => r.Values.Count == 0 || !Name(r.Values[0].Value))) return [];
        var result = new List<Target>();
        foreach (var g in groups)
        {
            var v = g.Values;
            // Explicit extra group limits/guide rates need their own capability; never silently ignore them.
            if (v.Count > 21 || v[1].Value != "ORAT" || v[2].Count != 1 || !Number(v[2].Value, out var rate)
                || v.Skip(3).Any(t => t.Value.Length != 0)) continue;
            var members = specs.Where(r => r.Values[1].Value == v[0].Value).ToArray();
            if (members.Length < 2 || members.Length > 1000) continue;
            var entries = new List<EclipseGroupProductionMember>();
            foreach (var member in members)
            {
                var well = member.Values[0].Value;
                var controls = producers.Where(r => r.Values[0].Value == well).ToArray();
                if (controls.Length != 1 || injectors.Any(r => r.Values[0].Value == well)) break;
                var c = controls[0].Values;
                if (c.Count < 9 || c.Count > 12 || c[1].Value != "OPEN" || c[2].Value != "GRUP"
                    || !Number(c[8].Value, out var bhp) || c.Skip(3).Take(5).Any(t => t.Value.Length != 0 && !Number(t.Value, out _))
                    || c.Skip(9).Any(t => t.Value.Length != 0 && t.Value != "0")) break;
                var connections = completions.Where(r => r.Values.Count > 0 && r.Values[0].Value == well).ToArray();
                if (connections.Length == 0 || connections.Any(r => r.Values.Count < 6 || r.Values[5].Value != "OPEN")) break;
                entries.Add(new(well, bhp));
            }
            if (entries.Count != members.Length) continue;
            result.Add(new(new(v[0].Value, source, g.Line, "ORAT", Keywords(text, "FIELD").Count == 1 ? "STB/day" : "sm3/day", rate, entries), v[2].Start, v[2].Length));
        }
        return result;
    }
    private static List<Record> Records(string text, int start, int end, string keyword)
    {
        var result = new List<Record>();
        foreach (Match block in Keywords(text[start..end], keyword))
        {
            var offset = start + block.Index + block.Length;
            var tokens = new List<Token>(); bool terminated = false; int line = 0;
            foreach (Match lex in Regex.Matches(text[offset..end], """--[^\r\n]*|'(?:[^']|'')*'|"(?:[^"]|"")*"|/|[^\s/]+"""))
            {
                if (lex.Value.StartsWith("--", StringComparison.Ordinal)) continue;
                if (lex.Value == "/")
                {
                    if (tokens.Count == 0) { terminated = true; break; }
                    result.Add(new(tokens, line)); tokens = []; if (result.Count > 4000) throw new FormatException();
                    continue;
                }
                var value = lex.Value;
                if (value.Length >= 2 && value[0] is '\'' or '"' && value[^1] == value[0]) value = value[1..^1];
                if (tokens.Count == 0) line = 1 + Regex.Matches(text[..(offset + lex.Index)], "\r\n|\r|\n").Count;
                int count = 1;
                if (tokens.Count > 0 && Regex.Match(value, "^(\\d+)\\*(.*)$") is { Success: true } repeat)
                {
                    if (!int.TryParse(repeat.Groups[1].Value, out count) || count < 1 || count > 128) throw new FormatException();
                    value = repeat.Groups[2].Value;
                }
                if (tokens.Count + count > 128) throw new FormatException();
                for (int i = 0; i < count; i++) tokens.Add(new(value, offset + lex.Index, lex.Length, count));
            }
            if (!terminated) throw new FormatException();
        }
        return result;
    }
}
