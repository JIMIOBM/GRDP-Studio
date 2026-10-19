using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseThermalInjectionReader
{
    internal sealed record Target(EclipseThermalInjectionRecord Record, int QualityStart, int QualityLength, int TemperatureStart, int TemperatureLength, int RateStart, int RateLength, int PressureStart, int PressureLength);
    internal sealed record Snapshot(EclipseThermalInjectionContext Context, byte[] SourceBytes, IReadOnlyList<Target> Targets);
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
        var model = EclipseThermalReader.Read(package);
        if (model is null || model.Solver != 300 || model.ComponentCount != 3 || model.FluidModel != "LIVEOIL") return null;
        var bytes = File.ReadAllBytes(package.MainFile);
        var text = new UTF8Encoding(false, true).GetString(bytes);
        if (text.Contains('\0') || EclipseIncludeParser.Parse(text).Count != 0) return null;
        var matches = Regex.Matches(text, "(?im)^[ \\t]*([A-Z][A-Z0-9-]*)[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$").ToArray();
        var blocks = matches.Select((m, n) => new Block(m.Groups[1].Value.ToUpperInvariant(), m.Index, m.Index + m.Length,
            n + 1 < matches.Length ? matches[n + 1].Index : text.Length)).ToArray();
        if (blocks.Any(b => b.Name is "RESTART" or "ACTION" or "ACTIONX" or "UDQ" or "UDADIMS" or "PATHS" or "ENDINC"
            or "SKIP" or "SKIP100" or "SKIP300" or "ENDSKIP" or "TEMP" or "LGR" or "LGRON" or "LGRFREE")) return null;
        var units = blocks.Where(b => b.Name is "FIELD" or "METRIC").ToArray();
        var schedules = blocks.Where(b => b.Name == "SCHEDULE").ToArray();
        if (units.Length != 1 || schedules.Length != 1) return null;
        var field = units[0].Name == "FIELD";
        var schedule = blocks.Where(b => b.Start > schedules[0].Start).ToArray();
        var allowed = new HashSet<string> { "RPTPRINT", "RPTSCHED", "RPTRST", "WELSPECS", "COMPDAT", "WCONINJE", "WINJTEMP", "WCONPROD", "TSTEP", "DATES", "END" };
        var clocks = schedule.Where(b => b.Name is "TSTEP" or "DATES").ToArray();
        if (clocks.Length == 0 || schedule.Any(b => !allowed.Contains(b.Name)) || schedule.Count(b => b.Name == "END") != 1) return null;
        if (schedule.Any(b => b.Start > clocks[0].Start && b.Name is "WELSPECS" or "COMPDAT" or "WCONINJE" or "WINJTEMP" or "WCONPROD")) return null;
        var specs = schedule.Where(b => b.Name == "WELSPECS").SelectMany(b => Rows(text, b)).ToArray();
        if (specs.Length == 0 || specs.Any(r => r.Values.Count < 2 || !Name(r.Values[0].Value))
            || specs.Select(r => r.Values[0].Value).Distinct().Count() != specs.Length) return null;
        var wells = specs.Select(r => r.Values[0].Value).ToHashSet();
        var controls = schedule.Where(b => b.Name == "WCONINJE").SelectMany(b => Rows(text, b)).ToArray();
        if (controls.Length == 0 || controls.Any(r => r.Values.Count != 7 || !Name(r.Values[0].Value) || !wells.Contains(r.Values[0].Value)
            || r.Values[1].Value is not ("WATER" or "WAT") || r.Values[2].Value != "OPEN" || r.Values[3].Value != "RATE"
            || !Positive(r.Values[4].Value, out _) || r.Values[5].Value != "" || !Positive(r.Values[6].Value, out _))
            || controls.Select(r => r.Values[0].Value).Distinct().Count() != controls.Length) return null;
        var producers = schedule.Where(b => b.Name == "WCONPROD").SelectMany(b => Rows(text, b)).ToArray();
        if (producers.Any(r => r.Values.Count < 3 || !Name(r.Values[0].Value) || !wells.Contains(r.Values[0].Value)
            || r.Values[1].Value != "OPEN" || r.Values[2].Value != "BHP")) return null;
        var perfs = schedule.Where(b => b.Name == "COMPDAT").SelectMany(b => Rows(text, b)).ToArray();
        var targets = new List<Target>(); var seen = new HashSet<string>();
        foreach (var row in schedule.Where(b => b.Name == "WINJTEMP").SelectMany(b => Rows(text, b)))
        {
            var v = row.Values;
            if (v.Count != 3 || !Name(v[0].Value) || !seen.Add(v[0].Value) || !wells.Contains(v[0].Value)
                || v.Skip(1).Any(t => t.Count != 1 || text.Substring(t.Start, t.Length) != t.Value)
                || !double.TryParse(v[1].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var quality) || !double.IsFinite(quality) || quality is < 0 or > 1
                || !double.TryParse(v[2].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var temperature) || !double.IsFinite(temperature)
                || temperature <= (field ? -459.67 : -273.15) || temperature > 1_000_000
                || producers.Any(r => r.Values[0].Value == v[0].Value)) return null;
            var control = controls.Where(r => r.Values[0].Value == v[0].Value).ToArray();
            if (control.Length != 1 || control[0].Line >= row.Line) return null;
            var connections = perfs.Where(r => r.Values.Count > 0 && r.Values[0].Value == v[0].Value).ToArray();
            if (connections.Length == 0 || connections.Any(r => r.Values.Count < 6 || r.Values[5].Value is not ("OPEN" or ""))) return null;
            Positive(control[0].Values[4].Value, out var rate); Positive(control[0].Values[6].Value, out var pressure);
            targets.Add(new(new(v[0].Value, row.Line, quality, temperature, field ? "degF" : "degC", rate,
                field ? "STB/day" : "sm3/day", pressure, control[0].Line), v[1].Start, v[1].Length, v[2].Start, v[2].Length,
                control[0].Values[4].Start, control[0].Values[4].Length, control[0].Values[6].Start, control[0].Values[6].Length));
        }
        if (targets.Count is < 1 or > 1000) return null;
        return new(new("eclipse-thermal-injection-context/1", Path.GetFileName(package.MainFile), Convert.ToHexStringLower(SHA256.HashData(bytes)),
            bytes.Length, 300, model.ComponentCount, model.ComponentNames, model.FluidModel, targets.Select(t => t.Record).ToArray()), bytes, targets);
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
