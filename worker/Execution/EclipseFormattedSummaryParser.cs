using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

/// <summary>Reads official 80-column formatted descriptor/data blocks, not the Lotus RSM display.</summary>
public static partial class EclipseFormattedSummaryParser
{
    private const long MaximumFileBytes = 64 * 1024 * 1024;
    private const int MaximumBlockValues = 65536;
    private const int MaximumTotalValues = 4_000_000;
    private sealed record Block(string Keyword, string Type, string[] Text, double[] Numbers);

    public static string ReadStartDate(string specificationPath)
    {
        var arrays = ReadBlocks(specificationPath).Where(b => b.Keyword == "STARTDAT").ToArray();
        if (arrays.Length != 1 || arrays[0].Type != "INTE" || arrays[0].Numbers.Length != 6 || arrays[0].Numbers.Skip(3).Any(n => n != 0))
            throw new InvalidDataException("A unique midnight Summary STARTDAT is required.");
        var n = arrays[0].Numbers;
        try { return new DateOnly(checked((int)n[2]), checked((int)n[1]), checked((int)n[0])).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture); }
        catch (ArgumentOutOfRangeException e) { throw new InvalidDataException("Invalid Summary start date.", e); }
    }

    public static IReadOnlyList<EclipseSummarySeries> Parse(string specificationPath, string summaryPath)
    {
        var specification = ReadBlocks(specificationPath).ToArray();
        var keywords = RequiredText(specification, "KEYWORDS");
        var names = RequiredText(specification, "WGNAMES");
        var units = RequiredText(specification, "UNITS");
        if (keywords.Length == 0 || names.Length != keywords.Length || units.Length != keywords.Length)
            throw new InvalidDataException("Formatted Summary identity and unit columns must match KEYWORDS.");
        var axes = Enumerable.Range(0, keywords.Length).Where(index => keywords[index] is "TIME" or "DAYS").ToArray();
        if (axes.Length != 1 || units[axes[0]] != "DAYS")
            throw new InvalidDataException("A unique real TIME/DAYS axis in DAYS is required.");
        var numbers = specification.Where(block => block.Keyword == "NUMS").ToArray();
        if (numbers.Length > 1 || numbers.Length == 1 && (numbers[0].Type != "INTE" ||
            numbers[0].Numbers.Length != keywords.Length || numbers[0].Numbers.Any(value => value > 0)))
            throw new InvalidDataException("Spatial/region Summary identities need a separate NUMS-aware contract.");

        var identities = new HashSet<string>(StringComparer.Ordinal);
        var series = new List<EclipseSummarySeries>();
        var columns = new Dictionary<int, List<EclipseSummaryPoint>>();
        for (var column = 0; column < keywords.Length; column++)
        {
            if (column == axes[0]) continue;
            var name = names[column] is "" or "FIELD" or ":+:+:+:+" ? null : names[column];
            if (!identities.Add(keywords[column] + "\u0001" + name))
                throw new InvalidDataException("Duplicate formatted Summary identities must not be merged.");
            var points = new List<EclipseSummaryPoint>();
            columns.Add(column, points);
            series.Add(new EclipseSummarySeries(keywords[column], name, units[column].Length == 0 ? null : units[column], points));
        }

        double previousTime = -1;
        foreach (var block in ReadBlocks(summaryPath))
        {
            ValidateSummaryBlock(block);
            if (block.Keyword != "PARAMS") continue;
            if (block.Numbers.Length != keywords.Length)
                throw new InvalidDataException("PARAMS must match the full specification column count.");
            var time = block.Numbers[axes[0]];
            if (time < 0 || time <= previousTime)
                throw new InvalidDataException("Formatted Summary times must be nonnegative and strictly increasing.");
            previousTime = time;
            foreach (var column in columns) column.Value.Add(new EclipseSummaryPoint(time, block.Numbers[column.Key]));
        }
        if (previousTime < 0 || series.Count == 0) throw new InvalidDataException("No usable formatted Summary records.");
        return series;
    }

    // Raw specifications can contain RESTART paths. Only numeric Summary blocks may be downloaded.
    public static void ValidateDataFile(string path)
    {
        var hasValues = false;
        foreach (var block in ReadBlocks(path))
        {
            ValidateSummaryBlock(block);
            hasValues |= block.Keyword == "PARAMS" && block.Numbers.Length > 0;
        }
        if (!hasValues) throw new InvalidDataException("No numeric Summary records to publish.");
    }

    private static void ValidateSummaryBlock(Block block)
    {
        if (block.Keyword == "PARAMS" && block.Type is "REAL" or "DOUB" && block.Numbers.Length > 0) return;
        if (block.Keyword is "SEQHDR" or "MINISTEP" && block.Type == "INTE" && block.Numbers.Length == 1) return;
        throw new InvalidDataException("Only SEQHDR, MINISTEP and numeric PARAMS blocks are allowed in formatted Summary data.");
    }

    private static string[] RequiredText(Block[] blocks, string keyword)
    {
        var matches = blocks.Where(block => block.Keyword == keyword).ToArray();
        if (matches.Length != 1 || matches[0].Type != "CHAR")
            throw new InvalidDataException("Missing, duplicate or wrongly typed Summary metadata: " + keyword);
        return matches[0].Text;
    }

    private static IEnumerable<Block> ReadBlocks(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (stream.Length == 0 || stream.Length > MaximumFileBytes)
            throw new InvalidDataException("Formatted Summary file size is outside the allowed bound.");
        using var reader = new StreamReader(stream, Encoding.GetEncoding("us-ascii", EncoderFallback.ExceptionFallback,
            DecoderFallback.ExceptionFallback), detectEncodingFromByteOrderMarks: false);
        var total = 0;
        while (ReadCard(reader) is { } card)
        {
            if (string.IsNullOrWhiteSpace(card)) continue;
            var header = Header().Match(card);
            if (!header.Success || !int.TryParse(header.Groups["count"].Value, out var count) || count > MaximumBlockValues)
                throw new InvalidDataException("Invalid formatted Summary descriptor.");
            total = checked(total + count);
            if (total > MaximumTotalValues) throw new InvalidDataException("Formatted Summary value limit exceeded.");
            var type = header.Groups["type"].Value;
            var text = type == "CHAR" ? new string[count] : [];
            var values = type == "CHAR" ? [] : new double[count];
            var read = 0;
            while (read < count)
            {
                var line = ReadCard(reader) ?? throw new InvalidDataException("Truncated formatted Summary block.");
                if (string.IsNullOrWhiteSpace(line)) continue;
                var tokens = type == "CHAR" ? CharacterValues(line) : line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
                if (tokens.Length == 0 || tokens.Length > count - read)
                    throw new InvalidDataException("Formatted Summary element count mismatch.");
                foreach (var token in tokens)
                {
                    if (type == "CHAR") text[read++] = token.Trim(' ');
                    else values[read++] = Number(token, type);
                }
            }
            yield return new Block(header.Groups["keyword"].Value.TrimEnd(), type, text, values);
        }
    }

    private static string? ReadCard(StreamReader reader)
    {
        var card = reader.ReadLine();
        if (card is not null && (card.Length > 80 || card.Any(character => character > 126 || character < 32 && character != '\t')))
            throw new InvalidDataException("Invalid formatted Summary card width/characters.");
        return card;
    }

    private static string[] CharacterValues(string line)
    {
        var values = new List<string>();
        var position = 0;
        while (position < line.Length)
        {
            if (char.IsWhiteSpace(line[position])) { position++; continue; }
            if (line.Length - position < 10 || line[position] != '\'' || line[position + 9] != '\'' ||
                line.AsSpan(position + 1, 8).Contains('\''))
                throw new InvalidDataException("CHAR items must contain exactly eight quoted characters.");
            values.Add(line.Substring(position + 1, 8));
            position += 10;
            if (position < line.Length && !char.IsWhiteSpace(line[position]))
                throw new InvalidDataException("CHAR items require whitespace separation.");
        }
        return values.ToArray();
    }

    private static double Number(string token, string type)
    {
        if (type == "INTE")
        {
            if (int.TryParse(token, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var integer)) return integer;
            throw new InvalidDataException("Invalid formatted integer.");
        }
        if (type == "LOGI") return token switch { "T" or ".TRUE." => 1, "F" or ".FALSE." => 0,
            _ => throw new InvalidDataException("Invalid formatted logical.") };
        var normalized = token.Replace('D', 'E').Replace('d', 'E');
        if (!normalized.Contains('E') && !normalized.Contains('e'))
        {
            var exponent = ImplicitExponent().Match(normalized);
            if (exponent.Success) normalized = exponent.Groups["number"].Value + "E" + exponent.Groups["exponent"].Value;
        }
        if (!double.TryParse(normalized, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) || !double.IsFinite(value))
            throw new InvalidDataException("Invalid or nonfinite formatted number.");
        var result = type == "REAL" ? (double)(float)value : value;
        return double.IsFinite(result) ? result : throw new InvalidDataException("Formatted REAL overflow.");
    }

    [GeneratedRegex("^\\s*'(?<keyword>[A-Z0-9_ ]{8})'\\s+(?<count>\\d+)\\s+'(?<type>INTE|REAL|DOUB|LOGI|CHAR)'\\s*$", RegexOptions.CultureInvariant)]
    private static partial Regex Header();
    [GeneratedRegex("^(?<number>[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))(?<exponent>[+-]\\d{2,4})$", RegexOptions.CultureInvariant)]
    private static partial Regex ImplicitExponent();
}
