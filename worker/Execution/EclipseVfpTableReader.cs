using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

/// <summary>Bounded complete initial BHP VFPPROD tables; invalid/ambiguous tables are never offered.</summary>
internal static class EclipseVfpTableReader
{
    internal static MatchCollection Keyword(string text, string alternatives) => Regex.Matches(text,
        "(?im)^[ \\t]*(" + alternatives + ")[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$", RegexOptions.CultureInvariant);
    internal static IReadOnlyList<EclipseVfpTable> Read(string text, string file)
    {
        try
        {
            var units = Keyword(text, "FIELD|METRIC");
            var schedules = Keyword(text, "SCHEDULE");
            if (units.Count != 1 || schedules.Count != 1) return [];
            var unit = units[0].Groups[1].Value.ToUpperInvariant();
            var start = schedules[0].Index + schedules[0].Length;
            var boundary = Keyword(text[start..], "TSTEP|DATES|TIME|END");
            var end = boundary.Count > 0 ? start + boundary[0].Index : text.Length;
            var matches = Keyword(text, "VFPPROD");
            if (matches.Count > 64 || matches.Cast<Match>().Any(m => m.Index < start || m.Index >= end)) return [];
            var result = new List<EclipseVfpTable>();
            foreach (Match match in matches)
            {
                var tokens = Regex.Matches(text[(match.Index + match.Length)..end], """--[^\r\n]*|'(?:[^']|'')*'|"(?:[^"]|"")*"|/|[^\s/]+""").Cast<Match>().GetEnumerator();
                int last = 0;
                List<string> Record()
                {
                    var values = new List<string>();
                    while (tokens.MoveNext())
                    {
                        var lex = tokens.Current;
                        if (lex.Value.StartsWith("--", StringComparison.Ordinal)) continue;
                        last = match.Index + match.Length + lex.Index + lex.Length;
                        if (lex.Value == "/") return values;
                        var value = lex.Value.Trim('\'', '"');
                        var repeat = Regex.Match(value, "^(\\d+)\\*(.*)$");
                        int count = repeat.Success ? int.Parse(repeat.Groups[1].Value, CultureInfo.InvariantCulture) : 1;
                        value = repeat.Success ? repeat.Groups[2].Value : value;
                        if (count < 1 || count > 128 || values.Count + count > 132) throw new FormatException();
                        for (var j = 0; j < count; j++) values.Add(value);
                    }
                    throw new FormatException();
                }
                var header = Record();
                if (header.Count < 5 || header.Count > 9) return [];
                while (header.Count < 9) header.Add("");
                var id = int.Parse(header[0], CultureInfo.InvariantCulture);
                var datum = Number(header[1]);
                if (id <= 0 || id > 100000 || datum < 0 || !new[] { "OIL", "LIQ", "GAS" }.Contains(header[2])
                    || !new[] { "WOR", "WCT", "WGR" }.Contains(header[3]) || !new[] { "GOR", "GLR", "OGR" }.Contains(header[4])
                    || header[5] is not ("" or "THP") || header[6].Length > 16
                    || header[7] != "" && header[7] != unit || header[8] is not ("" or "BHP")) return [];
                double[] Axis(int min, int max)
                {
                    var axis = Record().Select(Number).ToArray();
                    if (axis.Length < min || axis.Length > max || axis.Any(v => v < 0)
                        || axis.Zip(axis.Skip(1)).Any(pair => pair.First >= pair.Second)) throw new FormatException();
                    return axis;
                }
                var flow = Axis(2, 128); var thp = Axis(1, 32); var water = Axis(1, 32); var gas = Axis(1, 32); var alq = Axis(1, 32);
                var countRows = checked(thp.Length * water.Length * gas.Length * alq.Length);
                if (countRows > 4096) return [];
                var identities = new HashSet<string>();
                for (var row = 0; row < countRows; row++)
                {
                    var values = Record();
                    if (values.Count != 4 + flow.Length) return [];
                    var index = values.Take(4).Select(v => int.Parse(v, CultureInfo.InvariantCulture)).ToArray();
                    var dimensions = new[] { thp.Length, water.Length, gas.Length, alq.Length };
                    if (index.Where((v, i) => v < 1 || v > dimensions[i]).Any() || !identities.Add(string.Join(",", index))
                        || values.Skip(4).Select(Number).Any(v => v <= 0 || v >= 1e10)) return [];
                }
                // Exact row count is mandatory; another numeric row cannot be silently ignored.
                while (tokens.MoveNext())
                {
                    var next = tokens.Current.Value;
                    if (next.StartsWith("--", StringComparison.Ordinal)) continue;
                    if (!Regex.IsMatch(next, "^[A-Z][A-Z0-9-]{0,7}$", RegexOptions.CultureInvariant)) return [];
                    break;
                }
                if (result.Any(t => t.Number == id)) return [];
                result.Add(new(id, datum, header[2], header[3], header[4], header[6], unit, file,
                    1 + Regex.Matches(text[..match.Index], "\r\n|\r|\n").Count,
                    Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text[match.Index..last]))), flow, thp, water, gas, alq));
            }
            return result;
        }
        catch (Exception e) when (e is FormatException or OverflowException) { return []; }
    }
    private static double Number(string value)
    {
        if (!double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var number) || !double.IsFinite(number)) throw new FormatException();
        return number;
    }
}
