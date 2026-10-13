using System.Globalization;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record EclipseThermalMetadata(
    [property: JsonPropertyName("solver")] int Solver,
    [property: JsonPropertyName("modelType")] string ModelType,
    [property: JsonPropertyName("componentCount")] int ComponentCount,
    [property: JsonPropertyName("componentNames")] IReadOnlyList<string> ComponentNames,
    [property: JsonPropertyName("fluidModel")] string FluidModel,
    [property: JsonPropertyName("summaryProgramIdentifier")] int SummaryProgramIdentifier);

public static class EclipseThermalReader
{
    private static readonly Regex Heading = new("(?im)^[ \\t]*([A-Z][A-Z0-9-]{0,15})[ \\t]*(?:=+[ \\t]*)?\\r?$", RegexOptions.CultureInvariant);
    private static readonly Regex Token = new("'(?:(?:[^']|'')*)'|\"(?:(?:[^\"]|\"\")*)\"|/|[^\\s/]+", RegexOptions.CultureInvariant);
    private static readonly string[] Sections = ["RUNSPEC", "GRID", "EDIT", "PROPS", "REGIONS", "SOLUTION", "SUMMARY", "SCHEDULE"];

    public static EclipseThermalMetadata? Read(EclipseDeckPackage package)
    {
        if (package.Files.Sum(f => new FileInfo(f).Length) > 64L * 1024 * 1024)
            Fail("The model package exceeds the thermal metadata bound.");
        return ReadText(string.Join("\n", package.Files.Select(EclipseDeckPackageResolver.ReadUtf8)));
    }

    internal static EclipseThermalMetadata? ReadText(string source)
    {
        var text = EclipseSimulationReader.StripComments(source);
        var headings = Heading.Matches(text).Cast<Match>().ToArray();
        if (!headings.Any(m => m.Groups[1].Value.Equals("THERMAL", StringComparison.OrdinalIgnoreCase))) return null;
        var section = "";
        var flags = new List<(string Name, string Section)>();
        var declarations = new List<(string Name, string Section, string[] Values)>();
        foreach (var heading in headings)
        {
            var key = heading.Groups[1].Value.ToUpperInvariant();
            if (Sections.Contains(key)) { section = key; continue; }
            flags.Add((key, section));
            if (key is "BLACKOIL" or "TEMP" or "DEADOIL" or "RADIAL" or "LGR" or "LGRON" or "LGRFREE" or "REACTION" or "CO2STORE" or "EOS")
                Fail("This thermal card supports a global Cartesian LIVEOIL thermal-property model without reactions or EOS regions.");
            if (key is not ("COMPS" or "NCOMPS" or "CNAMES")) continue;
            var tokens = Token.Matches(text[(heading.Index + heading.Length)..]).Cast<Match>().Take(102).Select(m => m.Value).ToArray();
            var end = Array.IndexOf(tokens, "/");
            if (end < 0 || end > 100) Fail("Thermal component metadata is missing its bounded terminator.");
            declarations.Add((key, section, tokens.Take(end).Select(Unquote).ToArray()));
        }
        var thermal = flags.Where(f => f.Name == "THERMAL").ToArray();
        if (thermal.Length != 1 || thermal[0].Section != "RUNSPEC") Fail("THERMAL must occur exactly once in RUNSPEC.");
        var live = flags.Where(f => f.Name == "LIVEOIL").ToArray();
        if (live.Length > 1 || live.Any(f => f.Section != "RUNSPEC")) Fail("LIVEOIL must be a unique RUNSPEC declaration or its official default.");
        var units = flags.Where(f => f.Name is "FIELD" or "METRIC" or "LAB" or "PVT-M").ToArray();
        if (units.Length != 1 || units[0].Section != "RUNSPEC" || units[0].Name is not ("FIELD" or "METRIC"))
            Fail("One explicit FIELD or METRIC declaration is required.");
        foreach (var phase in new[] { "OIL", "WATER", "GAS" })
            if (flags.Count(f => f.Name == phase && f.Section == "RUNSPEC") != 1) Fail("The initial steam-drive card requires explicit OIL, WATER and GAS phases.");
        var comps = declarations.Where(d => d.Name == "COMPS").ToArray();
        if (comps.Length != 1 || comps[0].Section != "RUNSPEC" || comps[0].Values.Length != 1 ||
            !int.TryParse(comps[0].Values[0], NumberStyles.None, CultureInfo.InvariantCulture, out var count) || count is < 1 or > 100)
            Fail("Thermal COMPS must declare one literal oil-component count from 1 to 100.");
        count = int.Parse(comps[0].Values[0], CultureInfo.InvariantCulture);
        var ncomps = declarations.Where(d => d.Name == "NCOMPS").ToArray();
        if (ncomps.Length > 1 || ncomps.Length == 1 && (ncomps[0].Section != "PROPS" || ncomps[0].Values.Length != 1 || ncomps[0].Values[0] != count.ToString(CultureInfo.InvariantCulture)))
            Fail("Optional NCOMPS must match thermal COMPS.");
        var names = declarations.Where(d => d.Name == "CNAMES").ToArray();
        if (names.Length != 1 || names[0].Section != "PROPS" || names[0].Values.Length != count ||
            names[0].Values.Any(n => n.Length is < 1 or > 8 || n.Any(c => c < 32 || c > 126 || c is '/' or '\\')) ||
            names[0].Values.Distinct(StringComparer.Ordinal).Count() != count)
            Fail("CNAMES must preserve the complete unique oil-component name list.");
        return new(300, "THERMAL", count, names[0].Values, "LIVEOIL", 500);
    }

    private static string Unquote(string value) => value.Length >= 2 && value[0] is '\'' or '"' ? value[1..^1].Replace("''", "'").Replace("\"\"", "\"") : value;
    private static void Fail(string message) => throw new EclipseDeckPackageException("ECLIPSE_THERMAL_METADATA_INVALID", message);
}
