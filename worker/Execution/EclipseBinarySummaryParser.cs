using System.Buffers.Binary;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

/// <summary>
/// Reads the ECLIPSE SMSPEC/UNSMRY Summary pair without exposing raw binary data.
/// Each official block has a keyword/count/type descriptor and separate data records.
/// The bounded field index validates those records. RSM remains the text fallback.
/// </summary>
public static class EclipseBinarySummaryParser
{
    private sealed record Section(string Keyword, string DataType, int Count, byte[] Data, bool BigEndian);

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
        if (wells.Count != keywords.Count || units.Count != keywords.Count)
            throw new InvalidDataException("Summary identity and unit columns must match KEYWORDS.");
        var timeIndices = Enumerable.Range(0, keywords.Count).Where(index => keywords[index] is "TIME" or "DAYS").ToArray();
        if (timeIndices.Length != 1 || units[timeIndices[0]] != "DAYS")
            throw new InvalidDataException("A unique real TIME/DAYS axis in DAYS is required; years and record indices are not simulation days.");
        var timeIndex = timeIndices[0];
        var series = new Dictionary<string, MutableSeries>(StringComparer.Ordinal);
        for (var index = 0; index < keywords.Count; index++)
        {
            if (index == timeIndex) continue;
            var objectName = ObjectName(wells.ElementAtOrDefault(index));
            var key = SeriesKey(keywords[index], objectName);
            series.TryAdd(key, new MutableSeries(keywords[index], objectName, units.ElementAtOrDefault(index)));
        }

        foreach (var path in paths)
        {
            foreach (var section in ReadSections(path))
            {
                if (!section.Keyword.Equals("PARAMS", StringComparison.OrdinalIgnoreCase)) continue;
                var values = Numbers(section, keywords.Count);
                var time = values[timeIndex];
                if (!double.IsFinite(time) || time < 0) continue;
                for (var index = 0; index < keywords.Count && index < values.Count; index++)
                {
                    if (index == timeIndex || !double.IsFinite(values[index])) continue;
                    var objectName = ObjectName(wells.ElementAtOrDefault(index));
                    var key = SeriesKey(keywords[index], objectName);
                    if (!series.TryGetValue(key, out var item)) continue;
                    if (item.Points.Count == 0 || time >= item.Points[^1].TimeDays)
                        item.Points.Add(new EclipseSummaryPoint(time, values[index]));
                }
            }
        }

        return series.Values.Select(item => new EclipseSummarySeries(item.Keyword, item.ObjectName, item.Unit, item.Points)).ToArray();
    }

    private static IReadOnlyList<Section> ReadSections(string path)
    {
        var index = EclipseBinaryFieldIndexParser.TryParse(path)
            ?? throw new InvalidDataException("Invalid ECLIPSE descriptor/data record structure.");
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (stream.Length != index.FileSize) throw new InvalidDataException("The Summary file changed during indexing.");
        var sections = new List<Section>();
        long collectedBytes = 0;
        foreach (var field in index.Sections)
        {
            if (field.Keyword is not ("KEYWORDS" or "WGNAMES" or "UNITS" or "PARAMS")) continue;
            collectedBytes += field.DataBytes;
            if (field.Count > 65536 || field.DataBytes > 512 * 1024 || collectedBytes > 64 * 1024 * 1024)
                throw new InvalidDataException("The Summary exceeds bounded value/byte limits.");
            var data = new byte[checked((int)field.DataBytes)];
            var offset = 0;
            foreach (var segment in field.Segments)
            {
                stream.Position = segment.Offset;
                stream.ReadExactly(data.AsSpan(offset, segment.Length));
                offset += segment.Length;
            }
            sections.Add(new Section(field.Keyword, field.DataType, field.Count, data, index.ByteOrder == "BIG"));
        }
        return sections;
    }

    private static List<string> Strings(IEnumerable<Section> sections, string name) =>
        sections.Where(section => section.Keyword.Equals(name, StringComparison.OrdinalIgnoreCase) && section.DataType == "CHAR")
            .SelectMany(section => Enumerable.Range(0, section.Data.Length / 8)
                .Select(index => Encoding.ASCII.GetString(section.Data, index * 8, 8).Trim('\0', ' ')))
            .ToList();

    private static List<double> Numbers(Section section, int expectedCount)
    {
        if (expectedCount <= 0 || section.Count != expectedCount || section.DataType is not ("REAL" or "DOUB"))
            throw new InvalidDataException("PARAMS count/type must match the Summary specification.");
        var size = section.DataType == "DOUB" ? 8 : 4;
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
        public string? Unit { get; } = string.IsNullOrWhiteSpace(unit) ? null : unit;
        public List<EclipseSummaryPoint> Points { get; } = [];
    }
}
