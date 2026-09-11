using System.Globalization;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record EclipseEndCounts(int Comments, int Warnings, int Problems, int Errors, int Bugs);
public sealed record EclipseSummaryPoint(double TimeDays, double Value);
public sealed record EclipseSummarySeries(string Keyword, string? ObjectName, string? Unit, IReadOnlyList<EclipseSummaryPoint> Points);

public static partial class EclipseParsers
{
    public static bool TryParseEclEnd(string text, out EclipseEndCounts? counts)
    {
        counts = null;
        var values = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        foreach (Match match in EclEndCount().Matches(text)) values[match.Groups[1].Value] = int.Parse(match.Groups[2].Value, CultureInfo.InvariantCulture);
        if (!new[] { "Comments", "Warnings", "Problems", "Errors", "Bugs" }.All(values.ContainsKey)) return false;
        counts = new(values["Comments"], values["Warnings"], values["Problems"], values["Errors"], values["Bugs"]);
        return true;
    }

    public static IReadOnlyList<EclipseSummarySeries> ParseRsm(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        var series = new Dictionary<string, MutableSeries>(StringComparer.Ordinal);
        IReadOnlyList<Column> columns = [];
        var headerLines = new List<string>();
        var state = RsmState.BeforeBlock;

        foreach (var rawLine in text.Split('\n'))
        {
            var line = rawLine.TrimEnd('\r');
            var trimmed = line.Trim();
            if (trimmed.Contains("SUMMARY OF RUN", StringComparison.Ordinal))
            {
                state = RsmState.CollectingHeader;
                continue;
            }

            if (state == RsmState.CollectingHeader)
            {
                if (IsSeparator(trimmed))
                {
                    if (headerLines.Count == 0) continue;
                    columns = BuildColumns(headerLines);
                    headerLines = [];
                    state = RsmState.Data;
                }
                else headerLines.Add(line);
            }
            else if (state == RsmState.Data)
            {
                ParseDataRow(line, columns, series);
            }
        }

        AddEmptySeries(columns, series);
        return series.Values.Select(value => new EclipseSummarySeries(value.Keyword, value.ObjectName, value.Unit, value.Points)).ToArray();
    }

    internal static bool IsPublishableSummary(IReadOnlyList<EclipseSummarySeries>? series)
    {
        if (series is null || !series.Any(item => item.Points.Count > 0)) return false;
        foreach (var item in series)
        {
            if (!IsSafeKeyword(item.Keyword) || !IsSafeObjectName(item.ObjectName) || !IsSafeUnit(item.Unit)) return false;
            var previousTime = -1d;
            foreach (var point in item.Points)
            {
                if (!double.IsFinite(point.TimeDays) || !double.IsFinite(point.Value) || point.TimeDays < 0 || point.TimeDays < previousTime) return false;
                previousTime = point.TimeDays;
            }
        }
        return true;
    }

    private static void AddEmptySeries(IReadOnlyList<Column> columns, Dictionary<string, MutableSeries> series)
    {
        foreach (var column in columns.Where(column => !IsSummaryAxis(column.Keyword)))
        {
            var key = SeriesKey(column);
            if (!series.ContainsKey(key)) series.Add(key, new MutableSeries(column.Keyword, column.ObjectName, column.Unit));
        }
    }

    private static IReadOnlyList<Column> BuildColumns(List<string> headerLines)
    {
        var keywordRow = headerLines.Find(line => line.Contains("TIME", StringComparison.Ordinal));
        if (keywordRow is null) return [];
        var boundaries = Token().Matches(keywordRow);
        if (boundaries.Count == 0) return [];
        var keywordIndex = headerLines.IndexOf(keywordRow);
        var unitsRow = keywordIndex + 1 < headerLines.Count ? headerLines[keywordIndex + 1] : null;
        var qualifiers = keywordIndex + 2 <= headerLines.Count ? headerLines.GetRange(keywordIndex + 2, headerLines.Count - keywordIndex - 2) : [];
        var columns = new List<Column>(boundaries.Count);
        for (var index = 0; index < boundaries.Count; index++)
        {
            var start = boundaries[index].Index;
            var end = index + 1 < boundaries.Count ? boundaries[index + 1].Index : keywordRow.Length;
            var keyword = Slice(keywordRow, start, end);
            var unit = Slice(unitsRow, start, end);
            var objectName = ExtractObjectName(qualifiers, start, end);
            if (keyword is null || !IsSafeKeyword(keyword) || !IsSafeUnit(unit) || !IsSafeObjectName(objectName)) return [];
            columns.Add(new(keyword, unit, objectName));
        }
        return columns;
    }

    private static void ParseDataRow(string line, IReadOnlyList<Column> columns, Dictionary<string, MutableSeries> series)
    {
        var tokens = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        if (columns.Count == 0 || tokens.Length < columns.Count || !TryParseDouble(tokens[0], out var time) || !double.IsFinite(time) || time < 0) return;
        for (var index = 1; index < columns.Count; index++)
        {
            var column = columns[index];
            if (IsSummaryAxis(column.Keyword) || !TryParseDouble(tokens[index], out var value) || !double.IsFinite(value)) continue;
            var key = SeriesKey(column);
            if (!series.TryGetValue(key, out var item))
            {
                item = new MutableSeries(column.Keyword, column.ObjectName, column.Unit);
                series.Add(key, item);
            }
            if (item.Points.Count > 0 && time < item.Points[^1].TimeDays) continue;
            item.Points.Add(new(time, value));
        }
    }

    private static string? ExtractObjectName(List<string> qualifierLines, int start, int end)
    {
        foreach (var line in qualifierLines)
        {
            var value = Slice(line, start, end);
            if (value is not null && !NumericOnly().IsMatch(value)) return value;
        }
        return null;
    }

    private static string? Slice(string? line, int start, int end)
    {
        if (line is null || start >= line.Length) return null;
        var value = line.Substring(start, Math.Min(end, line.Length) - start).Trim();
        return value.Length == 0 ? null : value;
    }

    private static bool TryParseDouble(string value, out double parsed) =>
        double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out parsed);
    // TIME supplies the x-axis. YEARS remains an excluded RSM summary axis, never a converted time value.
    private static bool IsSummaryAxis(string keyword) => keyword.Equals("TIME", StringComparison.Ordinal) || keyword.Equals("YEARS", StringComparison.Ordinal);
    private static bool IsSafeKeyword(string value) => SafeKeyword().IsMatch(value) && !ContainsSensitiveText(value);
    private static bool IsSafeObjectName(string? value) => value is null || (SafeObjectName().IsMatch(value) && !ContainsSensitiveText(value));
    private static bool IsSafeUnit(string? value) => value is null || (SafeUnit().IsMatch(value) && !ContainsSensitiveText(value));
    private static bool ContainsSensitiveText(string value) => SensitiveText().IsMatch(value);
    private static string SeriesKey(Column column) => column.Keyword + "\u0001" + column.ObjectName;
    private static bool IsSeparator(string value) => value.StartsWith("---", StringComparison.Ordinal) && value.Replace("-", string.Empty).Trim().Length == 0;
    private sealed record Column(string Keyword, string? Unit, string? ObjectName);
    private sealed class MutableSeries(string keyword, string? objectName, string? unit)
    {
        public string Keyword { get; } = keyword;
        public string? ObjectName { get; } = objectName;
        public string? Unit { get; } = unit;
        public List<EclipseSummaryPoint> Points { get; } = [];
    }
    private enum RsmState { BeforeBlock, CollectingHeader, Data }

    [GeneratedRegex("\\b(Comments|Warnings|Problems|Errors|Bugs)\\s*[:=]?\\s*(\\d+)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex EclEndCount();
    [GeneratedRegex(@"\S+")]
    private static partial Regex Token();
    [GeneratedRegex(@"^[\d\s.*]+$")]
    private static partial Regex NumericOnly();
    [GeneratedRegex(@"^[A-Za-z][A-Za-z0-9_]{0,63}$", RegexOptions.CultureInvariant)]
    private static partial Regex SafeKeyword();
    [GeneratedRegex(@"^[A-Za-z0-9][A-Za-z0-9 _.\-]{0,127}$", RegexOptions.CultureInvariant)]
    private static partial Regex SafeObjectName();
    [GeneratedRegex(@"^[A-Za-z0-9][A-Za-z0-9 _./%^()+\-]{0,127}$", RegexOptions.CultureInvariant)]
    private static partial Regex SafeUnit();
    [GeneratedRegex(@"(?i)(?:[a-z]:[\\/]|[\\/]{2}|net\.pipe://|\b(?:license|server|password|token|secret|environment)\b)", RegexOptions.CultureInvariant)]
    private static partial Regex SensitiveText();
}
