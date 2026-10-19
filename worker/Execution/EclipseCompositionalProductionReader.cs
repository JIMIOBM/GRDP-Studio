using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompositionalProductionReader
{
    internal sealed record Target(EclipseCompositionalProductionRecord Record, int Start, int Length, int PressureStart, int PressureLength);
    internal sealed record Snapshot(EclipseCompositionalProductionContext Context, byte[] SourceBytes, IReadOnlyList<Target> Targets);
    private sealed record Token(string Value, int Start, int Length, int Count = 1);
    private sealed record Row(IReadOnlyList<Token> Values, int Line);
    private sealed record Block(string Name, int Start, int BodyStart, int End);
    private static bool Name(string s) => Regex.IsMatch(s, "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$", RegexOptions.CultureInvariant);
    private static bool Positive(string s, out double value) => double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out value)
        && double.IsFinite(value) && value is > 0 and <= 100_000_000;

    internal static Snapshot? Read(EclipseDeckPackage package)
    {
        try { return ReadCore(package); }
        catch (Exception error) when (error is FormatException or DecoderFallbackException or EclipseDeckPackageException) { return null; }
    }
    private static Snapshot? ReadCore(EclipseDeckPackage package)
    {
        if (package.Files.Count != 1 || Directory.EnumerateFiles(package.Root, "*", SearchOption.AllDirectories).Take(2).Count() != 1
            || !Path.GetFullPath(package.MainFile).Equals(Path.GetFullPath(package.Files[0]), StringComparison.OrdinalIgnoreCase)
            || !Path.GetFullPath(Path.GetDirectoryName(package.MainFile)!).Equals(Path.GetFullPath(package.Root), StringComparison.OrdinalIgnoreCase)
            || new FileInfo(package.MainFile).Length is < 1 or > 4 * 1024 * 1024) return null;
        var sourceName = Path.GetFileName(package.MainFile);
        if (sourceName.Length > 128 || !Regex.IsMatch(sourceName, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$", RegexOptions.CultureInvariant)) return null;
        var model = EclipseSimulationReader.Read(package);
        if (model is null || model.Solver != 300 || model.ModelType != "COMPOSITIONAL" || model.ComponentCount != 9 || model.EquationOfState != "PR") return null;
        var bytes = File.ReadAllBytes(package.MainFile);
        var text = new UTF8Encoding(false, true).GetString(bytes);
        if (text.Contains('\0') || EclipseIncludeParser.Parse(text).Count != 0) return null;
        var matches = Regex.Matches(text, "(?im)^[ \\t]*([A-Z][A-Z0-9-]*)[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$").ToArray();
        var blocks = matches.Select((m, n) => new Block(m.Groups[1].Value.ToUpperInvariant(), m.Index, m.Index + m.Length,
            n + 1 < matches.Length ? matches[n + 1].Index : text.Length)).ToArray();
        if (blocks.Any(b => b.Name is "RESTART" or "ACTION" or "ACTIONX" or "UDQ" or "UDADIMS" or "PATHS" or "ENDINC"
            or "SKIP" or "SKIP100" or "SKIP300" or "ENDSKIP" or "THERMAL" or "TEMP" or "LGR" or "LGRON" or "LGRFREE")) return null;
        var unitBlocks = blocks.Where(b => b.Name is "FIELD" or "METRIC").ToArray();
        var schedules = blocks.Where(b => b.Name == "SCHEDULE").ToArray();
        if (unitBlocks.Length != 1 || schedules.Length != 1) return null;
        var schedule = blocks.Where(b => b.Start > schedules[0].Start).ToArray();
        var allowed = new HashSet<string> { "RPTSCHED", "RPTRST", "SEPCOND", "WELSPECS", "WSEPCOND", "COMPDAT", "WCONPROD",
            "WELTARG", "GRUPSALE", "GCONINJE", "GINJGAS", "WCONINJE", "TSTEP", "DATES", "SAVE", "WELOPEN", "END" };
        var clocks = schedule.Where(b => b.Name is "TSTEP" or "DATES").ToArray();
        if (clocks.Length == 0 || schedule.Any(b => !allowed.Contains(b.Name)) || schedule.Count(b => b.Name == "END") != 1) return null;
        var firstTime = clocks[0].Start;
        if (schedule.Any(b => b.Start > firstTime && b.Name is "WELSPECS" or "COMPDAT" or "WCONPROD" or "WELTARG" or "WCONINJE")) return null;
        var specs = schedule.Where(b => b.Name == "WELSPECS").SelectMany(b => Rows(text, b)).ToArray();
        if (specs.Length == 0 || specs.Any(r => r.Values.Count < 2 || !Name(r.Values[0].Value))
            || specs.Select(r => r.Values[0].Value).Distinct().Count() != specs.Length) return null;
        var wells = specs.Select(r => r.Values[0].Value).ToHashSet();
        var injectors = schedule.Where(b => b.Name == "WCONINJE").SelectMany(b => Rows(text, b)).ToArray();
        if (injectors.Any(r => r.Values.Count < 4 || !Name(r.Values[0].Value))) return null;
        var injectionNames = injectors.Select(r => r.Values[0].Value).ToHashSet();
        var perfs = schedule.Where(b => b.Name == "COMPDAT").SelectMany(b => Rows(text, b)).ToArray();
        var pressures = schedule.Where(b => b.Name == "WELTARG").SelectMany(b => Rows(text, b)).ToArray();
        var targets = new List<Target>(); var seen = new HashSet<string>();
        foreach (var block in schedule.Where(b => b.Name == "WCONPROD"))
        {
            var rows = Rows(text, block); if (rows.Count == 0) return null;
            foreach (var row in rows)
            {
                var v = row.Values;
                if (v.Count is < 6 or > 12 || !Name(v[0].Value) || !wells.Contains(v[0].Value) || injectionNames.Contains(v[0].Value)
                    || !seen.Add(v[0].Value) || v[1].Value != "OPEN" || v[2].Value != "GRAT" || v[3].Value.Length != 0 || v[4].Value.Length != 0
                    || v[5].Count != 1 || !Positive(v[5].Value, out var rate) || v.Skip(6).Any(t => t.Value.Length != 0 && t.Value != "0")) return null;
                var limits = pressures.Where(r => r.Values.Count > 0 && r.Values[0].Value == v[0].Value).ToArray();
                if (limits.Length != 1 || limits[0].Values.Count != 3 || limits[0].Values[1].Value != "BHP"
                    || limits[0].Values[2].Start <= v[5].Start || !Positive(limits[0].Values[2].Value, out var pressure)) return null;
                var wellPerfs = perfs.Where(r => r.Values.Count > 0 && r.Values[0].Value == v[0].Value).ToArray();
                if (wellPerfs.Length == 0 || wellPerfs.Any(r => r.Values.Count < 6 || r.Values[5].Value is not ("" or "OPEN"))) return null;
                if (schedule.Where(b => b.Name == "WELOPEN").SelectMany(b => Rows(text, b)).Any(r => r.Values.Count == 0
                    || !Name(r.Values[0].Value) || r.Values[0].Value == v[0].Value)) return null;
                targets.Add(new(new(v[0].Value, row.Line, "GRAT", unitBlocks[0].Name == "FIELD" ? "Mscf/day" : "sm3/day", rate, pressure, limits[0].Line),
                    v[5].Start, v[5].Length, limits[0].Values[2].Start, limits[0].Values[2].Length));
            }
        }
        if (targets.Count is < 1 or > 1000) return null;
        return new(new("eclipse-compositional-production-context/1", Path.GetFileName(package.MainFile),
            Convert.ToHexStringLower(SHA256.HashData(bytes)), bytes.Length, 300, model.ComponentCount, model.ComponentNames,
            model.EquationOfState, targets.Select(t => t.Record).ToArray()), bytes, targets);
    }
    private static IReadOnlyList<Row> Rows(string text, Block block)
    {
        var result = new List<Row>(); var tokens = new List<Token>(); var line = 0; var terminated = false;
        foreach (Match m in Regex.Matches(text[block.BodyStart..block.End], """--[^\r\n]*|'(?:[^']|'')*'|"(?:[^"]|"")*"|/|[^\s/]+"""))
        {
            if (m.Value.StartsWith("--", StringComparison.Ordinal)) continue;
            if (m.Value == "/")
            {
                if (tokens.Count == 0) { terminated = true; break; }
                result.Add(new(tokens.ToArray(), line)); tokens.Clear();
                if (result.Count > 4000) throw new FormatException();
                continue;
            }
            var value = m.Value;
            if (value.Length >= 2 && value[0] is '\'' or '"' && value[^1] == value[0]) value = value[1..^1];
            if (tokens.Count == 0) line = 1 + Regex.Matches(text[..(block.BodyStart + m.Index)], "\r\n|\r|\n").Count;
            var count = 1;
            if (tokens.Count > 0 && Regex.Match(value, "^(\\d+)\\*(.*)$") is { Success: true } repeated)
            {
                if (!int.TryParse(repeated.Groups[1].Value, out count) || count is < 1 or > 128) throw new FormatException();
                value = repeated.Groups[2].Value;
            }
            if (tokens.Count + count > 128) throw new FormatException();
            for (var n = 0; n < count; n++) tokens.Add(new(value, block.BodyStart + m.Index, m.Length, count));
        }
        if (!terminated || tokens.Count != 0) throw new FormatException();
        return result;
    }
}
