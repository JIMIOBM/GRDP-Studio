using System.Buffers.Binary;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record EclipseCompositionalSaveFile(string Name, string ArtifactName, long SizeBytes, string Sha256);
public sealed record EclipseCompositionalSaveCatalog(string SchemaVersion, string SourceFile, string SourceSha256, long SourceSizeBytes,
    int Solver, string SolverVersion, string CaseName, string StartDate, int ReportNumber, double TimeDays, string Date,
    string UnitSystem, EclipseDimensions Dimensions, int ActiveCells, int ComponentCount, IReadOnlyList<string> ComponentNames,
    string EquationOfState, double EndTimeDays, IReadOnlyList<EclipseCompositionalSaveFile> Files);

internal static class EclipseCompositionalSaveCatalogReader
{
    // Call only with the fresh outputs of a successful, zero-parameter, serial E300 run.
    // X supplies the clock and grid identity; the two opaque V files supply native state.
    internal static EclipseCompositionalSaveCatalog Read(EclipseDeckPackage package, IReadOnlyList<string> freshOutputs, string solverVersion, bool publishedArtifacts = false,
        EclipseCompositionalSaveSourceReader.Snapshot? verifiedSource = null)
    {
        var source = verifiedSource ?? EclipseCompositionalSaveSourceReader.Read(package)
            ?? throw new InvalidDataException("History source is outside the verified serial SAVE subset.");
        if (!File.ReadAllBytes(package.MainFile).SequenceEqual(source.OriginalBytes))
            throw new InvalidDataException("Historical input changed after source qualification.");
        if (solverVersion != "2024.1") throw new InvalidDataException("SAVE requires the identical verified simulator release.");
        var caseName = Path.GetFileNameWithoutExtension(package.MainFile);
        string Name(string path)
        {
            var name = Path.GetFileName(path);
            if (!publishedArtifacts) return name;
            if (!name.StartsWith("eclipse-output-", StringComparison.Ordinal)) throw new InvalidDataException("Published history file is not a controlled output artifact.");
            return name["eclipse-output-".Length..];
        }
        var report = source.ReportNumber.ToString("D4", CultureInfo.InvariantCulture);
        var names = new[] { caseName + ".V0000", caseName + ".V" + report, caseName + ".X" + report };
        var selected = new List<string>();
        foreach (var name in names)
        {
            var candidates = freshOutputs.Where(p => Name(p).Equals(name, StringComparison.OrdinalIgnoreCase)).ToArray();
            if (candidates.Length != 1 || new FileInfo(candidates[0]).Length is < 24 or > 128 * 1024 * 1024)
                throw new InvalidDataException("History requires the fresh nonrecurrent SAVE, selected SAVE and matching X report.");
            selected.Add(candidates[0]);
        }
        if (selected.Select(p => Path.GetFullPath(Path.GetDirectoryName(p)!)).Distinct(StringComparer.OrdinalIgnoreCase).Count() != 1
            || selected.Any(p => (File.GetAttributes(p) & FileAttributes.ReparsePoint) != 0))
            throw new InvalidDataException("SAVE outputs must share one owned regular-file directory.");
        var vNames = freshOutputs.Select(Name).Where(n => Regex.IsMatch(n,
            "^" + Regex.Escape(caseName) + @"\.V[0-9]{4}$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)).ToArray();
        if (vNames.Length != 2) throw new InvalidDataException("Only one explicitly saved checkpoint is supported.");
        var statePath = selected[2];
        var index = EclipseBinaryFieldIndexParser.TryParse(statePath) ?? throw new InvalidDataException("Invalid history report metadata.");
        var allowed = new HashSet<string> { "INTEHEAD", "LOGIHEAD", "DOUBHEAD", "IWEL", "ZWEL", "ICON", "ZCOMPS", "ZPHASE", "ZFLUID",
            "PRESSURE", "SOIL", "SGAS", "SSOLID" };
        if (index.Sections.Any(s => !allowed.Contains(s.Keyword)) || index.Sections.Select(s => s.Keyword).Distinct().Count() != index.Sections.Count)
            throw new InvalidDataException("Unverified restart grid, report or component structure.");
        var integers = ReadIntegers(statePath, index, "INTEHEAD");
        var doubles = ReadDoubles(statePath, index, "DOUBHEAD");
        if (integers.Length < 208 || doubles.Length == 0 || integers[94] != 300 || integers[2] != 2
            || integers[67] != source.ReportNumber || doubles[0] != source.TimeDays)
            throw new InvalidDataException("History report solver, units, report number or elapsed time differs from source.");
        var dimensions = new EclipseDimensions(integers[8], integers[9], integers[10]);
        var sourceText = EclipseSimulationReader.StripComments(Encoding.UTF8.GetString(source.OriginalBytes));
        var dimensionRecords = Regex.Matches(sourceText, @"(?im)^DIMENS[ \t]*\r?\n\s*([0-9]+)\s+([0-9]+)\s+([0-9]+)\s*/");
        if (dimensionRecords.Count != 1 || dimensions.Nx <= 0 || dimensions.Ny <= 0 || dimensions.Nz <= 0
            || (long)dimensions.Nx * dimensions.Ny * dimensions.Nz > 1_000_000 || integers[11] <= 0
            || integers[11] > (long)dimensions.Nx * dimensions.Ny * dimensions.Nz
            || dimensions != new EclipseDimensions(int.Parse(dimensionRecords[0].Groups[1].Value, CultureInfo.InvariantCulture),
                int.Parse(dimensionRecords[0].Groups[2].Value, CultureInfo.InvariantCulture), int.Parse(dimensionRecords[0].Groups[3].Value, CultureInfo.InvariantCulture)))
            throw new InvalidDataException("Saved report grid does not match the historical source.");
        DateOnly date;
        try { date = new(integers[66], integers[65], integers[64]); }
        catch (ArgumentOutOfRangeException e) { throw new InvalidDataException("Invalid saved calendar date.", e); }
        if (date != DateOnly.ParseExact(source.StartDate, "yyyy-MM-dd", CultureInfo.InvariantCulture).AddDays(checked((int)source.TimeDays)))
            throw new InvalidDataException("Saved calendar date does not preserve the original simulation origin.");
        foreach (var keyword in new[] { "ZCOMPS", "ZFLUID" })
        {
            var bytes = ReadArray(statePath, index, keyword, "CHAR", 10);
            var labels = Enumerable.Range(0, 10).Select(j => Encoding.ASCII.GetString(bytes, j * 8, 8).Trim()).ToArray();
            if (!labels.Take(9).SequenceEqual(source.Production.ComponentNames) || labels[9] != "Water")
                throw new InvalidDataException("Saved compositional identity differs from the source component order.");
        }
        if (integers[16] != 2)
            throw new InvalidDataException("Historical source or well count changed.");
        var wellBytes = ReadArray(statePath, index, "ZWEL", "CHAR", 2);
        var wells = Enumerable.Range(0, 2).Select(j => Encoding.ASCII.GetString(wellBytes, j * 8, 8).Trim()).ToHashSet(StringComparer.Ordinal);
        if (!wells.SetEquals([source.InjectionWell, source.Production.Records.Single().Well]))
            throw new InvalidDataException("Saved wells differ from historical producer and injector identities.");
        foreach (var keyword in new[] { "PRESSURE", "SOIL", "SGAS", "SSOLID" })
            if (index.Sections.SingleOrDefault(s => s.Keyword == keyword) is not { DataType: "REAL" } section || section.Count != integers[11])
                throw new InvalidDataException("Incomplete matching grid state metadata.");
        var files = selected.Select(p => {
            using var stream = File.OpenRead(p);
            return new EclipseCompositionalSaveFile(Name(p), "eclipse-output-" + Name(p), stream.Length,
                Convert.ToHexStringLower(SHA256.HashData(stream)));
        }).ToArray();
        if (files.Sum(f => f.SizeBytes) + 2L * source.OriginalBytes.Length + 64 * 1024 > 64L * 1024 * 1024)
            throw new InvalidDataException("Historical state cannot fit the complete verified portable forecast package.");
        var c = source.Production;
        return new("eclipse-compositional-save-catalog/1", c.SourceFile, c.SourceSha256, c.SourceSizeBytes, 300, solverVersion, caseName,
            source.StartDate, source.ReportNumber, source.TimeDays, date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
            "FIELD", dimensions, integers[11], c.ComponentCount, c.ComponentNames, c.EquationOfState, source.EndTimeDays, files);
    }

    private static byte[] ReadArray(string path, EclipseBinaryFieldIndex index, string keyword, string type, int? exactCount = null)
    {
        var section = index.Sections.SingleOrDefault(s => s.Keyword == keyword);
        if (section is null || section.DataType != type || section.Count is < 1 or > 4096 || exactCount is not null && section.Count != exactCount
            || section.DataBytes > 64 * 1024) throw new InvalidDataException("Missing or invalid bounded history header.");
        var bytes = new byte[checked((int)section.DataBytes)]; var offset = 0;
        using var stream = File.OpenRead(path);
        foreach (var segment in section.Segments) { stream.Position = segment.Offset; stream.ReadExactly(bytes.AsSpan(offset, segment.Length)); offset += segment.Length; }
        if (offset != bytes.Length) throw new InvalidDataException("Incomplete history header.");
        return bytes;
    }
    private static int[] ReadIntegers(string path, EclipseBinaryFieldIndex index, string keyword)
    {
        var bytes = ReadArray(path, index, keyword, "INTE");
        return Enumerable.Range(0, bytes.Length / 4).Select(j => index.ByteOrder == "BIG"
            ? BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(j * 4, 4)) : BinaryPrimitives.ReadInt32LittleEndian(bytes.AsSpan(j * 4, 4))).ToArray();
    }
    private static double[] ReadDoubles(string path, EclipseBinaryFieldIndex index, string keyword)
    {
        var bytes = ReadArray(path, index, keyword, "DOUB");
        return Enumerable.Range(0, bytes.Length / 8).Select(j => BitConverter.Int64BitsToDouble(index.ByteOrder == "BIG"
            ? BinaryPrimitives.ReadInt64BigEndian(bytes.AsSpan(j * 8, 8)) : BinaryPrimitives.ReadInt64LittleEndian(bytes.AsSpan(j * 8, 8)))).ToArray();
    }
}
