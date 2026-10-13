using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

// File Formats reference, PDF43–48: exact unified formatted report blocks, E100/midnight only.
public sealed record EclipseRestartReport(int ReportNumber, double TimeDays, string Date, string UnitSystem,
    EclipseDimensions Dimensions, int ActiveCells, int PhaseMask, int Solver, long ByteOffset, long SizeBytes, string Sha256);
public sealed record EclipseRestartCatalog(string SchemaVersion, string CaseName, string StartDate,
    string ArtifactName, string ArtifactSha256, long ArtifactSizeBytes, IReadOnlyList<EclipseRestartReport> Reports);

public static partial class EclipseFormattedRestartReader
{
    private const int MaximumBytes = 128 * 1024 * 1024;
    private static readonly Encoding Ascii = Encoding.GetEncoding("us-ascii", EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
    private sealed record Section(string Keyword, string Type, int Count, string Body);
    public static EclipseRestartCatalog Read(string path)
    {
        var info = new FileInfo(path);
        if (!info.Exists || info.Length is <= 0 or > MaximumBytes || !Regex.IsMatch(info.Name, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.FUNRST$", RegexOptions.IgnoreCase))
            throw new InvalidDataException("Only bounded, named unified formatted restart files are supported.");
        var bytes = File.ReadAllBytes(path);
        var text = Ascii.GetString(bytes);
        if (text.Split('\n').Any(line => line.TrimEnd('\r').Length > 80 || line.TrimEnd('\r').Any(c => c < 32 && c != '\t')))
            throw new InvalidDataException("Invalid formatted restart card.");
        var headings = Header().Matches(text).Cast<Match>().ToArray();
        if (headings.Length is < 4 or > 131072 || !string.IsNullOrWhiteSpace(text[..headings[0].Index]))
            throw new InvalidDataException("Invalid restart descriptors.");
        var groups = new List<(int Start, int End, List<Section> Sections)>();
        var sections = new List<Section>();
        var start = -1;
        long totalValues = 0;
        for (var index = 0; index < headings.Length; index++)
        {
            var h = headings[index];
            if (!int.TryParse(h.Groups["count"].Value, out var count) || count > 4_000_000 || (totalValues += count) > 32_000_000)
                throw new InvalidDataException("Restart value bound exceeded.");
            var keyword = h.Groups["keyword"].Value.TrimEnd();
            var type = h.Groups["type"].Value;
            var end = index + 1 < headings.Length ? headings[index + 1].Index : text.Length;
            var section = new Section(keyword, type, count, text[(h.Index + h.Length)..end]);
            ValidateValues(section);
            if (keyword == "SEQNUM")
            {
                if (start >= 0) groups.Add((start, h.Index, sections));
                start = h.Index; sections = [];
            }
            if (start < 0 || keyword == "LGR" || keyword == "LGRHEADI")
                throw new InvalidDataException("Only standalone global-grid unified reports are supported.");
            sections.Add(section);
        }
        groups.Add((start, text.Length, sections));
        if (groups.Count is < 1 or > 8192) throw new InvalidDataException("Invalid restart report count.");
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
                var position = group.Sections.FindIndex(s => s.Keyword == keyword);
                if (position >= 0 && (position <= solutionStart || position >= solutionEnd))
                    throw new InvalidDataException("Cell solution arrays must be inside the complete solution markers.");
            }
            var unit = ih[2] == 2 ? "FIELD" : "METRIC";
            if (reports.Count > 0 && (reports[0].Dimensions != new EclipseDimensions(nx, ny, nz) || reports[0].ActiveCells != active || reports[0].PhaseMask != phases || reports[0].UnitSystem != unit))
                throw new InvalidDataException("Grid, phases or units changed across report blocks.");
            reports.Add(new(number, dh[0], date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture), unit,
                new(nx, ny, nz), active, phases, 100, group.Start, group.End - group.Start,
                Convert.ToHexStringLower(SHA256.HashData(bytes.AsSpan(group.Start, group.End - group.Start)))));
        }
        return new("eclipse-restart-catalog/1", Path.GetFileNameWithoutExtension(path), origin!.Value.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
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
    private static void ValidateValues(Section section)
    {
        if (section.Type == "CHAR")
        {
            var matches = Character().Matches(section.Body);
            if (matches.Count != section.Count || !string.IsNullOrWhiteSpace(Character().Replace(section.Body, "")) || matches.Any(m => m.Groups[1].Value.Length > 8))
                throw new InvalidDataException("Invalid restart character array.");
            return;
        }
        var values = section.Body.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        if (values.Length != section.Count || section.Type == "MESS" && section.Count != 0)
            throw new InvalidDataException("Truncated restart array or invalid marker.");
        foreach (var value in values)
        {
            if (section.Type == "LOGI") { if (value is not ("T" or "F")) throw new InvalidDataException("Invalid restart logical."); }
            else if (section.Type == "INTE") { if (!int.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out _)) throw new InvalidDataException("Invalid restart integer."); }
            else if (section.Type != "MESS") _ = Number(value);
        }
    }
    private static double[] Numbers(Section section) => section.Body.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries).Select(Number).ToArray();
    private static double Number(string text)
    {
        text = text.Replace('D', 'E').Replace('d', 'E');
        if (!text.Contains('E') && !text.Contains('e') && ImplicitExponent().Match(text) is { Success: true } match)
            text = match.Groups[1].Value + "E" + match.Groups[2].Value;
        return double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) && double.IsFinite(value)
            ? value : throw new InvalidDataException("Invalid restart number.");
    }
    [GeneratedRegex("(?m)^[ \\t]*'(?<keyword>[A-Z0-9_ ]{8})'[ \\t]+(?<count>\\d+)[ \\t]+'(?<type>INTE|REAL|DOUB|LOGI|CHAR|MESS)'[ \\t]*\\r?$", RegexOptions.CultureInvariant)]
    private static partial Regex Header();
    [GeneratedRegex("'((?:[^']|'')*)'", RegexOptions.CultureInvariant)] private static partial Regex Character();
    [GeneratedRegex("^([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))([+-]\\d{2,4})$", RegexOptions.CultureInvariant)] private static partial Regex ImplicitExponent();
}
