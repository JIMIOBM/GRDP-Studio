using System.Globalization;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

// Initial direct-group OIL/RATE and GAS/RESV/REIN/VREP. Unsupported decks retain normal inspection.
internal static class EclipseAdvancedInjectionReader
{
    internal sealed record Target(EclipseAdvancedInjectionControl Control, int Start, int Length);
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
            || Keywords(text, "LAB|PVT-M|RESTART|GRUPTREE|WELSPECL|WGRUPCON|GUIDERAT|GEFAC|WEFAC|GCONPROD|GCONSUMP|GCONSALE|GCONPRI|GCONCAL|GECON|GCONTOL|GRUPTARG|WELTARG|WELOPEN|WCONHIST|WCONINJH|WELCNTL|WELPI|WHISTCTL|ACTION|ACTIONX|UDQ|UDADIMS|PRORDER|WLIST|GRUPRIG|WDRILTIM|WELSEGS|VFPPROD|VFPINJ|COMPS|WINJGAS|WINJOIL|WINJTEMP|THERMAL|TEMP|NETWORK|BRANPROP|NODEPROP|GRUPNET|GNETPUMP|GLIFTOPT|WLIFTOPT|GSSCPTST|GCUTBACK|WCUTBACK|CECON|COMPLUMP|WPLUG|WORKLIM").Count != 0) return [];
        var schedule = Keywords(text, "SCHEDULE");
        if (schedule.Count != 1) return [];
        var start = schedule[0].Index + schedule[0].Length;
        if (Keywords(text[start..], "G[A-Z0-9-]+|COMPDATM|WECON|WECONINJ|WECONCMF|WTEST|WELDRAW|WMULT|WSEGITER|WELLOPEN")
            .Cast<Match>().Any(m => m.Groups[1].Value.ToUpperInvariant() != "GCONINJE")) return [];
        var times = Keywords(text[start..], "DATES|TSTEP|TIME|END");
        var end = times.Count == 0 ? text.Length : start + times[0].Index;
        foreach (var name in new[] { "WELSPECS", "COMPDAT", "WCONPROD", "WCONINJE", "GCONINJE" })
            if (Keywords(text[end..], name).Count != 0) return [];
        var specs = Records(text, start, end, "WELSPECS");
        var producers = Records(text, start, end, "WCONPROD");
        var injectors = Records(text, start, end, "WCONINJE");
        var completions = Records(text, start, end, "COMPDAT");
        var groups = Records(text, start, end, "GCONINJE");
        if (specs.Count == 0 || groups.Count == 0 || groups.Count > 1000
            || specs.Any(r => r.Values.Count < 2 || !Name(r.Values[0].Value) || !Name(r.Values[1].Value))
            || specs.Select(r => r.Values[0].Value).Distinct(StringComparer.Ordinal).Count() != specs.Count
            || groups.Any(r => r.Values.Count < 4 || !Name(r.Values[0].Value) || r.Values[0].Value == "FIELD")
            || groups.Select(r => r.Values[0].Value).Distinct(StringComparer.Ordinal).Count() != groups.Count
            || producers.Concat(injectors).Any(r => r.Values.Count == 0 || !Name(r.Values[0].Value))) return [];
        // A new contract keeps the frozen water/gas RATE capabilities unchanged.
        if (groups.Any(g => !(g.Values[1].Value == "OIL" && g.Values[2].Value == "RATE")
            && !(g.Values[1].Value == "GAS" && g.Values[2].Value is "RESV" or "REIN" or "VREP"))) return [];
        var result = new List<Target>();
        foreach (var g in groups)
        {
            var v = g.Values;
            // Explicit extra group limits/guide rates need their own capability; never silently ignore them.
            var phase = v[1].Value is "WAT" or "WATER" ? "WATER" : v[1].Value;
            var mode = v[2].Value;
            var slot = mode switch { "RATE" => 3, "RESV" => 4, "REIN" => 5, _ => 6 };
            var referenceSlot = mode == "REIN" ? 10 : mode == "VREP" ? 11 : -1;
            if (v.Count > 13 || v.Count <= slot || v[slot].Count != 1 || !Number(v[slot].Value, out var rate)
                || (referenceSlot >= 0 && (v.Count <= referenceSlot || !Name(v[referenceSlot].Value) || v[referenceSlot].Value is "FIELD" || v[referenceSlot].Value == v[0].Value))
                || v.Skip(3).Where((_, n) => n + 3 != slot && n + 3 != referenceSlot).Any(t => t.Value.Length != 0)) continue;
            string? referenceGroup = referenceSlot >= 0 ? v[referenceSlot].Value : null;
            var referenceMembers = new List<string>();
            if (referenceGroup is not null)
            {
                var refs = specs.Where(r => r.Values[1].Value == referenceGroup).ToArray();
                if (refs.Length == 0 || refs.Length > 1000) continue;
                foreach (var member in refs)
                {
                    var well = member.Values[0].Value;
                    var controls = producers.Where(r => r.Values[0].Value == well).ToArray();
                    if (controls.Length != 1 || injectors.Any(r => r.Values[0].Value == well)) break;
                    var c = controls[0].Values;
                    if (c.Count < 9 || c[1].Value != "OPEN" || c[2].Value != "ORAT" || !Number(c[3].Value, out _)
                        || c.Skip(4).Take(4).Any(t => t.Value.Length != 0) || !Number(c[8].Value, out _)
                        || c.Skip(9).Any(t => t.Value.Length != 0 && t.Value != "0")) break;
                    var cs = completions.Where(r => r.Values.Count > 0 && r.Values[0].Value == well).ToArray();
                    if (cs.Length == 0 || cs.Any(r => r.Values.Count < 6 || r.Values[5].Value != "OPEN")) break;
                    referenceMembers.Add(well);
                }
                if (referenceMembers.Count != refs.Length) continue;
            }
            var members = specs.Where(r => r.Values[1].Value == v[0].Value).ToArray();
            if (members.Length < 2 || members.Length > 1000) continue;
            var entries = new List<EclipseAdvancedInjectionMember>();
            foreach (var member in members)
            {
                var well = member.Values[0].Value;
                var controls = injectors.Where(r => r.Values[0].Value == well).ToArray();
                if (controls.Length != 1 || producers.Any(r => r.Values[0].Value == well)) break;
                var c = controls[0].Values;
                var wellPhase = c.Count > 1 && c[1].Value is "WAT" or "WATER" ? "WATER" : c.Count > 1 ? c[1].Value : "";
                if (c.Count < 7 || c.Count > 15 || wellPhase != phase || c[2].Value != "OPEN" || c[3].Value != "GRUP"
                    || !Number(c[6].Value, out var bhp) || !Number(c[4].Value, out var surfaceLimit) || c[5].Value.Length != 0
                    || c.Skip(7).Any(t => t.Value.Length != 0 && t.Value != "0")) break;
                var connections = completions.Where(r => r.Values.Count > 0 && r.Values[0].Value == well).ToArray();
                if (connections.Length == 0 || connections.Any(r => r.Values.Count < 6 || r.Values[5].Value != "OPEN")) break;
                entries.Add(new(well, bhp, surfaceLimit));
            }
            if (entries.Count != members.Length) continue;
            var unit = mode is "REIN" or "VREP" ? "fraction" : mode == "RESV" ? Keywords(text, "FIELD").Count == 1 ? "RB/day" : "rm3/day" : Keywords(text, "FIELD").Count == 1 ? "STB/day" : "sm3/day";
            result.Add(new(new(v[0].Value, source, g.Line, mode, phase, unit, rate, entries, referenceGroup, referenceMembers), v[slot].Start, v[slot].Length));
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
