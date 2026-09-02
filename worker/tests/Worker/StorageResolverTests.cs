using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class StorageResolverTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-worker-storage-tests", Guid.NewGuid().ToString("N"));
    private readonly StorageResolver resolver;

    public StorageResolverTests()
    {
        Directory.CreateDirectory(Path.Combine(root, "models", "1"));
        resolver = new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root }));
    }

    [Theory]
    [InlineData("../outside.pips")]
    [InlineData("models/../../outside.pips")]
    [InlineData("C:\\outside.pips")]
    [InlineData("C:outside.pips")]
    [InlineData("\\\\server\\share\\outside.pips")]
    public void RejectsTraversalAndAbsoluteStorageKeys(string key)
    {
        var error = Assert.Throws<StorageException>(() => resolver.ResolveExistingModel(key));
        Assert.Contains(error.Code, new[] { "INVALID_STORAGE_KEY", "STORAGE_ROOT_ESCAPE" });
    }

    [Fact]
    public async Task ResolvesRelativeModelAndComputesExactSha256()
    {
        var path = Path.Combine(root, "models", "1", "well.pips");
        await File.WriteAllBytesAsync(path, [1, 2, 3, 4], TestContext.Current.CancellationToken);
        Assert.Equal(path, resolver.ResolveExistingModel("models/1/well.pips"));
        Assert.Equal(
            Convert.ToHexStringLower(SHA256.HashData([1, 2, 3, 4])),
            await resolver.ComputeSha256Async(path, TestContext.Current.CancellationToken));
    }

    [Fact]
    public void MissingModelReturnsStorageNotFound()
    {
        var error = Assert.Throws<StorageException>(() => resolver.ResolveExistingModel("models/1/missing.pips"));
        Assert.Equal("MODEL_NOT_FOUND", error.Code);
        Assert.Equal(404, error.HttpStatus);
    }

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }
}
