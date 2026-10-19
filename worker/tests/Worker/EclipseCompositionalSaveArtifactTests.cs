using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalSaveArtifactTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-save-artifacts", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalSaveArtifactTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private (ArtifactStore Store, string Source, string Output, EclipseCompositionalSaveFile File) Input(string name)
    {
        var folder = Path.Combine(root, "native"); var output = Path.Combine(root, "jobs/77/output");
        Directory.CreateDirectory(folder); Directory.CreateDirectory(output);
        var source = Path.Combine(folder, name);
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/SAVE-HISTORY", name), source);
        var bytes = File.ReadAllBytes(source);
        var record = new EclipseCompositionalSaveFile(name, "eclipse-output-" + name, bytes.Length, Convert.ToHexStringLower(SHA256.HashData(bytes)));
        var storage = new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root }));
        return (new ArtifactStore(storage), source, output, record);
    }

    [Theory]
    [InlineData("SPE3_GASCOND.V0000")] [InlineData("SPE3_GASCOND.V0010")] [InlineData("SPE3_GASCOND.X0010")]
    public async Task CatalogBoundSaveAndIdentityReportAreCopiedByteForByte(string name)
    {
        var input = Input(name);
        var result = await input.Store.CopyEclipseCompositionalSaveOutputAsync(input.Output, input.Source, input.File, TestContext.Current.CancellationToken);
        Assert.Equal(input.File.Sha256, result.Sha256); Assert.Equal(input.File.SizeBytes, result.Size);
        Assert.Equal("application/octet-stream", result.ContentType);
        Assert.Equal(File.ReadAllBytes(input.Source), File.ReadAllBytes(Path.Combine(input.Output, input.File.ArtifactName)));
    }

    [Theory]
    [InlineData("hash")] [InlineData("size")] [InlineData("name")] [InlineData("artifact")] [InlineData("changed-source")] [InlineData("extension")]
    public async Task UnboundChangedOrTextFilesCannotCreateDownloadableSave(string fault)
    {
        var input = Input("SPE3_GASCOND.V0010"); var expected = input.File; var source = input.Source;
        if (fault == "hash") expected = expected with { Sha256 = new('0', 64) };
        if (fault == "size") expected = expected with { SizeBytes = expected.SizeBytes + 1 };
        if (fault == "name") expected = expected with { Name = "OTHER.V0010" };
        if (fault == "artifact") expected = expected with { ArtifactName = "../output.V0010" };
        if (fault == "changed-source") { var bytes = File.ReadAllBytes(source); bytes[^1] ^= 1; File.WriteAllBytes(source, bytes); }
        if (fault == "extension") { source = Path.ChangeExtension(source, "PRT"); File.Copy(input.Source, source); expected = expected with { Name = Path.GetFileName(source), ArtifactName = "eclipse-output-" + Path.GetFileName(source) }; }
        await Assert.ThrowsAsync<InvalidDataException>(() => input.Store.CopyEclipseCompositionalSaveOutputAsync(input.Output, source, expected, TestContext.Current.CancellationToken));
        Assert.Empty(Directory.GetFiles(input.Output));
    }

    [Theory]
    [InlineData("SPE3_GASCOND.V0000")] [InlineData("SPE3_GASCOND.V0010")] [InlineData("SPE3_GASCOND.X0010")]
    public async Task GenericResultCopyRemainsClosedForUnboundHistoryFiles(string name)
    {
        var input = Input(name);
        await Assert.ThrowsAsync<InvalidDataException>(() => input.Store.CopyEclipseBinaryOutputAsync(input.Output, input.Source, TestContext.Current.CancellationToken));
        Assert.Empty(Directory.GetFiles(input.Output));
    }
}
