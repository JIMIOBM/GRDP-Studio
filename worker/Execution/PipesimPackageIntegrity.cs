using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Storage;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class PipesimPackageIntegrity
{
    private const int MaxFiles = 4096;
    private const long MaxFileBytes = 512L * 1024 * 1024;

    internal static bool IsValidManifest(IReadOnlyList<EclipsePackageFile> files)
    {
        if (files.Count is < 1 or > MaxFiles) return false;
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var file in files)
        {
            var path = file.RelativePath;
            if (string.IsNullOrWhiteSpace(path) || path.Contains('\\') || path.Length > 512 || path.StartsWith('/') ||
                path.Contains("//") || path.Contains(':') || path.Split('/').Any(segment =>
                    segment is "" or "." or ".." || segment.Any(char.IsControl)) || !paths.Add(path)) return false;
            if (file.SizeBytes < 0 || file.SizeBytes > MaxFileBytes) return false;
            if (file.Sha256 is not { Length: 64 } || file.Sha256.Any(value => value is not (>= '0' and <= '9' or >= 'a' and <= 'f'))) return false;
        }
        return true;
    }

    internal static async Task<IReadOnlyList<EclipsePackageFile>> DescribeAsync(
        string packageRoot, CancellationToken cancellationToken)
    {
        var paths = EnumerateFiles(packageRoot);
        if (paths.Count is < 1 or > MaxFiles)
            throw new StorageException("PTK_PACKAGE_TOO_LARGE", "The PIPESIM model package contains an unsupported number of files.");

        var files = new List<EclipsePackageFile>(paths.Count);
        foreach (var path in paths.OrderBy(item => Path.GetRelativePath(packageRoot, item), StringComparer.OrdinalIgnoreCase))
        {
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                var info = new FileInfo(path);
                if (info.Length > MaxFileBytes)
                    throw new StorageException("PTK_PACKAGE_TOO_LARGE", "A PIPESIM model package file exceeds the integrity limit.");
                await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read,
                    64 * 1024, FileOptions.SequentialScan | FileOptions.Asynchronous);
                var hash = await SHA256.HashDataAsync(stream, cancellationToken);
                files.Add(new EclipsePackageFile(
                    Path.GetRelativePath(packageRoot, path).Replace(Path.DirectorySeparatorChar, '/'),
                    info.Length,
                    Convert.ToHexStringLower(hash)));
            }
            catch (StorageException)
            {
                throw;
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
            {
                throw new StorageException("PTK_PACKAGE_READ_FAILED", "A PIPESIM model package file could not be hashed.");
            }
        }
        return files;
    }

    internal static async Task VerifyAsync(
        string packageRoot, IReadOnlyList<EclipsePackageFile>? expected, CancellationToken cancellationToken)
    {
        if (expected is null) return;
        if (!IsValidManifest(expected))
            throw new StorageException("INVALID_PACKAGE_MANIFEST", "The persisted PIPESIM package manifest is invalid.");
        var actual = await DescribeAsync(packageRoot, cancellationToken);
        if (actual.Count != expected.Count || actual.Where((file, index) => file != expected[index]).Any())
            throw new StorageException("PTK_PACKAGE_INTEGRITY_MISMATCH", "The PIPESIM package changed after validation; run was not started.");
    }

    private static List<string> EnumerateFiles(string packageRoot)
    {
        if (!Directory.Exists(packageRoot))
            throw new StorageException("PTK_PACKAGE_MISSING", "The PIPESIM model package directory does not exist.");
        var result = new List<string>();
        var pending = new Stack<string>();
        pending.Push(packageRoot);
        while (pending.Count > 0)
        {
            var current = pending.Pop();
            RejectReparsePoint(current);
            foreach (var file in Directory.EnumerateFiles(current, "*", SearchOption.TopDirectoryOnly))
            {
                RejectReparsePoint(file);
                result.Add(file);
                if (result.Count > MaxFiles) return result;
            }
            foreach (var directory in Directory.EnumerateDirectories(current, "*", SearchOption.TopDirectoryOnly))
            {
                RejectReparsePoint(directory);
                pending.Push(directory);
            }
        }
        return result;
    }

    private static void RejectReparsePoint(string path)
    {
        if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
            throw new StorageException("REPARSE_POINT_REJECTED", "PIPESIM package files may not contain reparse points.");
    }
}
