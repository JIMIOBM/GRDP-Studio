using System.Text.Json;
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

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }
}
