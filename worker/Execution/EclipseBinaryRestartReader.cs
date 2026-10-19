using System.Buffers.Binary;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

// File Formats, binary keyword records and Restart Files: preserve complete raw report blocks.
public static class EclipseBinaryRestartReader
{
    private const int MaximumBytes = 128 * 1024 * 1024;
    private sealed record Section(string Keyword, string Type, int Count, byte[] Data, bool BigEndian);
    public static EclipseRestartCatalog Read(string path)
    {
        var info = new FileInfo(path);
        if (!info.Exists || info.Length is < 24 or > MaximumBytes || !Regex.IsMatch(info.Name, @"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.UNRST$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
            throw new InvalidDataException("Only bounded, named unified binary restart files are supported.");
        var bytes = File.ReadAllBytes(path);
        var big = BinaryPrimitives.ReadInt32BigEndian(bytes) == 16;
        if (!big && BinaryPrimitives.ReadInt32LittleEndian(bytes) != 16) throw new InvalidDataException("Invalid binary descriptor marker.");
        var groups = new List<(int Start, int End, List<Section> Sections)>();
        var sections = new List<Section>();
        var position = 0; var start = -1; var sectionCount = 0; long totalValues = 0;
        while (position < bytes.Length)
        {
            var offset = position;
            var descriptor = Record(bytes, ref position, big, 16);
            if (descriptor.Length != 16 || ++sectionCount > 131072) throw new InvalidDataException("Invalid restart descriptor or section bound.");
            var keyword = Text(descriptor.AsSpan(0, 8)).TrimEnd(' ');
            var type = Text(descriptor.AsSpan(12, 4));
            var count = Integer(descriptor.AsSpan(8, 4), big);
            var size = type switch { "INTE" or "REAL" or "LOGI" => 4, "DOUB" or "CHAR" => 8, "MESS" => 1,
                _ when type[0] == 'C' && type.Skip(1).All(char.IsAsciiDigit) && int.TryParse(type.AsSpan(1), out var length) && length > 0 => length,
                _ => throw new InvalidDataException("Unsupported restart array type.") };
            if (count < 0 || count > 4_000_000 || (totalValues += count) > 32_000_000 || type == "MESS" && count != 0
                || !Regex.IsMatch(keyword, "^[A-Z0-9_]{1,8}$", RegexOptions.CultureInvariant))
                throw new InvalidDataException("Invalid restart keyword/count/type.");
            var lengthBytes = checked((long)count * size);
            if (lengthBytes > MaximumBytes || lengthBytes > bytes.Length - position) throw new InvalidDataException("Restart array exceeds file bound.");
            var data = new byte[(int)lengthBytes]; var used = 0;
            while (used < data.Length)
            {
                var record = Record(bytes, ref position, big, data.Length - used);
                if (record.Length == 0 || record.Length % size != 0) throw new InvalidDataException("Misaligned binary restart array.");
                record.CopyTo(data, used); used += record.Length;
            }
            var section = new Section(keyword, type, count, data, big);
            ValidateValues(section);
            if (keyword == "SEQNUM")
            {
                if (start >= 0) groups.Add((start, offset, sections));
                start = offset; sections = [];
                if (groups.Count >= 8192) throw new InvalidDataException("Restart report count exceeds bound.");
            }
            if (start < 0 || keyword is "LGR" or "LGRHEADI" or "LGRHEADQ" or "LGRHEADD" or "LGRNAMES" or "ENDLGR" or "NMATRING")
                throw new InvalidDataException("Only standalone global-grid E100 reports are supported.");
            sections.Add(section);
        }
        if (start < 0) throw new InvalidDataException("No unified restart report.");
        groups.Add((start, bytes.Length, sections));
        var reports = new List<EclipseRestartReport>();
        DateOnly? origin = null;
        foreach (var group in groups)
        {
            var seq = Numbers(Required(group.Sections, "SEQNUM", "INTE"));
            var ih = Numbers(Required(group.Sections, "INTEHEAD", "INTE"));
            var dh = Numbers(Required(group.Sections, "DOUBHEAD", "DOUB"));
            if (seq.Length != 1 || ih.Length < 208 || dh.Length == 0 || ih[94] != 100 || ih[2] is not (1 or 2)
                || dh[0] < 0 || dh[0] > 3_000_000 || Math.Truncate(dh[0]) != dh[0] || ih[206] != 0 || ih[207] != 0)
                throw new InvalidDataException("Report requires E100 FIELD/METRIC integer-day midnight metadata.");
            var number = checked((int)seq[0]);
            var nx = checked((int)ih[8]); var ny = checked((int)ih[9]); var nz = checked((int)ih[10]);
            var active = checked((int)ih[11]); var phases = checked((int)ih[14]);
            if (number < 0 || nx <= 0 || ny <= 0 || nz <= 0 || (long)nx * ny * nz > 100_000_000
                || active <= 0 || active > (long)nx * ny * nz || phases is < 1 or > 7)
                throw new InvalidDataException("Invalid report identity/grid/phases.");
            DateOnly date;
            try { date = new(checked((int)ih[66]), checked((int)ih[65]), checked((int)ih[64])); }
            catch (ArgumentOutOfRangeException e) { throw new InvalidDataException("Invalid restart calendar date.", e); }
            DateOnly thisOrigin;
            try { thisOrigin = date.AddDays(-checked((int)dh[0])); }
            catch (ArgumentOutOfRangeException e) { throw new InvalidDataException("Restart time exceeds its calendar date.", e); }
            origin ??= thisOrigin;
            if (origin != thisOrigin || reports.Count > 0 && (number <= reports[^1].ReportNumber || dh[0] <= reports[^1].TimeDays))
                throw new InvalidDataException("Restart report order or simulation time origin is inconsistent.");
            Required(group.Sections, "LOGIHEAD", "LOGI");
            ValidateGlobalState(group.Sections, ih);
            if (Required(group.Sections, "STARTSOL", "MESS").Count != 0 || Required(group.Sections, "ENDSOL", "MESS").Count != 0
                || Required(group.Sections, "PRESSURE", "REAL").Count != active
                || (phases & 2) != 0 && Required(group.Sections, "SWAT", "REAL").Count != active
                || (phases & 4) != 0 && Required(group.Sections, "SGAS", "REAL").Count != active)
                throw new InvalidDataException("Restart report is missing complete cell solution arrays.");
            var solutionStart = group.Sections.FindIndex(s => s.Keyword == "STARTSOL");
            var solutionEnd = group.Sections.FindIndex(s => s.Keyword == "ENDSOL");
            foreach (var keyword in new[] { "PRESSURE", "SWAT", "SGAS" })
            {
                var solutionPosition = group.Sections.FindIndex(s => s.Keyword == keyword);
                if (solutionPosition >= 0 && (solutionPosition <= solutionStart || solutionPosition >= solutionEnd))
                    throw new InvalidDataException("Cell solution arrays must be inside the complete solution markers.");
            }
            var unit = ih[2] == 2 ? "FIELD" : "METRIC";
            if (reports.Count > 0 && (reports[0].Dimensions != new EclipseDimensions(nx, ny, nz) || reports[0].ActiveCells != active || reports[0].PhaseMask != phases || reports[0].UnitSystem != unit))
                throw new InvalidDataException("Grid, phases or units changed across report blocks.");
            reports.Add(new(number, dh[0], date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture), unit,
                new(nx, ny, nz), active, phases, 100, group.Start, group.End - group.Start,
                Convert.ToHexStringLower(SHA256.HashData(bytes.AsSpan(group.Start, group.End - group.Start)))));
        }
        return new("eclipse-restart-catalog/2", Path.GetFileNameWithoutExtension(path), origin!.Value.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
            "eclipse-output-" + info.Name, Convert.ToHexStringLower(SHA256.HashData(bytes)), bytes.Length, reports);
    }

    public static byte[] Select(string path, EclipseRestartCatalog expected, int reportNumber)
    {
        var actual = Read(path);
        if (actual.SchemaVersion != expected.SchemaVersion || actual.CaseName != expected.CaseName || actual.ArtifactName != expected.ArtifactName
            || actual.ArtifactSha256 != expected.ArtifactSha256 || actual.ArtifactSizeBytes != expected.ArtifactSizeBytes || actual.StartDate != expected.StartDate)
            throw new InvalidDataException("Restart source changed after catalog inspection.");
        var record = actual.Reports.SingleOrDefault(r => r.ReportNumber == reportNumber)
            ?? throw new InvalidDataException("The selected report does not exist in the restart file.");
        var expectedRecord = expected.Reports.SingleOrDefault(r => r.ReportNumber == reportNumber);
        if (record != expectedRecord || record.SizeBytes > 64L * 1024 * 1024)
            throw new InvalidDataException("Restart report binding or portable report bound is invalid.");
        using var file = File.OpenRead(path);
        file.Position = record.ByteOffset;
        var bytes = new byte[checked((int)record.SizeBytes)];
        file.ReadExactly(bytes);
        if (Convert.ToHexStringLower(SHA256.HashData(bytes)) != record.Sha256)
            throw new InvalidDataException("Restart report bytes changed.");
        return bytes;
    }

    private static Section Required(List<Section> sections, string keyword, string type)
    {
        var rows = sections.Where(s => s.Keyword == keyword).ToArray();
        return rows.Length == 1 && rows[0].Type == type ? rows[0] : throw new InvalidDataException("Missing or duplicate restart metadata/solution: " + keyword);
    }
    private static void ValidateGlobalState(List<Section> sections, double[] header)
    {
        var wells = checked((int)header[16]); var connections = checked((int)header[17]); var groups = checked((int)header[20]);
        if (wells < 0 || connections < 0 || groups < 0) throw new InvalidDataException("Invalid global well/group dimensions.");
        foreach (var (keyword, type, stride, entities) in new[] {
            ("IWEL", "INTE", 24, (long)wells), ("SWEL", "REAL", 25, (long)wells), ("XWEL", "DOUB", 26, (long)wells), ("ZWEL", "CHAR", 27, (long)wells),
            ("ICON", "INTE", 32, (long)wells * connections), ("SCON", "REAL", 33, (long)wells * connections), ("XCON", "DOUB", 34, (long)wells * connections),
            ("IGRP", "INTE", 36, (long)groups), ("SGRP", "REAL", 37, (long)groups), ("XGRP", "DOUB", 38, (long)groups), ("ZGRP", "CHAR", 39, (long)groups) })
        {
            if (entities == 0) continue;
            if (header[stride] <= 0 || header[stride] > 4_000_000 || entities > 4_000_000 || entities * header[stride] > 4_000_000
                || Required(sections, keyword, type).Count != entities * header[stride])
                throw new InvalidDataException("Incomplete global well/group/completion state: " + keyword);
        }
    }
    private static byte[] Record(byte[] bytes, ref int position, bool big, int maximum)
    {
        if (position > bytes.Length - 8) throw new InvalidDataException("Truncated binary record.");
        var length = Integer(bytes.AsSpan(position, 4), big);
        if (length < 0 || length > maximum || length > bytes.Length - position - 8) throw new InvalidDataException("Invalid binary record length.");
        if (Integer(bytes.AsSpan(position + 4 + length, 4), big) != length) throw new InvalidDataException("Binary record marker mismatch.");
        var result = bytes.AsSpan(position + 4, length).ToArray(); position += length + 8; return result;
    }
    private static string Text(ReadOnlySpan<byte> bytes)
    {
        foreach (var b in bytes) if (b is < 32 or > 126) throw new InvalidDataException("Invalid binary descriptor text.");
        return Encoding.ASCII.GetString(bytes);
    }
    private static int Integer(ReadOnlySpan<byte> bytes, bool big) => big ? BinaryPrimitives.ReadInt32BigEndian(bytes) : BinaryPrimitives.ReadInt32LittleEndian(bytes);
    private static double Number(Section section, int index)
    {
        var size = section.Type == "DOUB" ? 8 : 4; var span = section.Data.AsSpan(index * size, size);
        return section.Type switch {
            "INTE" or "LOGI" => Integer(span, section.BigEndian),
            "REAL" => BitConverter.Int32BitsToSingle(Integer(span, section.BigEndian)),
            "DOUB" => BitConverter.Int64BitsToDouble(section.BigEndian ? BinaryPrimitives.ReadInt64BigEndian(span) : BinaryPrimitives.ReadInt64LittleEndian(span)),
            _ => throw new InvalidDataException("Non-numeric restart array.") };
    }
    private static double[] Numbers(Section section) => Enumerable.Range(0, section.Count).Select(i => Number(section, i)).ToArray();
    private static void ValidateValues(Section section)
    {
        if (section.Type is not ("INTE" or "LOGI" or "REAL" or "DOUB")) return;
        for (var i = 0; i < section.Count; i++)
        {
            var value = Number(section, i);
            if (!double.IsFinite(value) || section.Type == "LOGI" && value is not (0 or 1 or -1))
                throw new InvalidDataException("Invalid binary numeric/logical state.");
        }
    }
}
