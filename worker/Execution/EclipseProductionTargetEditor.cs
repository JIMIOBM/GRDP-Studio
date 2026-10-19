using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

/// <summary>Edits a single literal initial ORAT token, preserving every other byte of a supported UTF-8 deck.</summary>
internal static class EclipseProductionTargetEditor
{
    private sealed record Token(string Value, int Start, int Length);
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseProductionTargetParameters parameters, CancellationToken cancellationToken)
    {
        if (parameters.SourceSha256 is not null) return await ApplyPackageAsync(package, parameters, cancellationToken);
        // INCLUDE ordering and inherited controls require a separate contract; never guess them here.
        if (package.Files.Count != 1 || !Path.GetFileName(package.MainFile).Equals(parameters.SourceFile, StringComparison.Ordinal)) Fail();
        var bytes = await File.ReadAllBytesAsync(package.MainFile, cancellationToken);
        var encoding = new UTF8Encoding(false, true);
        var text = encoding.GetString(bytes);
        if (EclipseIncludeParser.Parse(text).Count != 0 || Keyword(text, "FIELD").Count != 1
            || Keyword(text, "METRIC|LAB|PVT-M").Count != 0) Fail();
        var schedule = Keyword(text, "SCHEDULE");
        if (schedule.Count != 1) Fail();
        var start = schedule[0].Index + schedule[0].Length;
        var laterTime = Keyword(text[start..], "DATES|TSTEP|TIME|END");
        var end = laterTime.Count > 0 ? start + laterTime[0].Index : text.Length;
        var initial = text[start..end];
        if (Keyword(initial, "WCONHIST|WELOPEN|WELTARG|WELCNTL|WELPI|ACTION|ACTIONX|WCONINJH").Count != 0) Fail();
        Token? target = null;
        foreach (Match block in Keyword(initial, "WCONPROD|WCONINJE"))
        {
            var keyword = block.Groups[1].Value.ToUpperInvariant();
            var record = new List<Token>();
            var terminated = false;
            foreach (Match lex in Regex.Matches(text[(start + block.Index + block.Length)..end], """--[^\r\n]*|'(?:[^']|'')*'|"(?:[^"]|"")*"|/|[^\s/]+"""))
            {
                if (lex.Value.StartsWith("--", StringComparison.Ordinal)) continue;
                if (lex.Value != "/")
                {
                    if (record.Count >= 128) Fail();
                    record.Add(new(Unquote(lex.Value), start + block.Index + block.Length + lex.Index, lex.Length));
                    continue;
                }
                if (record.Count == 0) { terminated = true; break; }
                if (record[0].Value.Contains('*') || record[0].Value.Contains('?')) Fail();
                if (record[0].Value == parameters.Well)
                {
                    if (keyword != "WCONPROD" || target is not null || record.Count < 4
                        || record[1].Value != "OPEN" || record[2].Value != "ORAT"
                        || 1 + Regex.Matches(text[..record[0].Start], "\r\n|\r|\n").Count != parameters.LineNumber
                        || !double.TryParse(record[3].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var original)
                        || !double.IsFinite(original) || original != parameters.OriginalOilRate) Fail();
                    target = record[3];
                }
                record.Clear();
            }
            if (!terminated) Fail();
        }
        if (target is null) Fail();
        var replacement = parameters.TargetOilRate.ToString("G17", CultureInfo.InvariantCulture);
        var updated = text[..target!.Start] + replacement + text[(target.Start + target.Length)..];
        // Round-trip before writing also rejects invalid UTF-8 and retains a possible BOM verbatim.
        if (!encoding.GetBytes(text).SequenceEqual(bytes)) Fail();
        cancellationToken.ThrowIfCancellationRequested();
        await File.WriteAllBytesAsync(package.MainFile, encoding.GetBytes(updated), cancellationToken);
        return $"Applied initial WCONPROD {parameters.Well} OPEN ORAT {parameters.OriginalOilRate:G17}->{replacement} STB/day at {parameters.SourceFile}:{parameters.LineNumber}; other constraints preserved.";
    }
    private static async Task<string> ApplyPackageAsync(EclipseDeckPackage package, EclipseProductionTargetParameters p, CancellationToken cancellationToken)
    {
        var snapshot = EclipsePackageProductionTargetReader.Read(package);
        var matches = snapshot?.Targets.Where(t => t.Well == p.Well && t.SourceFile == p.SourceFile
            && t.LineNumber == p.LineNumber && t.OriginalOilRate == p.OriginalOilRate && t.SourceSha256 == p.SourceSha256).ToArray();
        if (matches is not { Length: 1 } || !double.IsFinite(p.TargetOilRate) || p.TargetOilRate <= 0
            || p.TargetOilRate > 100_000_000 || p.TargetOilRate == p.OriginalOilRate)
            throw new EclipseDeckPackageException("ECLIPSE_PACKAGE_PRODUCTION_TARGET_MISMATCH", "The initial INCLUDE target, source hash or execution order no longer matches the inspected package.");
        var target = matches[0];
        var path = Path.Combine(package.Root, target.SourceFile.Replace('/', Path.DirectorySeparatorChar));
        var bytes = await File.ReadAllBytesAsync(path, cancellationToken);
        if (Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes)) != p.SourceSha256)
            throw new EclipseDeckPackageException("ECLIPSE_PACKAGE_PRODUCTION_TARGET_MISMATCH", "The selected INCLUDE file changed before editing.");
        var encoding = new UTF8Encoding(false, true);
        var text = encoding.GetString(bytes);
        var replacement = p.TargetOilRate.ToString("G17", CultureInfo.InvariantCulture);
        var updated = text[..target.ValueStart] + replacement + text[(target.ValueStart + target.ValueLength)..];
        cancellationToken.ThrowIfCancellationRequested();
        await File.WriteAllBytesAsync(path, encoding.GetBytes(updated), cancellationToken);
        return $"Applied initial WCONPROD {p.Well} OPEN ORAT {p.OriginalOilRate:G17}->{replacement} STB/day at {p.SourceFile}:{p.LineNumber}; INCLUDE files and other controls preserved.";
    }
    private static MatchCollection Keyword(string text, string alternatives) => Regex.Matches(text,
        "(?im)^[ \\t]*(" + alternatives + ")[ \\t]*(?:(?:--|=)[^\\r\\n]*)?\\r?$",
        RegexOptions.CultureInvariant);
    private static string Unquote(string value) => value.Length >= 2 && value[0] is '\'' or '"' && value[^1] == value[0] ? value[1..^1] : value;
    private static void Fail() => throw new EclipseDeckPackageException("ECLIPSE_PRODUCTION_TARGET_MISMATCH",
        "Initial ORAT requires one FIELD DATA deck, a unique literal OPEN WCONPROD record and matching source, line and original value. INCLUDE, templates and ambiguous initial controls are not supported.");
}
