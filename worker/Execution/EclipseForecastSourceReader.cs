using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record EclipseForecastSource(string PhysicsSha256, string StartDate, string RestartCase, int RestartReport,
    IReadOnlyList<string> FutureDates);
public sealed record EclipseForecastInput(string RelativePath, string Sha256, int RestartReport,
    string RestartCase, IReadOnlyList<string> FutureDates);
public sealed record EclipseRestartSourceCatalog(string SchemaVersion, EclipseRestartCatalog Catalog,
    string PhysicsSha256, IReadOnlyList<EclipseForecastInput> ForecastInputs);

// Conservative compatibility: every static input token must match; only text comments,
// TITLE and formatted/unified file switches are excluded. No numerical tolerance.
public static class EclipseForecastSourceReader
{
    private sealed record Block(string Name, string Body);
    private static readonly Regex Heading = new("(?m)^[ \\t]*([A-Z][A-Z0-9-]{0,15})[ \\t]*\\r?$", RegexOptions.CultureInvariant);
    private static readonly Regex Tokens = new("'(?:(?:[^']|'')*)'|\"(?:(?:[^\"]|\"\")*)\"|/|[^\\s/]+", RegexOptions.CultureInvariant);
    private static readonly Regex Includes = new("(?im)^[ \\t]*INCLUDE[ \\t\\r\\n]+(?:'(?<one>[^']+)'|\"(?<two>[^\"]+)\"|(?<bare>[^\\s/]+))[ \\t\\r\\n]*/", RegexOptions.CultureInvariant);

    public static EclipseForecastSource Read(EclipseDeckPackage package, bool forecast)
    {
        long total = 0;
        var text = Expand(package.MainFile, package, new HashSet<string>(StringComparer.OrdinalIgnoreCase), ref total);
        var headings = Heading.Matches(text).Cast<Match>().ToArray();
        var blocks = headings.Select((m, i) => new Block(m.Groups[1].Value, text[(m.Index + m.Length)..(i + 1 < headings.Length ? headings[i + 1].Index : text.Length)])).ToArray();
        var solution = Array.FindIndex(blocks, b => b.Name == "SOLUTION");
        var schedule = Array.FindIndex(blocks, b => b.Name == "SCHEDULE");
        if (solution <= 0 || schedule <= solution || blocks.Count(b => b.Name == "SOLUTION") != 1 || blocks.Count(b => b.Name == "SCHEDULE") != 1
            || blocks.Any(b => b.Name is "COMPS" or "THERMAL" or "TEMP" or "LGR" or "LGRON" or "LGRFREE"))
            throw new InvalidDataException("Forecast compatibility requires one E100 static model and explicit SOLUTION/SCHEDULE sections.");
        var sections = blocks.Take(solution).Where(b => b.Name is "RUNSPEC" or "GRID" or "EDIT" or "PROPS" or "REGIONS").Select(b => b.Name).ToArray();
        if (!sections.Contains("RUNSPEC") || !sections.Contains("GRID") || !sections.Contains("PROPS") || sections.Distinct().Count() != sections.Length
            || !sections.SequenceEqual(new[] { "RUNSPEC", "GRID", "EDIT", "PROPS", "REGIONS" }.Where(sections.Contains)))
            throw new InvalidDataException("Static model section ordering is ambiguous.");
        var unit = blocks.Take(solution).Count(b => b.Name is "FIELD" or "METRIC");
        if (unit != 1 || blocks.Any(b => b.Name is "LAB" or "PVT-M") || blocks.Count(b => b.Name == "START") != 1)
            throw new InvalidDataException("Forecast requires explicit FIELD/METRIC and START.");
        var startTokens = Values(blocks.Single(b => b.Name == "START").Body);
        if (startTokens.Length is not (4 or 5) || startTokens[^1] != "/" || startTokens.Length == 5 && Unquote(startTokens[3]) != "00:00:00")
            throw new InvalidDataException("START must be a literal midnight date.");
        var startDate = ParseDate(startTokens.Take(3).ToArray());
        var canonical = string.Join("\n", blocks.Take(solution).Where(b => b.Name is not ("TITLE" or "FMTIN" or "UNIFIN" or "FMTOUT" or "UNIFOUT" or "NOECHO" or "ECHO"))
            .Select(b => b.Name + "\n" + string.Join("\n", Values(b.Body))));
        var hash = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(canonical)));
        if (!forecast)
        {
            // RPTSOL may list the output mnemonic RESTART on its own line before '/'.
            if (blocks.Any(b => b.Name == "RESTART" && !Values(b.Body).SequenceEqual(new[] { "/" })))
                throw new InvalidDataException("A history source cannot itself restart from an unbound state.");
            return new(hash, startDate, "", 0, []);
        }
        if (!blocks.Take(solution).Any(b => b.Name == "FMTIN") || !blocks.Take(solution).Any(b => b.Name == "UNIFIN"))
            throw new InvalidDataException("Forecast requires formatted unified restart input switches.");
        var summary = Array.FindIndex(blocks, b => b.Name == "SUMMARY");
        if (summary <= solution || summary >= schedule) throw new InvalidDataException("Forecast requires an explicit SUMMARY section.");
        var state = blocks.Skip(solution + 1).Take(summary - solution - 1).ToArray();
        if (state.Length != 1 || state[0].Name != "RESTART") throw new InvalidDataException("Forecast SOLUTION must contain only the inherited RESTART state.");
        var restart = Values(state[0].Body);
        if (restart.Length != 3 || restart[2] != "/" || !int.TryParse(restart[1], NumberStyles.None, CultureInfo.InvariantCulture, out var report) || report <= 0)
            throw new InvalidDataException("Forecast RESTART declaration is invalid.");
        var caseName = Unquote(restart[0]);
        if (!Regex.IsMatch(caseName, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}$", RegexOptions.CultureInvariant))
            throw new InvalidDataException("Forecast RESTART case must be an isolated package filename.");
        if (blocks.Skip(schedule).Any(b => b.Name is "TSTEP" or "TIME" or "ACTION" or "ACTIONX" or "UDQ" or "RESTART"))
            throw new InvalidDataException("Forecast requires literal DATES without dynamic timeline changes.");
        var dates = new List<string>();
        foreach (var dateBlock in blocks.Skip(schedule).Where(b => b.Name == "DATES"))
        {
            var values = Values(dateBlock.Body); var row = new List<string>(); var terminated = false;
            foreach (var token in values)
            {
                if (terminated) throw new InvalidDataException("Unexpected text after DATES terminator.");
                if (token != "/") { row.Add(token); continue; }
                if (row.Count == 0) { terminated = true; continue; }
                if (row.Count is not (3 or 4) || row.Count == 4 && Unquote(row[3]) != "00:00:00")
                    throw new InvalidDataException("Forecast dates must be literal midnight calendar records.");
                var formatted = ParseDate(row.Take(3).ToArray());
                if (dates.Count > 0 && string.CompareOrdinal(formatted, dates[^1]) <= 0) throw new InvalidDataException("Forecast dates must increase.");
                dates.Add(formatted); row.Clear();
            }
            if (!terminated || row.Count != 0) throw new InvalidDataException("Unterminated forecast dates.");
        }
        if (dates.Count is < 1 or > 8192) throw new InvalidDataException("Forecast requires bounded future dates.");
        return new(hash, startDate, caseName, report, dates);
    }

    public static EclipseRestartSourceCatalog Describe(EclipseDeckPackage history, EclipseRestartCatalog catalog)
    {
        var source = Read(history, false);
        if (source.StartDate != catalog.StartDate) throw new InvalidDataException("Restart time origin does not match the source START date.");
        var candidates = new List<EclipseForecastInput>();
        var resolver = new EclipseDeckPackageResolver();
        var files = Directory.EnumerateFiles(history.Root, "*", SearchOption.AllDirectories).Where(p => p.EndsWith(".DATA", StringComparison.OrdinalIgnoreCase)).Take(65).ToArray();
        if (files.Length > 64) throw new InvalidDataException("Too many candidate forecast inputs.");
        foreach (var file in files)
        {
            if (Path.GetFullPath(file).Equals(Path.GetFullPath(history.MainFile), StringComparison.OrdinalIgnoreCase)) continue;
            try
            {
                var candidate = Read(resolver.Resolve(file, history.Root), true);
                var report = catalog.Reports.SingleOrDefault(r => r.ReportNumber == candidate.RestartReport);
                if (candidate.PhysicsSha256 != source.PhysicsSha256 || candidate.RestartCase != catalog.CaseName || report is null
                    || candidate.FutureDates.Any(d => string.CompareOrdinal(d, report.Date) <= 0)) continue;
                var relative = Path.GetRelativePath(history.Root, file).Replace('\\', '/');
                candidates.Add(new(relative, Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file))), candidate.RestartReport, candidate.RestartCase, candidate.FutureDates));
            }
            catch (Exception e) when (e is InvalidDataException or EclipseDeckPackageException) { /* Incompatible inputs are never offered as verified forecasts. */ }
        }
        return new("eclipse-restart-source/1", catalog, source.PhysicsSha256, candidates.OrderBy(c => c.RelativePath, StringComparer.Ordinal).ToArray());
    }

    private static string Expand(string file, EclipseDeckPackage package, HashSet<string> active, ref long total)
    {
        if (!package.Files.Contains(file, StringComparer.OrdinalIgnoreCase) || !active.Add(file)) throw new InvalidDataException("Unverified INCLUDE graph.");
        var text = StripComments(EclipseDeckPackageResolver.ReadUtf8(file));
        total += Encoding.UTF8.GetByteCount(text); if (total > 64L * 1024 * 1024 || active.Count > 64) throw new InvalidDataException("Static expansion bound exceeded.");
        var result = new StringBuilder(); var previous = 0;
        foreach (Match m in Includes.Matches(text))
        {
            result.Append(text[previous..m.Index]);
            var name = m.Groups["one"].Success ? m.Groups["one"].Value : m.Groups["two"].Success ? m.Groups["two"].Value : m.Groups["bare"].Value;
            result.Append('\n').Append(Expand(EclipseDeckPackageResolver.ResolveInclude(file, name), package, active, ref total)).Append('\n');
            previous = m.Index + m.Length;
        }
        result.Append(text[previous..]); active.Remove(file);
        if (Heading.Matches(result.ToString()).Any(m => m.Groups[1].Value == "INCLUDE")) throw new InvalidDataException("Unexpanded INCLUDE declaration.");
        return result.ToString();
    }
    private static string[] Values(string body) => Tokens.Matches(body).Select(m => m.Value).ToArray();
    private static string ParseDate(string[] row)
    {
        if (!int.TryParse(row[0], NumberStyles.None, CultureInfo.InvariantCulture, out var day) || !int.TryParse(row[2], NumberStyles.None, CultureInfo.InvariantCulture, out var year))
            throw new InvalidDataException("Invalid calendar date.");
        var monthName = Unquote(row[1]); if (monthName == "JLY") monthName = "JUL";
        var month = Array.IndexOf(new[] { "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC" }, monthName) + 1;
        try { return new DateOnly(year, month, day).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture); }
        catch (ArgumentOutOfRangeException e) { throw new InvalidDataException("Invalid calendar date.", e); }
    }
    private static string Unquote(string value) => value.Length >= 2 && value[0] is '\'' or '"' && value[^1] == value[0] ? value[1..^1].TrimEnd() : value;
    private static string StripComments(string text)
    {
        var chars = text.ToCharArray(); var quote = '\0'; var comment = false;
        for (var i = 0; i < chars.Length; i++)
        {
            var c = chars[i];
            if (comment) { if (c is '\r' or '\n') comment = false; else chars[i] = ' '; continue; }
            if (quote != '\0') { if (c == quote) { if (i + 1 < chars.Length && chars[i + 1] == quote) i++; else quote = '\0'; } continue; }
            if (c is '\'' or '"') { quote = c; continue; }
            if (c == '-' && i + 1 < chars.Length && chars[i + 1] == '-') { chars[i++] = ' '; chars[i] = ' '; comment = true; }
        }
        if (quote != '\0') throw new InvalidDataException("Unterminated source string.");
        return new(chars);
    }
}
