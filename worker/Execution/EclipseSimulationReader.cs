using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using System.Text.Json.Serialization;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record EclipseSimulationMetadata(
    [property: JsonPropertyName("solver")] int Solver,
    [property: JsonPropertyName("modelType")] string ModelType,
    [property: JsonPropertyName("componentCount")] int ComponentCount,
    [property: JsonPropertyName("componentNames")] IReadOnlyList<string> ComponentNames,
    [property: JsonPropertyName("equationOfState")] string EquationOfState);

// This first E300 contract requires explicit compositional metadata. It does not
// infer thermal support or change the interpretation of legacy E100 decks.
public static class EclipseSimulationReader
{
    private static readonly Regex Heading = new("(?im)^[ \\t]*([A-Z][A-Z0-9-]{0,15})[ \\t]*(?:=+[ \\t]*)?\\r?$", RegexOptions.CultureInvariant);
    private static readonly Regex Token = new("'(?:(?:[^']|'')*)'|\"(?:(?:[^\"]|\"\")*)\"|/|[^\\s/]+", RegexOptions.CultureInvariant);
    private static readonly string[] Sections = ["RUNSPEC", "GRID", "EDIT", "PROPS", "REGIONS", "SOLUTION", "SUMMARY", "SCHEDULE"];

    public static EclipseSimulationMetadata? Read(EclipseDeckPackage package)
    {
        if (package.Files.Sum(f => new FileInfo(f).Length) > 64L * 1024 * 1024) Fail("The model package exceeds the simulation metadata bound.");
        var text = string.Join("\n", package.Files.Select(f => StripComments(EclipseDeckPackageResolver.ReadUtf8(f))));
        return ReadText(text);
    }

    internal static EclipseSimulationMetadata? ReadText(string source)
    {
        var text = StripComments(source);
        var headings = Heading.Matches(text).Cast<Match>().ToArray();
        var comps = headings.Where(m => m.Groups[1].Value.Equals("COMPS", StringComparison.OrdinalIgnoreCase)).ToArray();
        if (comps.Length == 0) return null;
        var section = "";
        var units = new List<string>();
        var declarations = new List<(string Name, string Section, string[] Values)>();
        foreach (var heading in headings)
        {
            var key = heading.Groups[1].Value.ToUpperInvariant();
            if (Sections.Contains(key)) { section = key; continue; }
            if (section == "RUNSPEC" && key is "FIELD" or "METRIC" or "LAB" or "PVT-M") units.Add(key);
            if (key is "BLACKOIL" or "THERMAL" or "TEMP" or "LGR" or "LGRON" or "LGRFREE")
                Fail("This E300 card supports a global, isothermal compositional model only.");
            if (key is not ("COMPS" or "NCOMPS" or "CNAMES" or "EOS")) continue;
            var tokens = Token.Matches(text[(heading.Index + heading.Length)..]).Cast<Match>().Take(102).Select(m => m.Value).ToArray();
            var end = Array.IndexOf(tokens, "/");
            if (end < 0 || end > 100) Fail("A compositional metadata record is missing its bounded terminator.");
            declarations.Add((key, section, tokens.Take(end).Select(Unquote).ToArray()));
        }
        if (units.Count != 1 || units[0] is not ("FIELD" or "METRIC")) Fail("The first E300 card requires one explicit FIELD or METRIC unit declaration.");
        var countRecord = declarations.Where(d => d.Name == "COMPS").ToArray();
        if (countRecord.Length != 1 || countRecord[0].Section != "RUNSPEC" || countRecord[0].Values.Length != 1 ||
            !int.TryParse(countRecord[0].Values[0], NumberStyles.None, CultureInfo.InvariantCulture, out var count) || count is < 1 or > 100)
            Fail("COMPS must specify one literal component count from 1 to 100 in RUNSPEC.");
        // Parse again after the guard to keep definite assignment explicit.
        count = int.Parse(countRecord[0].Values[0], CultureInfo.InvariantCulture);
        var ncomps = declarations.Where(d => d.Name == "NCOMPS").ToArray();
        if (ncomps.Length > 1 || ncomps.Length == 1 && (ncomps[0].Section != "PROPS" || ncomps[0].Values.Length != 1 || ncomps[0].Values[0] != count.ToString(CultureInfo.InvariantCulture)))
            Fail("If present, PROPS NCOMPS must agree with RUNSPEC COMPS.");
        var names = declarations.Where(d => d.Name == "CNAMES").ToArray();
        if (names.Length != 1 || names[0].Section != "PROPS" || names[0].Values.Length != count ||
            names[0].Values.Any(n => n.Length is < 1 or > 8 || n.Any(c => c < 32 || c > 126 || c is '/' or '\\')) ||
            names[0].Values.Distinct(StringComparer.Ordinal).Count() != count)
            Fail("CNAMES must provide one unique, safe name of at most eight characters per component.");
        var eos = declarations.Where(d => d.Name == "EOS").ToArray();
        if (eos.Length is < 1 or > 2 || eos.Any(d => d.Section is not ("RUNSPEC" or "PROPS") || d.Values.Length != 1 || d.Values[0].ToUpperInvariant() != "PR") ||
            eos.Select(d => d.Section).Distinct().Count() != eos.Length)
            Fail("The first compositional card requires explicit, consistent Peng-Robinson EOS metadata.");
        return new(300, "COMPOSITIONAL", count, names[0].Values, "PR");
    }

    private static string Unquote(string value) => value.Length >= 2 && value[0] is '\'' or '"'
        ? value[1..^1].Replace("''", "'").Replace("\"\"", "\"") : value;

    internal static string StripComments(string text)
    {
        var result = new StringBuilder(text.Length);
        var quote = '\0';
        for (var i = 0; i < text.Length; i++)
        {
            var c = text[i];
            if (quote != '\0')
            {
                result.Append(c);
                if (c == quote) { if (i + 1 < text.Length && text[i + 1] == quote) result.Append(text[++i]); else quote = '\0'; }
            }
            else if (c is '\'' or '"') { quote = c; result.Append(c); }
            else if (c == '-' && i + 1 < text.Length && text[i + 1] == '-')
            { while (i < text.Length && text[i] is not ('\r' or '\n')) i++; if (i < text.Length) result.Append(text[i]); }
            else result.Append(c);
        }
        if (quote != '\0') Fail("A quoted compositional metadata value is unterminated.");
        return result.ToString();
    }

    private static void Fail(string message) => throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_METADATA_INVALID", message);
}
