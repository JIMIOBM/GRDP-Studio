using System.Security.Cryptography;
using System.Text;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseDeckPackageTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-package-tests", Guid.NewGuid().ToString("N"));

    public EclipseDeckPackageTests() => Directory.CreateDirectory(root);

    [Fact]
    public void ResolvesNestedRelativeIncludesAndPreservesOrder()
    {
        var main = Path.Combine(root, "CASE.DATA");
        File.WriteAllText(main, "RUNSPEC\nINCLUDE 'deck/grid.inc' /\n", Encoding.UTF8);
        Directory.CreateDirectory(Path.Combine(root, "deck", "parts"));
        File.WriteAllText(Path.Combine(root, "deck", "grid.inc"), "GRID\nINCLUDE 'parts/props.inc' /\n", Encoding.UTF8);
        File.WriteAllText(Path.Combine(root, "deck", "parts", "props.inc"), "PROPS\n", Encoding.UTF8);

        var package = new EclipseDeckPackageResolver().Resolve(main, root);

        Assert.Equal(3, package.Files.Count);
        Assert.Equal(main, package.Files[0]);
        Assert.Equal(Path.Combine(root, "deck", "grid.inc"), package.Files[1]);
        Assert.Equal(Path.Combine(root, "deck", "parts", "props.inc"), package.Files[2]);
    }

    [Theory]
    [InlineData("INCLUDE 'missing.inc' /\n", "ECLIPSE_INCLUDE_MISSING")]
    [InlineData("INCLUDE '../outside.inc' /\n", "ECLIPSE_INCLUDE_OUTSIDE_PACKAGE")]
    [InlineData("INCLUDE 'missing.inc'\n", "ECLIPSE_INCLUDE_MISSING")]
    public void RejectsMissingOrEscapingDependencies(string deck, string code)
    {
        var main = Path.Combine(root, "CASE.DATA");
        File.WriteAllText(main, deck, Encoding.UTF8);

        var error = Assert.Throws<EclipseDeckPackageException>(() => new EclipseDeckPackageResolver().Resolve(main, root));

        Assert.Equal(code, error.Code);
    }

    [Fact]
    public void RejectsIncludeCycles()
    {
        var main = Path.Combine(root, "CASE.DATA");
        File.WriteAllText(main, "INCLUDE 'a.inc' /\n", Encoding.UTF8);
        File.WriteAllText(Path.Combine(root, "a.inc"), "INCLUDE 'CASE.DATA' /\n", Encoding.UTF8);

        var error = Assert.Throws<EclipseDeckPackageException>(() => new EclipseDeckPackageResolver().Resolve(main, root));

        Assert.Equal("ECLIPSE_INCLUDE_CYCLE", error.Code);
    }

    [Fact]
    public async Task StorageCopiesTheCompletePackageIntoTheEclipseWorkDirectory()
    {
        var package = Path.Combine(root, "models", "7", "1", "deck");
        Directory.CreateDirectory(package);
        var main = Path.Combine(package, "CASE.DATA");
        var include = Path.Combine(package, "grid.inc");
        await File.WriteAllTextAsync(main, "RUNSPEC\nINCLUDE 'grid.inc' /\n", TestContext.Current.CancellationToken);
        await File.WriteAllTextAsync(include, "GRID\n", TestContext.Current.CancellationToken);
        var resolver = new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root }));

        var directories = resolver.CreateRunDirectories(71, main);
        try
        {
            var workMain = resolver.CopyInputPackageToWork(directories);
            Assert.Equal(Path.Combine(directories.Work, "deck", "CASE.DATA"), workMain);
            Assert.True(File.Exists(Path.Combine(directories.Work, "deck", "grid.inc")));
            await using var stream = File.OpenRead(main);
            Assert.Equal(Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, TestContext.Current.CancellationToken)), await resolver.ComputeSha256Async(workMain, TestContext.Current.CancellationToken));
        }
        finally { StorageResolver.TryDeleteDirectory(directories.Root); }
    }

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }
}
