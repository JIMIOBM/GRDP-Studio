using System.Text.Json;
using System.Security.Cryptography;
using System.Text;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class ArtifactStoreTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-artifact-tests", Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task RedactsSensitiveJsonKeysAndValuesBeforeWritingArtifact()
    {
        var output = Path.Combine(root, "jobs", "1", "output");
        Directory.CreateDirectory(output);
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));
        using var document = JsonDocument.Parse("""
                {"C:\\Users\\operator\\secret":"D:\\private\\file",
                 "pipe":"net.pipe://localhost/pipe/private-id"}
                """);

        await store.WriteJsonElementAsync(output, "result.json", document.RootElement,
                TestContext.Current.CancellationToken);

        var json = await File.ReadAllTextAsync(Path.Combine(output, "result.json"),
                TestContext.Current.CancellationToken);
        Assert.DoesNotContain("operator", json);
        Assert.DoesNotContain("private-id", json);
        Assert.Contains("[local path]", json);
        Assert.Contains("[redacted]", json);
    }

    [Fact]
    public async Task EclipseOutputsArePublishedAsMetadataNotRawArtifacts()
    {
        var output = Path.Combine(root, "jobs", "1", "output");
        var work = Path.Combine(root, "jobs", "1", "work");
        Directory.CreateDirectory(output);
        Directory.CreateDirectory(work);
        var source = Path.Combine(work, "CASE.PRT");
        await File.WriteAllTextAsync(source, "C:\\licenses\\secret LICENSE server=private-host", TestContext.Current.CancellationToken);
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));

        var metadata = await store.DescribeEclipseOutputsAsync([source], TestContext.Current.CancellationToken);
        await store.WriteJsonAsync(output, "result.json", new { outputFiles = metadata }, TestContext.Current.CancellationToken);

        var artifact = await File.ReadAllTextAsync(Path.Combine(output, "result.json"), TestContext.Current.CancellationToken);
        Assert.DoesNotContain("secret", artifact);
        Assert.DoesNotContain("private-host", artifact);
        var outputFile = Assert.Single(metadata);
        Assert.Equal("CASE.PRT", outputFile.Filename);
        Assert.Equal(46, outputFile.SizeBytes);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes("C:\\licenses\\secret LICENSE server=private-host"))), outputFile.Sha256);
    }

    [Fact]
    public async Task EclipseOutputMetadataSerializesToBackendValidatorContract()
    {
        var work = Path.Combine(root, "jobs", "2", "work");
        Directory.CreateDirectory(work);
        var source = Path.Combine(work, "CASE.ECLEND");
        await File.WriteAllTextAsync(source, "ok", TestContext.Current.CancellationToken);
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));

        var metadata = await store.DescribeEclipseOutputsAsync([source], TestContext.Current.CancellationToken);
        var result = JsonSerializer.SerializeToElement(new
        {
            outputFiles = metadata.Select(file => new { name = file.Filename, sizeBytes = file.SizeBytes, sha256 = file.Sha256 })
        });
        var output = Assert.Single(result.GetProperty("outputFiles").EnumerateArray());

        Assert.Equal(["name", "sha256", "sizeBytes"], output.EnumerateObject().Select(property => property.Name).OrderBy(name => name));
        Assert.False(output.TryGetProperty("filename", out _));
        Assert.Equal("CASE.ECLEND", output.GetProperty("name").GetString());
        Assert.True(output.GetProperty("sizeBytes").GetInt64() >= 0);
        Assert.Matches("^[0-9a-f]{64}$", output.GetProperty("sha256").GetString());
    }

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }
}
