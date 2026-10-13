using System.Buffers.Binary;
using System.Globalization;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record EclipseCompositionalSeries(int VectorIndex, string Keyword, string? ObjectName, int Number,
    string? Unit, IReadOnlyList<EclipseSummaryPoint> Points);
public sealed record EclipseCompositionalSummary(string StartDate, int TimeVectorIndex, IReadOnlyList<EclipseCompositionalSeries> Series);

// E300 keeps every original column, including duplicates and undefined slots.
// NUMS is raw native identity, never guessed to be a component number.
public static class EclipseCompositionalSummaryParser
{
    private sealed record Block(string Keyword, string Type, int Count, byte[] Bytes, bool BigEndian);
    public static EclipseCompositionalSummary Parse(string specificationPath, IEnumerable<string> summaryPaths)
        => ParseBound(specificationPath, summaryPaths, 300, null, null);

    public static EclipseCompositionalSummary ParseForModel(string specificationPath, IEnumerable<string> summaryPaths,
        int programIdentifier, string unitSystem, int nx, int ny, int nz)
    {
        if (programIdentifier is not (300 or 500) || unitSystem is not ("FIELD" or "METRIC") ||
            new[] { nx, ny, nz }.Any(n => n is < 1 or > 1_000_000))
            throw new InvalidDataException("A verified E300 model, unit system and grid are required.");
        return ParseBound(specificationPath, summaryPaths, programIdentifier, unitSystem == "FIELD" ? 2 : 1, [nx, ny, nz]);
    }

    private static EclipseCompositionalSummary ParseBound(string specificationPath, IEnumerable<string> summaryPaths,
        int programIdentifier, int? unitCode, int[]? expectedDimensions)
    {
        var specification = Read(specificationPath);
        var header = Numbers(One(specification, "INTEHEAD", "INTE"));
        if (header.Length != 2 || header[1] != programIdentifier || unitCode is not null && header[0] != unitCode)
            throw new InvalidDataException("The Summary program identifier and unit system must match the verified E300 model.");
        var dimensions = Numbers(One(specification, "DIMENS", "INTE"));
        var keywords = Strings(One(specification, "KEYWORDS", "CHAR"));
        var objects = Strings(One(specification, "WGNAMES", "CHAR"));
        var units = Strings(One(specification, "UNITS", "CHAR"));
        var numbers = Numbers(One(specification, "NUMS", "INTE"));
        if (dimensions.Length != 6 || dimensions[0] != keywords.Length || keywords.Length is < 2 or > 65536 ||
            objects.Length != keywords.Length || units.Length != keywords.Length || numbers.Length != keywords.Length ||
            dimensions.Skip(1).Take(3).Any(n => n <= 0 || n > 1_000_000))
            throw new InvalidDataException("Complete Summary dimensions and identity arrays are required.");
        if (expectedDimensions is not null && !dimensions.Skip(1).Take(3).SequenceEqual(expectedDimensions.Select(n => (double)n)))
            throw new InvalidDataException("The native Summary grid must match the verified model dimensions.");
        var localNames = specification.Where(b => b.Keyword == "LGRS").ToArray();
        if (localNames.Length > 1 || localNames.Length == 1 && (localNames[0].Count != keywords.Length || localNames[0].Type != "CHAR" || Strings(localNames[0]).Any(s => s is not ("" or ":+:+:+:+"))))
            throw new InvalidDataException("Named local-grid Summary identities require a separate contract.");
        // E300 also writes global block coordinates in NUMLX/Y/Z when LGRS is
        // a placeholder. Validate these against DIMENS and the raw cell NUMS.
        var coordinateNames = new[] { "NUMLX", "NUMLY", "NUMLZ" };
        if (specification.Any(b => coordinateNames.Contains(b.Keyword)))
        {
            var coordinates = coordinateNames.Select(name => Numbers(One(specification, name, "INTE"))).ToArray();
            if (coordinates.Any(a => a.Length != keywords.Length)) throw new InvalidDataException("Incomplete global Summary coordinate arrays.");
            for (var i = 0; i < keywords.Length; i++)
            {
                var xyz = coordinates.Select(a => a[i]).ToArray();
                if (xyz.All(n => n == 0)) continue;
                if (xyz.Where((n, axis) => n < 1 || n > dimensions[axis + 1]).Any() ||
                    keywords[i].StartsWith('B') && (xyz[2] - 1) * dimensions[1] * dimensions[2] + (xyz[1] - 1) * dimensions[1] + xyz[0] != numbers[i])
                    throw new InvalidDataException("Global Summary coordinates do not match the original cell identity.");
            }
        }
        var start = Numbers(One(specification, "STARTDAT", "INTE"));
        if (start.Length != 6 || start.Skip(3).Any(n => n != 0)) throw new InvalidDataException("A complete midnight STARTDAT is required.");
        string date;
        try { date = new DateOnly((int)start[2], (int)start[1], (int)start[0]).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture); }
        catch (ArgumentOutOfRangeException e) { throw new InvalidDataException("Invalid compositional start date.", e); }
        var axes = Enumerable.Range(0, keywords.Length).Where(i => keywords[i] is "TIME" or "DAYS").ToArray();
        if (axes.Length != 1 || units[axes[0]] != "DAYS") throw new InvalidDataException("One actual TIME axis in DAYS is required.");
        var paths = summaryPaths.ToArray();
        if (paths.Length is < 1 or > 8192) throw new InvalidDataException("A bounded complete Summary sequence is required.");
        var points = Enumerable.Range(0, keywords.Length).Select(_ => new List<EclipseSummaryPoint>()).ToArray();
        double previous = -1;
        long totalValues = 0;
        foreach (var path in paths)
        {
            var file = Read(path, dataFile: true);
            foreach (var block in file)
            {
                if (block.Keyword is "SEQHDR" or "MINISTEP")
                { if (block.Type != "INTE" || block.Count != 1) throw new InvalidDataException("Invalid Summary report marker."); continue; }
                if (block.Keyword != "PARAMS" || block.Type is not ("REAL" or "DOUB") || block.Count != keywords.Length)
                    throw new InvalidDataException("Every numeric record must match the full specification.");
                totalValues += block.Count;
                if (totalValues > 4_000_000) throw new InvalidDataException("The Summary exceeds the complete-value bound.");
                var values = Numbers(block);
                var time = values[axes[0]];
                if (values.Any(n => !double.IsFinite(n)) || time < 0 || time <= previous)
                    throw new InvalidDataException("All values must be finite and actual times strictly increasing.");
                previous = time;
                for (var i = 0; i < values.Length; i++) if (i != axes[0]) points[i].Add(new(time, values[i]));
            }
        }
        if (previous < 0) throw new InvalidDataException("No complete compositional output records.");
        var series = Enumerable.Range(0, keywords.Length).Where(i => i != axes[0]).Select(i => new EclipseCompositionalSeries(i, keywords[i],
            objects[i].Length == 0 ? null : objects[i], checked((int)numbers[i]), units[i].Length == 0 ? null : units[i], points[i])).ToArray();
        return new(date, axes[0], series);
    }

    private static Block One(IReadOnlyList<Block> blocks, string keyword, string type)
    {
        var matches = blocks.Where(b => b.Keyword == keyword).ToArray();
        if (matches.Length != 1 || matches[0].Type != type) throw new InvalidDataException("Missing, duplicate or wrongly typed specification: " + keyword);
        return matches[0];
    }
    private static IReadOnlyList<Block> Read(string path, bool dataFile = false)
    {
        var index = EclipseBinaryFieldIndexParser.TryParse(path) ?? throw new InvalidDataException("Invalid native binary Summary records.");
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (stream.Length != index.FileSize || stream.Length > 64L * 1024 * 1024) throw new InvalidDataException("Summary source size changed or exceeds its bound.");
        var result = new List<Block>();
        long bytes = 0;
        foreach (var field in index.Sections)
        {
            if (dataFile && field.Keyword is not ("SEQHDR" or "MINISTEP" or "PARAMS")) throw new InvalidDataException("Unexpected data record in a compositional Summary file.");
            if (field.Keyword is not ("INTEHEAD" or "DIMENS" or "KEYWORDS" or "WGNAMES" or "UNITS" or "NUMS" or "STARTDAT" or "LGRS" or "NUMLX" or "NUMLY" or "NUMLZ" or "SEQHDR" or "MINISTEP" or "PARAMS")) continue;
            bytes += field.DataBytes;
            if (field.Count > 65536 || field.DataBytes > 512 * 1024 || bytes > 64 * 1024 * 1024) throw new InvalidDataException("Summary collection exceeds bounded columns or bytes.");
            var data = new byte[checked((int)field.DataBytes)];
            var position = 0;
            foreach (var segment in field.Segments) { stream.Position = segment.Offset; stream.ReadExactly(data.AsSpan(position, segment.Length)); position += segment.Length; }
            result.Add(new(field.Keyword, field.DataType, field.Count, data, index.ByteOrder == "BIG"));
        }
        return result;
    }
    private static string[] Strings(Block block)
    {
        if (block.Type != "CHAR" || block.Bytes.Length != block.Count * 8) throw new InvalidDataException("Invalid character Summary array.");
        var result = new string[block.Count];
        for (var i = 0; i < result.Length; i++)
        {
            var value = block.Bytes.AsSpan(i * 8, 8);
            foreach (var c in value) if (c < 32 || c > 126) throw new InvalidDataException("Unsafe Summary identity.");
            result[i] = Encoding.ASCII.GetString(value).Trim(' ');
        }
        return result;
    }
    private static double[] Numbers(Block block)
    {
        var size = block.Type == "DOUB" ? 8 : 4;
        if (block.Type is not ("INTE" or "REAL" or "DOUB") || block.Bytes.Length != block.Count * size) throw new InvalidDataException("Invalid numerical Summary array.");
        var result = new double[block.Count];
        for (var i = 0; i < result.Length; i++)
        {
            var span = block.Bytes.AsSpan(i * size, size);
            if (size == 8) result[i] = BitConverter.Int64BitsToDouble(block.BigEndian ? BinaryPrimitives.ReadInt64BigEndian(span) : BinaryPrimitives.ReadInt64LittleEndian(span));
            else
            {
                var raw = block.BigEndian ? BinaryPrimitives.ReadInt32BigEndian(span) : BinaryPrimitives.ReadInt32LittleEndian(span);
                result[i] = block.Type == "INTE" ? raw : BitConverter.Int32BitsToSingle(raw);
            }
        }
        return result;
    }
}
