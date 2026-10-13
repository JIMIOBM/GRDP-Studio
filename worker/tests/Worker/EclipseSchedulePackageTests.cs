using System.IO.Compression;
using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseSchedulePackageTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-schedule-package", Guid.NewGuid().ToString("N"));
    public EclipseSchedulePackageTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);

    [Fact]
    public async Task ExportsOnlyResolvedInputsWithOriginalBytesAndRelativeNames()
    {
        var (store, output, package) = Inputs();
        File.WriteAllText(Path.Combine(package.Root, "CASE.PRT"), "private engine diagnostic");
        var descriptor = await store.WriteEclipseSchedulePackageAsync(output, package, TestContext.Current.CancellationToken);
        var destination = Path.Combine(output, "eclipse-schedule-applied-package.zip");
        Assert.Equal("jobs/1/output/eclipse-schedule-applied-package.zip", descriptor.StorageKey);
        Assert.Equal("application/zip", descriptor.ContentType);
        Assert.Equal(new FileInfo(destination).Length, descriptor.Size);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(destination))), descriptor.Sha256);
        using var zip = ZipFile.OpenRead(destination);
        Assert.Equal(new[] { "CASE.DATA", "includes/BASE.SCH" }, zip.Entries.Select(entry => entry.FullName).ToArray());
        foreach (var entry in zip.Entries)
        {
            using var stream = entry.Open();
            using var bytes = new MemoryStream();
            await stream.CopyToAsync(bytes, TestContext.Current.CancellationToken);
            Assert.Equal(File.ReadAllBytes(Path.Combine(package.Root, entry.FullName)), bytes.ToArray());
        }
    }

    [Theory]
    [InlineData("1.5")]
    public async Task CompletionFactorArchiveContainsAppliedTokenAndAllUnchangedDependencies(string target)
    {
        var (store, output, package) = Inputs();
        var path = package.Files[1];
        var content = "COMPDAT\r\n 'G1' 14 2 1 1 'OPEN' 1* 3.028 0.656 4945.379 2* 'Z' 85.765 /\r\n/\r\n";
        await File.WriteAllTextAsync(path, content, TestContext.Current.CancellationToken);
        var main = File.ReadAllBytes(package.MainFile);
        var parameters = new EclipseCompletionFactorParameters(74, "G1", "includes/BASE.SCH", 2, "14", "2", "1", "1", 3.028, 1.5);
        await EclipseScheduleEditor.ApplyCompletionFactorAsync(package, parameters, TestContext.Current.CancellationToken);
        await store.WriteEclipseSchedulePackageAsync(output, package, TestContext.Current.CancellationToken);
        using var zip = ZipFile.OpenRead(Path.Combine(output, "eclipse-schedule-applied-package.zip"));
        Assert.Equal(new[] { "CASE.DATA", "includes/BASE.SCH" }, zip.Entries.Select(e => e.FullName));
        using var reader = new StreamReader(zip.GetEntry("includes/BASE.SCH")!.Open());
        Assert.Equal(content.Replace("3.028", target, StringComparison.Ordinal), await reader.ReadToEndAsync(TestContext.Current.CancellationToken));
        Assert.Equal(main, File.ReadAllBytes(package.MainFile));
    }

    [Theory]
    [InlineData("outside")]
    [InlineData("duplicate")]
    [InlineData("missing")]
    public async Task RejectsUnsafeOrMissingInputsWithoutLeavingAnArchive(string error)
    {
        var (store, output, package) = Inputs();
        var unsafeFile = Path.Combine(root, "other.DATA");
        File.WriteAllText(unsafeFile, "not a dependency");
        string[] files = error switch
        {
            "outside" => [unsafeFile],
            "duplicate" => [package.MainFile, package.MainFile],
            _ => [Path.Combine(package.Root, "missing.INC")]
        };
        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteEclipseSchedulePackageAsync(output,
            new EclipseDeckPackage(package.MainFile, package.Root, files), TestContext.Current.CancellationToken));
        Assert.Empty(Directory.EnumerateFiles(output));
    }

    [Fact]
    public async Task RejectsOversizedInputBeforeCreatingArchiveEntries()
    {
        var (store, output, package) = Inputs();
        using (var stream = File.OpenWrite(package.MainFile)) stream.SetLength(256_000_001);
        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteEclipseSchedulePackageAsync(output, package,
            TestContext.Current.CancellationToken));
        Assert.Empty(Directory.EnumerateFiles(output));
    }

    [Fact]
    public async Task CancellationNeverPublishesAnIncompleteZip()
    {
        var (store, output, package) = Inputs();
        using var cancellation = new CancellationTokenSource();
        await cancellation.CancelAsync();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => store.WriteEclipseSchedulePackageAsync(output, package,
            cancellation.Token));
        Assert.Empty(Directory.EnumerateFiles(output));
    }

    private (ArtifactStore Store, string Output, EclipseDeckPackage Package) Inputs()
    {
        var output = Path.Combine(root, "jobs", "1", "output");
        var work = Path.Combine(root, "jobs", "1", "work");
        Directory.CreateDirectory(output);
        Directory.CreateDirectory(Path.Combine(work, "includes"));
        var deck = Path.Combine(work, "CASE.DATA");
        var include = Path.Combine(work, "includes", "BASE.SCH");
        File.WriteAllBytes(deck, "SCHEDULE\r\nINCLUDE\r\n 'includes/BASE.SCH' /\r\n"u8.ToArray());
        File.WriteAllBytes(include, "WELOPEN\r\n 'G1' 'SHUT' /\r\n/\r\n"u8.ToArray());
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));
        return (store, output, new EclipseDeckPackage(deck, work, [deck, include]));
    }
}
