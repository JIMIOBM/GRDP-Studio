using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipsePackageIntegrityTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-package-integrity", Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task VerifiesResolvedFilesBeforeExecution()
    {
        Directory.CreateDirectory(root);
        var main = Path.Combine(root, "CASE.DATA");
        var include = Path.Combine(root, "grid.inc");
        await File.WriteAllTextAsync(main, "RUNSPEC\nINCLUDE 'grid.inc' /\n", TestContext.Current.CancellationToken);
        await File.WriteAllTextAsync(include, "GRID\n", TestContext.Current.CancellationToken);
        var package = new EclipseDeckPackageResolver().Resolve(main, root);
        var expected = package.Files
            .OrderBy(path => Path.GetRelativePath(root, path), StringComparer.OrdinalIgnoreCase)
            .Select(path => new EclipsePackageFile(
                Path.GetRelativePath(root, path).Replace(Path.DirectorySeparatorChar, '/'),
                new FileInfo(path).Length,
                Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(path)))))
            .ToArray();

        Assert.True(EclipsePackageIntegrity.IsValidManifest(expected));
        await EclipsePackageIntegrity.VerifyAsync(package, expected, TestContext.Current.CancellationToken);

        await File.AppendAllTextAsync(include, "PORO\n", TestContext.Current.CancellationToken);
        var error = await Assert.ThrowsAsync<EclipseDeckPackageException>(() =>
            EclipsePackageIntegrity.VerifyAsync(package, expected, TestContext.Current.CancellationToken));
        Assert.Equal("ECLIPSE_PACKAGE_INTEGRITY_MISMATCH", error.Code);
    }

    [Theory]
    [InlineData("../outside.inc")]
    [InlineData("/absolute.inc")]
    [InlineData("include\\grid.inc")]
    [InlineData("include//grid.inc")]
    [InlineData("include:bad")]
    public void RejectsUnsafeManifestPaths(string path)
    {
        var manifest = new[] { new EclipsePackageFile(path, 1, new string('a', 64)) };

        Assert.False(EclipsePackageIntegrity.IsValidManifest(manifest));
    }

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }
}
