using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompositionalFluidPropertyReader
{
    internal sealed record Target(int ComponentIndex, int Start, int Length);
    internal sealed record Snapshot(EclipseCompositionalFluidPropertyContext Context, byte[] SourceBytes, IReadOnlyList<Target> Targets);
    private sealed record Block(string Name, int Start, int BodyStart, int End);
    private sealed record Token(string Value, int Start, int Length, int Line);
    internal static Snapshot? Read(EclipseDeckPackage package, string field = "PCRIT")
    {
        if (field is not ("PCRIT" or "TCRIT" or "ZCRIT" or "ACF" or "MW" or "OMEGAA" or "OMEGAB")) return null;
        try { return ReadCore(package, field); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or FormatException or OverflowException or DecoderFallbackException) { return null; }
    }
    private static Snapshot? ReadCore(EclipseDeckPackage package, string field)
    {
        var production = EclipseCompositionalProductionReader.Read(package);
        if (production is null || production.Context.Records.Count != 1 || production.Context.Records[0].Unit != "Mscf/day") return null;
        var bytes = File.ReadAllBytes(package.MainFile); var c = production.Context;
        if (bytes.Length != c.SourceSizeBytes || Convert.ToHexStringLower(SHA256.HashData(bytes)) != c.SourceSha256 ||
            c.ComponentNames.Count != 9 || c.ComponentNames.Distinct(StringComparer.Ordinal).Count() != 9 ||
            c.ComponentNames.Any(n => !Regex.IsMatch(n, "^[A-Za-z0-9][A-Za-z0-9+_.-]{0,15}$", RegexOptions.CultureInvariant))) return null;
        var text = new UTF8Encoding(false, true).GetString(bytes);
        var matches = Regex.Matches(text, "(?im)^[ \\t]*([A-Z][A-Z0-9-]*)[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$").ToArray();
        var blocks = matches.Select((m, j) => new Block(m.Groups[1].Value.ToUpperInvariant(), m.Index, m.Index + m.Length,
            j + 1 < matches.Length ? matches[j + 1].Index : text.Length)).ToArray();
        if (blocks.Any(b => b.Name is "EOSNUM" or "CO2STORE")) return null;
        if (EclipseCompositionalFluidPropertyParameters.IsEosField(field) && blocks.Any(b => b.Name is "LUMPDIMS" or "LUMPING" or "MWDETAIL" or "OMEGAADE" or "OMEGABDE")) return null;
        if (field == "ZCRIT" && blocks.Any(b => b.Name.StartsWith("VCRIT", StringComparison.Ordinal) || b.Name == "ZCRITVIS")) return null;
        foreach (var operation in blocks.Where(b => b.Name is "COPY" or "MULTIPLY" or "OPERATE" or "EQUALS" or "ADD"))
        {
            var body = Regex.Replace(text[operation.BodyStart..operation.End], "--[^\\r\\n]*", "");
            var affected = field == "ZCRIT" ? "(?:ZCRIT|VCRIT[A-Z0-9-]*|ZCRITVIS)" : field;
            if (Regex.IsMatch(body, "(?i)(?<![A-Z0-9_])" + affected + "(?![A-Z0-9_])", RegexOptions.CultureInvariant)) return null;
        }
        var props = blocks.Where(b => b.Name == "PROPS").ToArray(); var solution = blocks.Where(b => b.Name == "SOLUTION").ToArray();
        var tables = blocks.Where(b => b.Name == "TABDIMS").ToArray(); var critical = blocks.Where(b => b.Name == field).ToArray();
        var eos = blocks.Where(b => b.Name == "EOS").ToArray();
        if (props.Length != 1 || solution.Length != 1 || tables.Length != 1 || critical.Length != 1 || eos.Length is < 1 or > 2 ||
            critical[0].Start <= props[0].Start || critical[0].End > solution[0].Start) return null;
        var tableValues = Values(text, tables[0]);
        if (tableValues.Count != 4 || tableValues[0].Value != "1" || tableValues[1].Value != "1" ||
            tableValues.Any(t => !int.TryParse(t.Value, NumberStyles.None, CultureInfo.InvariantCulture, out var n) || n <= 0 || n > 100_000_000)) return null;
        foreach (var declaration in eos)
        {
            var eosValues = Values(text, declaration);
            if (eosValues.Count != 1 || eosValues[0].Value != "PR") return null;
        }
        var values = Values(text, critical[0]); if (values.Count != 9) return null;
        var records = new List<EclipseCompositionalFluidPropertyRecord>(); var targets = new List<Target>();
        for (var j = 0; j < values.Count; j++)
        {
            var t = values[j];
            if (!double.TryParse(t.Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var number) || !EclipseCompositionalFluidPropertyParameters.PropertyValue(field, number)) return null;
            records.Add(new(j + 1, c.ComponentNames[j], field, t.Line, EclipseCompositionalFluidPropertyParameters.PropertyUnit(field), number)); targets.Add(new(j + 1, t.Start, t.Length));
        }
        return new(new(EclipseCompositionalFluidPropertyParameters.IsEosField(field) ? "eclipse-compositional-eos-property-context/1" : field == "PCRIT" ? "eclipse-compositional-fluid-property-context/1" : field == "TCRIT" ? "eclipse-compositional-temperature-context/1" : field == "ZCRIT" ? "eclipse-compositional-critical-z-context/1" : "eclipse-compositional-acentric-factor-context/1", c.SourceFile, c.SourceSha256, c.SourceSizeBytes, c.Solver,
            c.ComponentCount, c.ComponentNames, c.EquationOfState, records), bytes, targets);
    }
    private static IReadOnlyList<Token> Values(string text, Block b)
    {
        var values = new List<Token>(); var ended = false;
        foreach (Match m in Regex.Matches(text[b.BodyStart..b.End], "--[^\\r\\n]*|/|[^\\s/]+"))
        {
            if (m.Value.StartsWith("--", StringComparison.Ordinal)) continue;
            if (m.Value == "/") { if (ended) throw new FormatException(); ended = true; continue; }
            if (ended || values.Count >= 16 || m.Value.Contains('*') || m.Value.Contains('\'') || m.Value.Contains('"')) throw new FormatException();
            var start = b.BodyStart + m.Index;
            values.Add(new(m.Value, start, m.Length, 1 + Regex.Matches(text[..start], "\\r\\n|\\r|\\n").Count));
        }
        if (!ended) throw new FormatException(); return values;
    }
}
