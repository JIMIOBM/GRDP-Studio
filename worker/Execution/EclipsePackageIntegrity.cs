using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipsePackageIntegrity
{
    internal static bool IsValidManifest(IReadOnlyList<EclipsePackageFile> files)
    {
        if (files.Count is < 1 or > 4096) return false;
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var file in files)
        {
            var path = file.RelativePath;
            if (string.IsNullOrWhiteSpace(path) || path.Contains('\\') || path.Length > 512 || path.StartsWith('/') || path.Contains("//") || path.Contains(':') ||
                path.Split('/').Any(segment => segment is "" or "." or ".." || segment.Any(char.IsControl)) || !paths.Add(path)) return false;
            if (file.SizeBytes < 0 || file.SizeBytes > 64L * 1024 * 1024) return false;
            if (file.Sha256 is not { Length: 64 } || file.Sha256.Any(value => value is not (>= '0' and <= '9' or >= 'a' and <= 'f'))) return false;
        }
        return true;
    }

    internal static async Task VerifyAsync(EclipseDeckPackage package,
        IReadOnlyList<EclipsePackageFile>? expected, CancellationToken cancellationToken)
    {
        if (expected is null) return;
        var paths = Directory.EnumerateFiles(package.Root, "*", SearchOption.AllDirectories).ToArray();
        var actual = new List<EclipsePackageFile>(paths.Length);
        foreach (var path in paths.OrderBy(item => Path.GetRelativePath(package.Root, item), StringComparer.OrdinalIgnoreCase))
        {
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                var info = new FileInfo(path);
                await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
                var hash = await SHA256.HashDataAsync(stream, cancellationToken);
                actual.Add(new EclipsePackageFile(
                    Path.GetRelativePath(package.Root, path).Replace(Path.DirectorySeparatorChar, '/'),
                    info.Length,
                    Convert.ToHexStringLower(hash)));
            }
            catch (IOException)
            {
                throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_READ_FAILED", "An ECLIPSE package file could not be verified.");
            }
        }
        if (actual.Count != expected.Count || actual.Where((file, index) => file != expected[index]).Any())
            throw new EclipseDeckPackageException("ECLIPSE_PACKAGE_INTEGRITY_MISMATCH", "The ECLIPSE package changed after validation; run was not started.");
    }
}
