using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompositionalReinjectionReader
{
    internal sealed record Snapshot(EclipseCompositionalReinjectionContext Context, byte[] SourceBytes, int Start, int Length, int SalesStart, int SalesLength, int InjectionPressureStart, int InjectionPressureLength);
    private sealed record Token(string Value, int Start, int Length, int Count);
    private sealed record Row(IReadOnlyList<Token> Values, int Line);
    private sealed record Block(string Name, int Start, int BodyStart, int End);
    private static bool Number(string s, out double n) => double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out n) && double.IsFinite(n);
    private static bool Positive(string s, out double n) => Number(s, out n) && n is > 0 and <= 100_000_000;
    private static bool Field(string s) => string.Equals(s, "FIELD", StringComparison.OrdinalIgnoreCase);
    internal static Snapshot? Read(EclipseDeckPackage package)
    {
        try { return ReadCore(package); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or FormatException or OverflowException or DecoderFallbackException) { return null; }
    }
    private static Snapshot? ReadCore(EclipseDeckPackage package)
    {
        var production = EclipseCompositionalProductionReader.Read(package);
        if (production is null || production.Targets.Count != 1 || production.Context.Records[0].Unit != "Mscf/day") return null;
        var producer = production.Context.Records[0];
        if (producer.Well != producer.Well.ToUpperInvariant()) return null;
        var bytes = File.ReadAllBytes(package.MainFile);
        if (bytes.Length != production.Context.SourceSizeBytes || Convert.ToHexStringLower(SHA256.HashData(bytes)) != production.Context.SourceSha256) return null;
        var text = new UTF8Encoding(false, true).GetString(bytes);
        var matches = Regex.Matches(text, "(?im)^[ \\t]*([A-Z][A-Z0-9-]*)[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$").ToArray();
        var all = matches.Select((m, j) => new Block(m.Groups[1].Value.ToUpperInvariant(), m.Index, m.Index + m.Length,
            j + 1 < matches.Length ? matches[j + 1].Index : text.Length)).ToArray();
        var schedule = all.Single(b => b.Name == "SCHEDULE");
        var blocks = all.Where(b => b.Start > schedule.Start).ToArray();
        var clocks = blocks.Where(b => b.Name is "TSTEP" or "DATES").ToArray();
        if (clocks.Length == 0 || clocks.Any(b => b.Name != "TSTEP")) return null;
        var firstClock = clocks[0].Start;
        var groups = blocks.Where(b => b.Name == "GCONINJE").ToArray();
        var streams = blocks.Where(b => b.Name == "GINJGAS").ToArray();
        var sales = blocks.Where(b => b.Name == "GRUPSALE").ToArray();
        var injections = blocks.Where(b => b.Name == "WCONINJE").ToArray();
        if (groups.Length != 1 || streams.Length != 1 || sales.Length != 1 || injections.Length != 1 ||
            new[] { groups[0], streams[0], sales[0], injections[0] }.Any(b => b.Start > firstClock)) return null;
        var groupRows = Rows(text, groups[0]); var streamRows = Rows(text, streams[0]); var saleRows = Rows(text, sales[0]); var injectionRows = Rows(text, injections[0]);
        if (groupRows.Count != 1 || streamRows.Count != 1 || saleRows.Count != 1 || injectionRows.Count != 1) return null;
        var g = groupRows[0]; var gv = g.Values; var gas = streamRows[0]; var sv = gas.Values;
        var sale = saleRows[0]; var saleValues = sale.Values; var injection = injectionRows[0]; var iv = injection.Values;
        if (gv.Count != 6 || !Field(gv[0].Value) || gv[1].Value != "GAS" || gv[2].Value != "REIN" || gv[3].Value != "" || gv[4].Value != "" ||
            gv[5].Count != 1 || !Number(gv[5].Value, out var fraction) || fraction is < 0 or > 1 ||
            sv.Count != 3 || !Field(sv[0].Value) || sv[1].Value != "GV" || !Field(sv[2].Value) ||
            saleValues.Count != 2 || !Field(saleValues[0].Value) || !Number(saleValues[1].Value, out var sold) || sold is < 0 or > 100_000_000 ||
            iv.Count != 7 || iv[0].Value != iv[0].Value.ToUpperInvariant() || iv[0].Value == producer.Well || iv[1].Value != "GAS" || iv[2].Value != "OPEN" ||
            iv[3].Value != "GRUP" || iv[4].Value != "" || iv[5].Value != "" || iv[6].Count != 1 || !Positive(iv[6].Value, out var upper)) return null;
        var injector = iv[0].Value;
        var specs = blocks.Where(b => b.Name == "WELSPECS").SelectMany(b => Rows(text, b)).ToArray();
        if (specs.Length != 2 || specs.Any(r => r.Values.Count < 2 || !Field(r.Values[1].Value)) ||
            !specs.Select(r => r.Values[0].Value).ToHashSet().SetEquals([injector, producer.Well])) return null;
        var completions = blocks.Where(b => b.Name == "COMPDAT").SelectMany(b => Rows(text, b)).Where(r => r.Values.Count > 0 && r.Values[0].Value == injector).ToArray();
        if (completions.Length == 0 || completions.Any(r => r.Values.Count < 6 || r.Values[5].Value is not ("" or "OPEN") || r.Values[0].Start > iv[0].Start)) return null;
        var changes = blocks.Where(b => b.Name == "WELOPEN").ToArray();
        if (changes.Length != 1) return null;
        var shutRows = Rows(text, changes[0]);
        if (shutRows.Count != 1 || shutRows[0].Values.Count != 2 || shutRows[0].Values[0].Value != injector || shutRows[0].Values[1].Value != "SHUT") return null;
        double elapsed = 0, shutDays = -1;
        foreach (var b in blocks)
        {
            if (b.Name == "TSTEP")
            {
                var rows = Rows(text, b, false); if (rows.Count != 1 || rows[0].Values.Count == 0) return null;
                foreach (var step in rows[0].Values)
                {
                    if (!Positive(step.Value, out var days)) return null;
                    elapsed += days;
                    if (!double.IsFinite(elapsed) || elapsed > 10_000_000) return null;
                }
            }
            if (b == changes[0]) shutDays = elapsed;
        }
        if (shutDays <= 0 || shutDays >= elapsed) return null;
        var record = new EclipseCompositionalReinjectionRecord("FIELD", "GAS", "REIN", g.Line, fraction,
            "GV", "FIELD", gas.Line, sold, sale.Line, "Mscf/day", "psia", injector, upper, injection.Line,
            producer.Well, producer.OriginalGasRate, producer.BhpLimit, shutDays, shutRows[0].Line);
        var c = production.Context;
        return new(new("eclipse-compositional-reinjection-context/1", c.SourceFile, c.SourceSha256, c.SourceSizeBytes, c.Solver,
            c.ComponentCount, c.ComponentNames, c.EquationOfState, [record]), bytes, gv[5].Start, gv[5].Length, saleValues[1].Start, saleValues[1].Length, iv[6].Start, iv[6].Length);
    }
    private static IReadOnlyList<Row> Rows(string text, Block block, bool blank = true)
    {
        var result = new List<Row>(); var values = new List<Token>(); var line = 0; var ended = false;
        foreach (Match m in Regex.Matches(text[block.BodyStart..block.End], """--[^\r\n]*|'(?:[^']|'')*'|"(?:[^"]|"")*"|/|[^\s/]+"""))
        {
            if (m.Value.StartsWith("--", StringComparison.Ordinal)) continue;
            if (m.Value == "/")
            {
                if (values.Count == 0) { ended = true; break; }
                result.Add(new(values.ToArray(), line)); values.Clear();
                if (result.Count > 2000) throw new FormatException();
                continue;
            }
            var v = m.Value;
            if (v.Length >= 2 && v[0] is '\'' or '"' && v[^1] == v[0]) v = v[1..^1];
            if (values.Count == 0) line = 1 + Regex.Matches(text[..(block.BodyStart + m.Index)], "\r\n|\r|\n").Count;
            var count = 1;
            if ((values.Count > 0 || !blank) && Regex.Match(v, "^(\\d+)\\*(.*)$") is { Success: true } repeated)
            {
                if (!int.TryParse(repeated.Groups[1].Value, out count) || count is < 1 or > 128) throw new FormatException();
                v = repeated.Groups[2].Value;
            }
            if (values.Count + count > 128) throw new FormatException();
            for (var j = 0; j < count; j++) values.Add(new(v, block.BodyStart + m.Index, m.Length, count));
        }
        if (blank && !ended || values.Count != 0) throw new FormatException();
        return result;
    }
}
