using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompletionGeometryEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseCompletionGeometryParameters p, CancellationToken token)
    {
        if (package.Files.Count != 1 || !string.Equals(Path.GetFileName(package.MainFile), p.SourceFile, StringComparison.Ordinal))
            throw Error("PACKAGE", "Initial completion numerical editing requires a single DATA with matching source filename.");
        var inspection = EclipseDataInspector.InspectPackage(package.Files, Path.GetFileName(package.MainFile));
        if (!p.MatchesInspection(inspection)) throw Error("SOURCE", "The source record, units or initial-only connection changed, or later completion records would override it.");
        var utf8 = new UTF8Encoding(false, true);
        var original = utf8.GetString(await File.ReadAllBytesAsync(package.MainFile, token));
        var number = 0;
        foreach (Match line in Regex.Matches(original, @"[^\r\n]*(?:\r\n|\r|\n|$)", RegexOptions.CultureInvariant))
        {
            if (line.Length == 0) continue;
            if (++number != p.LineNumber) continue;
            var comment = StripComment(line.Value);
            var parts = Regex.Matches(comment, @"'(?:''|[^'])*'|[^\s/]+|/", RegexOptions.CultureInvariant).Cast<Match>().ToArray();
            if (parts.Length != p.RecordValues.Length + 1 || parts[^1].Value != "/" || !parts[..^1].Select(m => Unquote(m.Value)).SequenceEqual(p.RecordValues))
                throw Error("ROW", "The selected record must be one explicit single-line COMPDAT row; compressed or split fields are unsupported.");
            var selected = parts[p.ValueIndex]; var offset = line.Index + selected.Index;
            var target = p.TargetValue.ToString("G17", CultureInfo.InvariantCulture);
            var updated = original[..offset] + target + original[(offset + selected.Length)..];
            await File.WriteAllBytesAsync(package.MainFile, utf8.GetBytes(updated), token);
            return $"Applied COMPDAT {p.Field} {p.OriginalValue:G17}->{target} {p.Unit} at {p.SourceFile}:{p.LineNumber}; other tokens retained.";
        }
        throw Error("ROW", "The inspected completion row no longer exists.");
    }
    private static string Unquote(string value) => value.StartsWith('\'') && value.EndsWith('\'') ? value[1..^1].Replace("''", "'", StringComparison.Ordinal) : value;
    private static string StripComment(string value)
    {
        var quoted = false;
        for (var i = 0; i + 1 < value.Length; i++)
        {
            if (value[i] == '\'') quoted = !quoted;
            if (!quoted && value[i] == '-' && value[i + 1] == '-') return value[..i];
        }
        return value;
    }
    private static EclipseDeckPackageException Error(string suffix, string message) => new("ECLIPSE_COMPLETION_GEOMETRY_" + suffix, message);
}
