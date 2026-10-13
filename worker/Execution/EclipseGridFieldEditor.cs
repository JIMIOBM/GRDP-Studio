using System.Buffers.Binary;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static partial class EclipseGridFieldEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseGridFieldParameters parameters,
        string baselineFieldPath, CancellationToken cancellationToken)
    {
        ValidateBaselineField(baselineFieldPath, parameters);
        if (package.Files.Count != 1 || !string.Equals(package.Files[0], package.MainFile, StringComparison.OrdinalIgnoreCase))
            throw new EclipseDeckPackageException("ECLIPSE_GRID_FIELD_UNSUPPORTED_PACKAGE", "Single-cell PERMX editing currently requires a single-file DATA deck without INCLUDE dependencies.");

        var text = EclipseDeckPackageResolver.ReadUtf8(package.MainFile);
        var lines = text.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n');
        var gridLines = FindStandaloneKeywords(lines, "GRID");
        var propsLines = FindStandaloneKeywords(lines, "PROPS");
        if (gridLines.Count != 1 || propsLines.Count != 1 || propsLines[0] <= gridLines[0])
            throw new EclipseDeckPackageException("ECLIPSE_GRID_FIELD_SECTION_UNSUPPORTED", "The DATA deck must contain exactly one GRID section followed by PROPS.");

        var gridStart = gridLines[0] + 1;
        var gridEnd = propsLines[0];
        if (FindStandaloneKeywords(lines, "PERMX", gridStart, gridEnd).Count != 1)
            throw new EclipseDeckPackageException("ECLIPSE_GRID_FIELD_PERMX_MISSING", "The supported GRID section must define PERMX exactly once before PROPS.");
        if (new[] { "BOX", "ENDBOX", "LGR", "ENDLGR", "MULTIPLY", "INCLUDE" }
            .Any(keyword => FindStandaloneKeywords(lines, keyword, gridStart, gridEnd).Count != 0))
            throw new EclipseDeckPackageException("ECLIPSE_GRID_FIELD_COMPLEX_GRID", "GRID contains BOX/LGR/MULTIPLY/INCLUDE constructs outside the supported single-cell editing contract.");

        var newline = text.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : "\n";
        var block = string.Join(newline,
            "-- GRDP controlled single-cell PERMX multiplier; generated in the isolated run copy.",
            "MULTIPLY",
            string.Create(CultureInfo.InvariantCulture, $" PERMX {parameters.Multiplier:G17} {parameters.I} {parameters.I} {parameters.J} {parameters.J} {parameters.K} {parameters.K} /"),
            "/",
            string.Empty);
        lines[propsLines[0]] = block + lines[propsLines[0]];
        await File.WriteAllTextAsync(package.MainFile, string.Join(newline, lines), new UTF8Encoding(false), cancellationToken);
        return string.Create(CultureInfo.InvariantCulture,
            $"Applied PERMX multiplier {parameters.Multiplier:G6} at I={parameters.I}, J={parameters.J}, K={parameters.K} to isolated DATA copy.");
    }

    internal static void ValidateBaselineField(string path, EclipseGridFieldParameters parameters)
    {
        var index = EclipseBinaryFieldIndexParser.TryParse(path)
            ?? throw new EclipseDeckPackageException("ECLIPSE_GRID_FIELD_ARTIFACT_INVALID", "The selected baseline INIT Artifact is not a readable ECLIPSE binary field file.");
        if (!string.Equals(index.FileName, parameters.FieldFile, StringComparison.OrdinalIgnoreCase))
            throw new EclipseDeckPackageException("ECLIPSE_GRID_FIELD_ARTIFACT_MISMATCH", "The selected baseline INIT filename does not match the field request.");
        var expectedCount = checked((long)parameters.Nx * parameters.Ny * parameters.Nz);
        var candidates = index.Sections.Where(section => section.Keyword == "PERMX" && section.Count == expectedCount
            && section.DataType is "REAL" or "DOUB").ToArray();
        if (candidates.Length != 1)
            throw new EclipseDeckPackageException("ECLIPSE_GRID_FIELD_NOT_UNIQUE", "The baseline INIT must contain exactly one full-grid REAL/DOUB PERMX field.");

        var flatIndex = checked(((long)parameters.K - 1) * parameters.Ny * parameters.Nx
            + ((long)parameters.J - 1) * parameters.Nx + parameters.I - 1);
        var actual = ReadValue(path, index.ByteOrder, candidates[0], flatIndex);
        var tolerance = Math.Max(1e-6, Math.Abs(parameters.OriginalValue) * 1e-6);
        if (!double.IsFinite(actual) || actual <= 0 || Math.Abs(actual - parameters.OriginalValue) > tolerance)
            throw new EclipseDeckPackageException("ECLIPSE_GRID_FIELD_STALE_VALUE", "The selected PERMX cell value no longer matches the bound baseline INIT Artifact.");
    }

    private static double ReadValue(string path, string byteOrder, EclipseBinaryFieldSection field, long index)
    {
        var remaining = index;
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 4096, FileOptions.RandomAccess);
        Span<byte> data = stackalloc byte[8];
        foreach (var segment in field.Segments)
        {
            var values = segment.Length / field.ElementSize;
            if (remaining >= values) { remaining -= values; continue; }
            stream.Position = checked(segment.Offset + remaining * field.ElementSize);
            data.Clear();
            stream.ReadExactly(data[..field.ElementSize]);
            var bigEndian = byteOrder == "BIG";
            if (field.DataType == "REAL")
            {
                var bits = bigEndian ? BinaryPrimitives.ReadInt32BigEndian(data[..4]) : BinaryPrimitives.ReadInt32LittleEndian(data[..4]);
                return BitConverter.Int32BitsToSingle(bits);
            }
            var doubleBits = bigEndian ? BinaryPrimitives.ReadInt64BigEndian(data) : BinaryPrimitives.ReadInt64LittleEndian(data);
            return BitConverter.Int64BitsToDouble(doubleBits);
        }
        throw new EclipseDeckPackageException("ECLIPSE_GRID_FIELD_INDEX_INVALID", "The selected PERMX value is outside the baseline INIT field data.");
    }

    private static List<int> FindStandaloneKeywords(string[] lines, string keyword, int start = 0, int? end = null)
    {
        var result = new List<int>();
        var limit = end ?? lines.Length;
        for (var index = start; index < limit; index++)
        {
            var line = lines[index].Split("--", 2, StringSplitOptions.None)[0].Trim();
            if (Regex.IsMatch(line, "^" + Regex.Escape(keyword) + @"(?:\s+=+)?$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)) result.Add(index);
        }
        return result;
    }
}
