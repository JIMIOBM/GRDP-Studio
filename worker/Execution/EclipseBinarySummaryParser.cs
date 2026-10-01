using System.Buffers.Binary;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

/// <summary>
/// Reads the ECLIPSE SMSPEC/UNSMRY Summary pair without exposing raw binary data.
/// ECLIPSE writes Fortran records whose payload starts with an eight-byte keyword;
/// the byte order is detected from the first record. RSM remains the text fallback.
/// </summary>
public static class EclipseBinarySummaryParser
{
    private sealed record Section(string Keyword, byte[] Data, bool BigEndian);

    public static IReadOnlyList<EclipseSummarySeries> Parse(string smspecPath, string unsmryPath)
        => Parse(smspecPath, [unsmryPath]);

    public static IReadOnlyList<EclipseSummarySeries> Parse(string smspecPath, IEnumerable<string> summaryPaths)
    {
        var paths = summaryPaths.Where(path => !string.IsNullOrWhiteSpace(path)).ToArray();
        if (paths.Length == 0) throw new InvalidDataException("No ECLIPSE binary Summary file was supplied.");
        var specification = ReadSections(smspecPath);
        var keywords = Strings(specification, "KEYWORDS");
        if (keywords.Count == 0) return [];
        var wells = Strings(specification, "WGNAMES");
        var units = Strings(specification, "UNITS");
        var timeIndex = keywords.FindIndex(keyword => keyword is "TIME" or "DAYS" or "YEARS");
        var series = new Dictionary<string, MutableSeries>(StringComparer.Ordinal);
        for (var index = 0; index < keywords.Count; index++)
        {
            if (index == timeIndex) continue;
            var objectName = ObjectName(wells.ElementAtOrDefault(index));
            var key = SeriesKey(keywords[index], objectName);
            series.TryAdd(key, new MutableSeries(keywords[index], objectName, units.ElementAtOrDefault(index)));
        }

        var step = 0d;
        foreach (var path in paths)
        {
            foreach (var section in ReadSections(path))
            {
                if (!section.Keyword.Equals("PARAMS", StringComparison.OrdinalIgnoreCase)) continue;
                var values = Numbers(section, keywords.Count);
                if (values.Count < keywords.Count) continue;
                var time = timeIndex >= 0 && timeIndex < values.Count ? values[timeIndex] : step;
                if (!double.IsFinite(time) || time < 0) { step++; continue; }
                for (var index = 0; index < keywords.Count && index < values.Count; index++)
                {
                    if (index == timeIndex || !double.IsFinite(values[index])) continue;
                    var objectName = ObjectName(wells.ElementAtOrDefault(index));
                    var key = SeriesKey(keywords[index], objectName);
                    if (!series.TryGetValue(key, out var item)) continue;
                    if (item.Points.Count == 0 || time >= item.Points[^1].TimeDays)
                        item.Points.Add(new EclipseSummaryPoint(time, values[index]));
                }
                step++;
            }
        }

        return series.Values.Select(item => new EclipseSummarySeries(item.Keyword, item.ObjectName, item.Unit, item.Points)).ToArray();
    }

    private static IReadOnlyList<Section> ReadSections(string path)
    {
        var bytes = File.ReadAllBytes(path);
        if (bytes.Length < 8) throw new InvalidDataException("Truncated ECLIPSE binary file.");
        var bigEndian = IsValidRecordLength(ReadInt32(bytes, 0, true), bytes.Length)
                        && !IsValidRecordLength(ReadInt32(bytes, 0, false), bytes.Length);
        var littleEndian = IsValidRecordLength(ReadInt32(bytes, 0, false), bytes.Length);
        if (!bigEndian && !littleEndian) throw new InvalidDataException("Invalid ECLIPSE binary record byte order.");

        var sections = new List<Section>();
        var offset = 0;
        while (offset < bytes.Length)
        {
            var length = ReadRecordLength(bytes, ref offset, bigEndian);
            var payload = bytes[offset..(offset + length)];
            offset += length;
            var footer = ReadInt32(bytes, offset, bigEndian);
            offset += 4;
            if (footer != length) throw new InvalidDataException("Invalid ECLIPSE binary record footer.");
            var keyword = Encoding.ASCII.GetString(payload, 0, 8).TrimEnd('\0', ' ');
            sections.Add(new Section(keyword, payload[8..], bigEndian));
        }
        return sections;
    }

    private static int ReadRecordLength(byte[] bytes, ref int offset, bool bigEndian)
    {
        if (offset + 4 > bytes.Length) throw new InvalidDataException("Truncated ECLIPSE binary record.");
        var length = ReadInt32(bytes, offset, bigEndian);
        offset += 4;
        if (length < 8 || length > 512 * 1024 * 1024 || offset + length + 4 > bytes.Length)
            throw new InvalidDataException("Invalid ECLIPSE binary record length.");
        return length;
    }

    private static bool IsValidRecordLength(int length, int totalLength) =>
        length >= 8 && length <= 512 * 1024 * 1024 && length + 8 <= totalLength;

    private static List<string> Strings(IEnumerable<Section> sections, string name) =>
        sections.Where(section => section.Keyword.Equals(name, StringComparison.OrdinalIgnoreCase))
            .SelectMany(section => Enumerable.Range(0, section.Data.Length / 8)
                .Select(index => Encoding.ASCII.GetString(section.Data, index * 8, 8).TrimEnd('\0', ' ')))
            .ToList();

    private static List<double> Numbers(Section section, int expectedCount)
    {
        if (expectedCount <= 0 || section.Data.Length % expectedCount != 0) return [];
        var size = section.Data.Length / expectedCount;
        if (size is not (4 or 8)) return [];
        var values = new List<double>(expectedCount);
        for (var index = 0; index < expectedCount; index++)
        {
            var offset = index * size;
            values.Add(size == 8
                ? BitConverter.Int64BitsToDouble(ReadInt64(section.Data, offset, section.BigEndian))
                : BitConverter.Int32BitsToSingle(ReadInt32(section.Data, offset, section.BigEndian)));
        }
        return values;
    }

    private static string? ObjectName(string? value) => string.IsNullOrWhiteSpace(value) || value is "FIELD" or ":+:+:+:+" ? null : value;
    private static string SeriesKey(string keyword, string? objectName) => keyword + "\u0001" + objectName;
    private static int ReadInt32(byte[] bytes, int offset, bool bigEndian)
    {
        var value = BitConverter.ToInt32(bytes, offset);
        return BitConverter.IsLittleEndian == bigEndian ? BinaryPrimitives.ReverseEndianness(value) : value;
    }
    private static long ReadInt64(byte[] bytes, int offset, bool bigEndian)
    {
        var value = BitConverter.ToInt64(bytes, offset);
        return BitConverter.IsLittleEndian == bigEndian ? BinaryPrimitives.ReverseEndianness(value) : value;
    }

    private sealed class MutableSeries(string keyword, string? objectName, string? unit)
    {
        public string Keyword { get; } = keyword;
        public string? ObjectName { get; } = objectName;
        public string? Unit { get; } = unit;
        public List<EclipseSummaryPoint> Points { get; } = [];
    }
}
