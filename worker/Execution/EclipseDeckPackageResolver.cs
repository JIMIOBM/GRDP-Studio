using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed class EclipseDeckPackageException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

/// <summary>
/// Resolves an ECLIPSE INCLUDE graph without rewriting the uploaded deck.
/// Only files already inside the isolated model package may be referenced.
/// </summary>
public sealed class EclipseDeckPackageResolver
{
    private const long MaxIncludedFileBytes = 64L * 1024 * 1024;
    private const int MaxFiles = 4096;
    private const int MaxDepth = 64;

    public EclipseDeckPackage Resolve(string mainFile, string packageRoot)
    {
        var root = Path.GetFullPath(packageRoot).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var main = Path.GetFullPath(mainFile);
        EnsureInside(root, main, "ECLIPSE_MAIN_OUTSIDE_PACKAGE", "The ECLIPSE main DATA file is outside its package.");
        var files = new List<string>();
        Visit(main, root, files, new HashSet<string>(StringComparer.OrdinalIgnoreCase),
            new HashSet<string>(StringComparer.OrdinalIgnoreCase), 0);
        return new EclipseDeckPackage(main, root, files);
    }

    private static void Visit(string file, string root, List<string> files, HashSet<string> visited,
        HashSet<string> active, int depth)
    {
        var full = Path.GetFullPath(file);
        EnsureInside(root, full, "ECLIPSE_INCLUDE_OUTSIDE_PACKAGE", "An ECLIPSE INCLUDE path escapes the model package.");
        RejectReparsePoints(root, full);
        if (!File.Exists(full)) throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_MISSING", $"The ECLIPSE INCLUDE file '{Path.GetFileName(full)}' does not exist.");
        if (new FileInfo(full).Length > MaxIncludedFileBytes)
            throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_TOO_LARGE", "An ECLIPSE INCLUDE file exceeds the 64 MiB limit.");
        if (!active.Add(full)) throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_CYCLE", "The ECLIPSE INCLUDE graph contains a cycle.");
        if (visited.Add(full))
        {
            if (files.Count >= MaxFiles) throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_COUNT", "The ECLIPSE INCLUDE graph contains too many files.");
            files.Add(full);
            var text = ReadUtf8(full);
            foreach (var include in EclipseIncludeParser.Parse(text))
            {
                if (depth >= MaxDepth) throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_DEPTH", "The ECLIPSE INCLUDE graph is too deep.");
                Visit(ResolveInclude(full, include), root, files, visited, active, depth + 1);
            }
        }
        active.Remove(full);
    }

    public static string ReadUtf8(string path)
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.SequentialScan);
            using var reader = new StreamReader(stream, new UTF8Encoding(false, true), detectEncodingFromByteOrderMarks: false, bufferSize: 64 * 1024);
            return reader.ReadToEnd();
        }
        catch (DecoderFallbackException) { throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_INVALID_UTF8", "An ECLIPSE deck file is not valid UTF-8."); }
        catch (IOException) { throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_READ_FAILED", "An ECLIPSE deck file could not be read."); }
    }

    public static string ResolveInclude(string includingFile, string include)
    {
        var normalized = include.Trim().Replace('\\', '/');
        if (normalized.Length == 0 || normalized.Contains('\0') || normalized.Contains(':') ||
            Path.IsPathRooted(normalized) || normalized.StartsWith("//", StringComparison.Ordinal))
            throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_PATH_INVALID", "ECLIPSE INCLUDE paths must be relative package paths.");
        try { return Path.GetFullPath(Path.Combine(Path.GetDirectoryName(includingFile)!, normalized)); }
        catch (Exception exception) when (exception is ArgumentException or NotSupportedException or PathTooLongException)
        { throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_PATH_INVALID", "An ECLIPSE INCLUDE path is invalid."); }
    }

    private static void EnsureInside(string root, string path, string code, string message)
    {
        var prefix = root + Path.DirectorySeparatorChar;
        if (!path.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) && !path.Equals(root, StringComparison.OrdinalIgnoreCase))
            throw new EclipseDeckPackageException(code, message);
    }

    private static void RejectReparsePoints(string root, string path)
    {
        var current = path;
        while (current is not null && current.StartsWith(root, StringComparison.OrdinalIgnoreCase))
        {
            if ((File.Exists(current) || Directory.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_REPARSE_POINT", "The ECLIPSE package contains a reparse point.");
            if (current.Equals(root, StringComparison.OrdinalIgnoreCase)) break;
            current = Path.GetDirectoryName(current);
        }
    }
}

public sealed record EclipseDeckPackage(string MainFile, string Root, IReadOnlyList<string> Files);

public static class EclipseIncludeParser
{
    public static IReadOnlyList<string> Parse(string text)
    {
        var includes = new List<string>();
        var index = 0;
        var inComment = false;
        while (index < text.Length)
        {
            var value = text[index];
            if (inComment)
            {
                if (value is '\r' or '\n') inComment = false;
                index++;
                continue;
            }
            if (value == '-' && index + 1 < text.Length && text[index + 1] == '-')
            {
                inComment = true;
                index += 2;
                continue;
            }
            if (value is '\'' or '"')
            {
                index = SkipQuoted(text, index, value);
                continue;
            }
            if (!IsAsciiWord(value)) { index++; continue; }
            var start = index;
            while (index < text.Length && IsAsciiWord(text[index])) index++;
            var token = text[start..index];
            if (!token.Equals("INCLUDE", StringComparison.OrdinalIgnoreCase)) continue;
            if (start > 0 && IsAsciiWord(text[start - 1])) continue;
            var cursor = index;
            while (cursor < text.Length && char.IsWhiteSpace(text[cursor])) cursor++;
            if (cursor >= text.Length) throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_SYNTAX", "An ECLIPSE INCLUDE directive has no path.");
            var quote = text[cursor] is '\'' or '"' ? text[cursor] : '\0';
            string include;
            if (quote != '\0')
            {
                cursor++;
                var builder = new StringBuilder();
                while (cursor < text.Length)
                {
                    if (text[cursor] == quote)
                    {
                        if (quote == '\'' && cursor + 1 < text.Length && text[cursor + 1] == '\'') { builder.Append('\''); cursor += 2; continue; }
                        break;
                    }
                    builder.Append(text[cursor++]);
                }
                if (cursor >= text.Length) throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_SYNTAX", "An ECLIPSE INCLUDE path is not closed.");
                include = builder.ToString();
                index = cursor + 1;
            }
            else
            {
                var pathStart = cursor;
                while (cursor < text.Length && !char.IsWhiteSpace(text[cursor]) && text[cursor] != '/') cursor++;
                include = text[pathStart..cursor];
                index = cursor;
            }
            if (string.IsNullOrWhiteSpace(include)) throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_SYNTAX", "An ECLIPSE INCLUDE directive has an empty path.");
            includes.Add(include);
        }
        return includes;
    }

    private static int SkipQuoted(string text, int start, char quote)
    {
        var index = start + 1;
        while (index < text.Length)
        {
            if (text[index] == quote)
            {
                if (quote == '\'' && index + 1 < text.Length && text[index + 1] == '\'') { index += 2; continue; }
                return index + 1;
            }
            index++;
        }
        return index;
    }

    private static bool IsAsciiWord(char value) => value is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '_';
}
