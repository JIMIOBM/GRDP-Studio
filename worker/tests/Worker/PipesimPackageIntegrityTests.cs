using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Xunit;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class PipesimPackageIntegrityTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-pipesim-package-integrity", Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task DescribesAndDetectsCompanionFileMutation()
    {
        Directory.CreateDirectory(Path.Combine(root, "assets"));
        var model = Path.Combine(root, "model.pips");
        var companion = Path.Combine(root, "assets", "completion.dat");
        await File.WriteAllTextAsync(model, "model", TestContext.Current.CancellationToken);
        await File.WriteAllTextAsync(companion, "companion", TestContext.Current.CancellationToken);

        var expected = await PipesimPackageIntegrity.DescribeAsync(root, TestContext.Current.CancellationToken);
        Assert.True(PipesimPackageIntegrity.IsValidManifest(expected));
        await PipesimPackageIntegrity.VerifyAsync(root, expected, TestContext.Current.CancellationToken);

        await File.AppendAllTextAsync(companion, " changed", TestContext.Current.CancellationToken);
        var error = await Assert.ThrowsAsync<StorageException>(() =>
            PipesimPackageIntegrity.VerifyAsync(root, expected, TestContext.Current.CancellationToken));
        Assert.Equal("PTK_PACKAGE_INTEGRITY_MISMATCH", error.Code);
    }

    [Theory]
    [InlineData("../outside.inc")]
    [InlineData("/absolute.inc")]
    [InlineData("assets\\completion.dat")]
    [InlineData("assets//completion.dat")]
    [InlineData("assets:completion.dat")]
    public void RejectsUnsafeManifestPaths(string path)
    {
        var manifest = new[] { new EclipsePackageFile(path, 1, new string('a', 64)) };

        Assert.False(PipesimPackageIntegrity.IsValidManifest(manifest));
    }

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }
}
