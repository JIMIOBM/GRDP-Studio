using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

// Expand only for analysis. Editing always uses the original physical file and token span.
internal static class EclipsePackageControlPlanReader
{
    internal sealed record Target(EclipseProductionPlanRecord Record, string SourceSha256, int Start, int Length, int ExecutionOrder);
    internal sealed record Snapshot(string StartDate, int EndTimeDays, IReadOnlyList<Target> Targets,
        IReadOnlyDictionary<string, byte[]> Sources)
    {
        internal EclipsePackageProductionControlPlanContext Context => new("eclipse-package-production-control-plan-context/1", StartDate, EndTimeDays,
            Targets.Select(t => new EclipsePackageProductionPlanRecord(t.Record.Well, t.Record.SourceFile, t.SourceSha256, t.Record.LineNumber,
                t.Record.Date, t.Record.TimeDays, t.Record.Unit, t.Record.OriginalOilRate, t.Record.BhpLimit)).ToArray());
    }
    private sealed record Span(int Start, int Length, string Source, int PhysicalStart);
    private static readonly Regex Includes = new("(?im)^[ \\t]*INCLUDE[ \\t\\r\\n]+(?:'(?<single>[^'\\r\\n]+)'|\"(?<double>[^\"\\r\\n]+)\"|(?<bare>[^\\s/]+))[ \\t\\r\\n]*/", RegexOptions.CultureInvariant);
    private const int MaximumBytes = 16 * 1024 * 1024;

    internal static Snapshot? Read(EclipseDeckPackage package)
    {
        try { return ReadCore(package); }
        catch (Exception exception) when (exception is FormatException or DecoderFallbackException) { return null; }
    }
    private static Snapshot? ReadCore(EclipseDeckPackage package)
    {
        if (package.Files.Count is < 2 or > 4096) return null;
        var allowed = package.Files.Select(Path.GetFullPath).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (allowed.Count != package.Files.Count) return null;
        var sources = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
        var texts = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var spans = new List<Span>(); var flat = new StringBuilder(); var total = 0;
        void Append(string text, int start, int length, string source)
        {
            if (length == 0) return;
            spans.Add(new(flat.Length, length, source, start)); flat.Append(text, start, length);
            if (flat.Length > MaximumBytes) throw new FormatException();
        }
        void Expand(string file, int depth)
        {
            file = Path.GetFullPath(file);
            if (depth > 64 || !allowed.Contains(file) || !visited.Add(file) || new FileInfo(file).Length > MaximumBytes - total) throw new FormatException();
            var relative = Path.GetRelativePath(package.Root, file).Replace('\\', '/');
            if (!EclipsePackageProductionTargetReader.SafePath(relative)) throw new FormatException();
            var bytes = File.ReadAllBytes(file); total += bytes.Length;
            if (total > MaximumBytes) throw new FormatException();
            var text = new UTF8Encoding(false, true).GetString(bytes);
            if (text.Contains('\0')) throw new FormatException();
            sources.Add(relative, bytes); texts.Add(relative, text);
            var matches = Includes.Matches(text).ToArray();
            string Name(Match m) => m.Groups["single"].Success ? m.Groups["single"].Value : m.Groups["double"].Success ? m.Groups["double"].Value : m.Groups["bare"].Value;
            if (!EclipseIncludeParser.Parse(text).SequenceEqual(matches.Select(Name))) throw new FormatException();
            var previous = text.StartsWith('\ufeff') ? 1 : 0;
            foreach (var match in matches)
            {
                if (match.Index < previous) throw new FormatException();
                Append(text, previous, match.Index - previous, relative); flat.Append('\n');
                Expand(EclipseDeckPackageResolver.ResolveInclude(file, Name(match), Path.GetDirectoryName(package.MainFile)!), depth + 1);
                flat.Append('\n'); previous = match.Index + match.Length;
            }
            Append(text, previous, text.Length - previous, relative);
        }
        Expand(package.MainFile, 0);
        if (visited.Count != allowed.Count) return null;
        var expanded = flat.ToString();
        if (expanded.Length > MaximumBytes || Regex.IsMatch(expanded, "(?im)^[ \\t]*(?:PATHS|ENDINC|SKIP|SKIP100|SKIP300|ENDSKIP)\\b", RegexOptions.CultureInvariant)) return null;
        var plan = EclipseControlPlanReader.Read(expanded, Path.GetFileName(package.MainFile));
        if (plan is null) return null;
        Span Locate(int offset, int length) => spans.SingleOrDefault(s => offset >= s.Start && offset + length <= s.Start + s.Length) ?? throw new FormatException();
        var lineStarts = new List<int> { 0 };
        foreach (Match newline in Regex.Matches(expanded, "\r\n|\r|\n")) lineStarts.Add(newline.Index + newline.Length);
        var targets = new List<Target>();
        foreach (var target in plan.Targets)
        {
            var span = Locate(target.Start, target.Length);
            var rowStart = lineStarts[target.Record.LineNumber - 1];
            while (rowStart < expanded.Length && expanded[rowStart] is ' ' or '\t' or '\ufeff') rowStart++;
            var rowSpan = Locate(rowStart, 1);
            if (rowSpan.Source != span.Source) return null;
            var physical = texts[span.Source];
            var line = 1 + Regex.Matches(physical[..(rowSpan.PhysicalStart + rowStart - rowSpan.Start)], "\r\n|\r|\n").Count;
            targets.Add(new(target.Record with { SourceFile = span.Source, LineNumber = line },
                Convert.ToHexStringLower(SHA256.HashData(sources[span.Source])), span.PhysicalStart + target.Start - span.Start,
                target.Length, targets.Count));
        }
        if (targets.Select(t => (t.Record.SourceFile, t.Record.LineNumber)).Distinct().Count() != targets.Count) return null;
        return new(plan.Plan.StartDate, plan.Plan.EndTimeDays, targets, sources);
    }
}
