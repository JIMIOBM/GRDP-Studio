using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

/// <summary>Reads initial targets in INCLUDE execution order while retaining physical source offsets.</summary>
internal static class EclipsePackageProductionTargetReader
{
    internal sealed record Target(string Well, string SourceFile, string SourceSha256, int LineNumber,
        double OriginalOilRate, int ValueStart, int ValueLength);
    internal sealed record Snapshot(IReadOnlyList<Target> Targets)
    {
        internal Grdp.SoftwareIntegration.Worker.Contracts.EclipsePackageProductionTargetContext Context => new(
            "eclipse-package-production-target-context/1", Targets.Select(t => new Grdp.SoftwareIntegration.Worker.Contracts.EclipsePackageProductionTarget(
                t.Well, t.SourceFile, t.SourceSha256, t.LineNumber, t.OriginalOilRate)).ToArray());
    }
    private sealed record Token(string Value, string Source, int Line, int Start, int Length, bool Heading);
    private sealed record Block(string Name, int Start, int End);
    private const int MaxBytes = 16 * 1024 * 1024;

    internal static Snapshot? Read(EclipseDeckPackage package)
    {
        try { return ReadCore(package); }
        catch (FormatException) { return null; }
        catch (DecoderFallbackException) { return null; }
    }

    private static Snapshot? ReadCore(EclipseDeckPackage package)
    {
        if (package.Files.Count is < 2 or > 4096) return null;
        var allowed = package.Files.Select(Path.GetFullPath).ToHashSet(StringComparer.OrdinalIgnoreCase);
        if (allowed.Count != package.Files.Count || !allowed.Contains(Path.GetFullPath(package.MainFile))) return null;
        var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var bytes = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
        var tokens = new List<Token>();
        var total = 0;
        void Expand(string path, int depth)
        {
            path = Path.GetFullPath(path);
            // Reusing a physical file could make one edit affect several execution positions.
            if (depth > 64 || !allowed.Contains(path) || !visited.Add(path)) throw new FormatException();
            var relative = Path.GetRelativePath(package.Root, path).Replace(Path.DirectorySeparatorChar, '/');
            if (!SafePath(relative)) throw new FormatException();
            if (new FileInfo(path).Length > MaxBytes - total) throw new FormatException();
            var data = File.ReadAllBytes(path);
            total += data.Length;
            if (total > MaxBytes) throw new FormatException();
            bytes.Add(relative, data);
            var local = Lex(new UTF8Encoding(false, true).GetString(data), relative);
            for (var i = 0; i < local.Count; i++)
            {
                var token = local[i];
                if (token.Value.Equals("INCLUDE", StringComparison.OrdinalIgnoreCase) && token.Heading)
                {
                    if (i + 2 >= local.Count || local[i + 2].Value != "/") throw new FormatException();
                    Expand(EclipseDeckPackageResolver.ResolveInclude(path, local[i + 1].Value, Path.GetDirectoryName(package.MainFile)!), depth + 1);
                    i += 2;
                }
                else tokens.Add(token);
                if (tokens.Count > 1_000_000) throw new FormatException();
            }
        }
        Expand(package.MainFile, 0);
        if (visited.Count != allowed.Count) return null;
        var headings = tokens.Select((token, index) => (token, index)).Where(x => x.token.Heading).ToArray();
        var blocks = headings.Select((h, i) => new Block(h.token.Value.ToUpperInvariant(), h.index + 1,
            i + 1 < headings.Length ? headings[i + 1].index : tokens.Count)).ToArray();
        if (blocks.Count(b => b.Name == "FIELD") != 1 || blocks.Count(b => b.Name == "SCHEDULE") != 1
            || blocks.Any(b => b.Name is "METRIC" or "LAB" or "PVT-M" or "COMPS" or "THERMAL"
                or "RESTART" or "PATHS" or "ENDINC" or "ACTION" or "ACTIONX" or "UDQ")) return null;
        var schedule = Array.FindIndex(blocks, b => b.Name == "SCHEDULE");
        var initial = blocks.Skip(schedule + 1).TakeWhile(b => b.Name is not ("DATES" or "TSTEP" or "TIME" or "END")).ToArray();
        if (initial.Any(b => b.Name is not ("WELSPECS" or "COMPDAT" or "WCONPROD" or "WCONINJE"
            or "RPTSCHED" or "RPTRST" or "DRSDT" or "TUNING"))) return null;
        var wells = initial.Where(b => b.Name == "WELSPECS").SelectMany(Rows).Select(r => r[0].Value).ToArray();
        if (wells.Length == 0 || wells.Distinct(StringComparer.Ordinal).Count() != wells.Length) return null;
        var controls = initial.Where(b => b.Name is "WCONPROD" or "WCONINJE")
            .SelectMany(b => Rows(b).Select(row => (b.Name, row))).ToArray();
        if (controls.Any(x => !Name(x.row[0].Value))) return null;
        var targets = new List<Target>();
        foreach (var (keyword, row) in controls)
        {
            if (keyword != "WCONPROD" || row.Count < 4 || row[1].Value != "OPEN" || row[2].Value != "ORAT"
                || !Rate(row[3].Value, out var original) || !wells.Contains(row[0].Value, StringComparer.Ordinal)
                || controls.Count(x => x.row[0].Value == row[0].Value) != 1) continue;
            if (row.Any(t => t.Source != row[0].Source)) continue;
            targets.Add(new(row[0].Value, row[0].Source, Convert.ToHexStringLower(SHA256.HashData(bytes[row[0].Source])),
                row[0].Line, original, row[3].Start, row[3].Length));
        }
        return targets.Count is > 0 and <= 1000 ? new(targets) : null;

        List<List<Token>> Rows(Block block)
        {
            var result = new List<List<Token>>();
            var row = new List<Token>();
            for (var i = block.Start; i < block.End; i++)
            {
                var token = tokens[i];
                if (token.Value != "/") { row.Add(token); if (row.Count > 128) throw new FormatException(); continue; }
                if (row.Count == 0)
                {
                    if (i != block.End - 1) throw new FormatException();
                    return result;
                }
                result.Add(row); row = [];
                if (result.Count > 4000) throw new FormatException();
            }
            throw new FormatException();
        }
    }

    internal static bool SafePath(string value) => value.Length is > 0 and <= 512 && !value.Contains('\\')
        && !value.Contains(':') && value.Split('/').All(p => p.Length > 0 && p is not ("." or "..")
            && Regex.IsMatch(p, "^[A-Za-z0-9][A-Za-z0-9._ -]*$", RegexOptions.CultureInvariant));
    private static bool Name(string value) => Regex.IsMatch(value, "^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$", RegexOptions.CultureInvariant);
    private static bool Rate(string value, out double rate) => double.TryParse(value, NumberStyles.Float,
        CultureInfo.InvariantCulture, out rate) && double.IsFinite(rate) && rate > 0 && rate <= 100_000_000;

    private static List<Token> Lex(string text, string source)
    {
        if (text.Contains('\0')) throw new FormatException();
        var result = new List<Token>();
        var line = 1; var position = 0; var onlyWhitespace = true;
        while (position < text.Length)
        {
            var c = text[position];
            if (c == '\0') throw new FormatException();
            if (c is '\r' or '\n')
            {
                position++; if (c == '\r' && position < text.Length && text[position] == '\n') position++;
                line++; onlyWhitespace = true; continue;
            }
            if (char.IsWhiteSpace(c) || c == '\ufeff') { position++; continue; }
            if (c == '-' && position + 1 < text.Length && text[position + 1] == '-')
            { while (position < text.Length && text[position] is not ('\r' or '\n')) position++; continue; }
            var start = position; var quoted = c is '\'' or '"'; var value = new StringBuilder();
            if (quoted)
            {
                position++; var closed = false;
                while (position < text.Length)
                {
                    c = text[position++]; if (c is '\r' or '\n' or '\0') throw new FormatException();
                    if (c == text[start])
                    {
                        if (position < text.Length && text[position] == c) { value.Append(c); position++; }
                        else { closed = true; break; }
                    }
                    else value.Append(c);
                }
                if (!closed) throw new FormatException();
            }
            else if (c == '/') { value.Append(c); position++; }
            else while (position < text.Length && !char.IsWhiteSpace(text[position]) && text[position] != '/')
            {
                if (text[position] == '-' && position + 1 < text.Length && text[position + 1] == '-') break;
                value.Append(text[position++]);
            }
            if (value.Length == 0 || value.Length > 4096) throw new FormatException();
            var word = value.ToString();
            var end = position; while (end < text.Length && text[end] is not ('\r' or '\n')) end++;
            var heading = !quoted && onlyWhitespace && Regex.IsMatch(word, "^[A-Za-z][A-Za-z0-9-]{0,7}$");
            result.Add(new(word, source, line, start, position - start, heading));
            // ECLIPSE keyword lines may contain documentary text after the keyword.
            // INCLUDE keeps its following path and slash for recursive expansion.
            if (heading && !word.Equals("INCLUDE", StringComparison.OrdinalIgnoreCase)) position = end;
            onlyWhitespace = false;
            if (result.Count > 1_000_000) throw new FormatException();
        }
        return result;
    }
}
