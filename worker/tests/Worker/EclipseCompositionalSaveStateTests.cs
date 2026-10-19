using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalSaveStateTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-save-state", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalSaveStateTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private (StorageResolver Storage, EclipseDeckPackage Source, EclipseDeckPackage Work, EclipseCompositionalForecastParameters Parameters) Inputs(double? target)
    {
        var input = Path.Combine(root, "models/1"); var native = Path.Combine(root, "native"); var published = Path.Combine(root, "artifacts/99");
        var work = Path.Combine(root, "jobs/100/work");
        foreach (var folder in new[] { input, native, published, work }) Directory.CreateDirectory(folder);
        var file = Path.Combine(input, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), file);
        var source = new EclipseDeckPackageResolver().Resolve(file, input);
        var paths = new[] { "SPE3_GASCOND.V0000", "SPE3_GASCOND.V0010", "SPE3_GASCOND.X0010" }.Select(name => {
            var path = Path.Combine(native, name); File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/SAVE-HISTORY", name), path);
            File.Copy(path, Path.Combine(published, "eclipse-output-" + name)); return path;
        }).ToArray();
        var catalog = EclipseCompositionalSaveCatalogReader.Read(source, paths, "2024.1");
        var raw = JsonSerializer.SerializeToUtf8Bytes(catalog, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        File.WriteAllBytes(Path.Combine(published, EclipseCompositionalSaveState.CatalogFile), raw);
        var parameters = new EclipseCompositionalForecastParameters(99, catalog.SourceFile, catalog.SourceSha256,
            Convert.ToHexStringLower(SHA256.HashData(raw)), 10, target);
        var working = Path.Combine(work, "SPE3_GASCOND.DATA"); File.Copy(file, working);
        return (new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })), source,
            new EclipseDeckPackageResolver().Resolve(working, work), parameters);
    }

    [Theory]
    [InlineData(false)] [InlineData(true)]
    public async Task PublishedStateCreatesAnExactSelfContainedSixFileForecastWithoutChangingHistory(bool change)
    {
        var input = Inputs(change ? 4500 : null); var sourceBytes = File.ReadAllBytes(input.Source.MainFile);
        var loaded = await EclipseCompositionalSaveState.LoadPublishedAsync(input.Storage, input.Parameters, input.Source, TestContext.Current.CancellationToken);
        Assert.Equal(3285, loaded.Catalog.TimeDays); Assert.Equal(3, loaded.StateFiles.Count);
        var state = await EclipseCompositionalSaveState.PreparePublishedAsync(input.Storage, input.Parameters, input.Work, TestContext.Current.CancellationToken);
        Assert.Equal(6, Directory.GetFiles(input.Work.Root, "*", SearchOption.AllDirectories).Length);
        Assert.Equal(sourceBytes, File.ReadAllBytes(input.Source.MainFile)); Assert.Equal(sourceBytes, state.Source.OriginalBytes);
        Assert.Equal(change ? 4500d : (double?)null, state.TargetGasRate);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", change ? "COMPOSITIONAL-SAVE-NESTED-LOWER.DATA" : "COMPOSITIONAL-SAVE-NESTED-ORIGINAL.DATA")),
            File.ReadAllBytes(input.Work.MainFile));
        foreach (var file in state.Catalog.Files)
            Assert.Equal(file.Sha256, Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(Path.Combine(root, "artifacts/99", file.ArtifactName)))));
    }
    [Theory]
    [InlineData("forecast")] [InlineData("source")] [InlineData("state")] [InlineData("x")]
    [InlineData("catalog")] [InlineData("extra")] [InlineData("missing")] [InlineData("rate")]
    [InlineData("path")] [InlineData("duplicate")] [InlineData("typed")] [InlineData("e100")]
    public async Task ModifiedOrIncompletePortableInputsNeverReuseAnUnverifiedHistoricalState(string fault)
    {
        var input = Inputs(4500);
        await EclipseCompositionalSaveState.PreparePublishedAsync(input.Storage, input.Parameters, input.Work, TestContext.Current.CancellationToken);
        var bindingPath = Path.Combine(input.Work.Root, EclipseCompositionalSaveState.BindingFile);
        var node = JsonNode.Parse(File.ReadAllText(bindingPath))!.AsObject();
        if (fault == "forecast") File.AppendAllText(input.Work.MainFile, "\nWELTARG\nP BHP 100 /\n/\n");
        if (fault == "source") File.AppendAllText(Path.Combine(input.Work.Root, "history/input/SPE3_GASCOND.DATA"), "\n--foreign source\n");
        if (fault is "state" or "x")
        {
            var path = Path.Combine(input.Work.Root, "history/state/" + (fault == "state" ? "SPE3_GASCOND.V0010" : "SPE3_GASCOND.X0010"));
            var bytes = File.ReadAllBytes(path); bytes[^1] ^= 1; File.WriteAllBytes(path, bytes);
        }
        if (fault == "extra") File.WriteAllText(Path.Combine(input.Work.Root, "EXTRA.INC"), "foreign");
        if (fault == "missing") File.Delete(Path.Combine(input.Work.Root, "history/state/SPE3_GASCOND.V0000"));
        if (fault == "catalog") node["catalog"]!["timeDays"] = 3284;
        if (fault == "rate") node["targetGasRate"] = 6200;
        if (fault == "path") node["catalog"]!["sourceFile"] = "../SPE3_GASCOND.DATA";
        if (fault == "typed") node["targetGasRate"] = "4500";
        if (fault == "e100") node["schemaVersion"] = "eclipse-portable-restart/2";
        if (fault is "catalog" or "rate" or "path" or "typed" or "e100") File.WriteAllText(bindingPath, node.ToJsonString());
        if (fault == "duplicate") File.WriteAllText(bindingPath, node.ToJsonString().Replace("\"targetGasRate\":4500", "\"targetGasRate\":4500,\"targetGasRate\":4500", StringComparison.Ordinal));
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveState.ReadPortable(input.Work));
    }

    [Theory]
    [InlineData("catalog-sha")] [InlineData("catalog-bytes")] [InlineData("state-bytes")] [InlineData("foreign-report")]
    public async Task ChangedPublishedArtifactsCannotReachForecastPreparation(string fault)
    {
        var input = Inputs(4500); var parameters = input.Parameters;
        if (fault == "catalog-sha") parameters = parameters with { SaveCatalogSha256 = new('0', 64) };
        if (fault == "foreign-report") parameters = parameters with { SaveReport = 11 };
        if (fault is "catalog-bytes" or "state-bytes")
        {
            var path = Path.Combine(root, "artifacts/99", fault == "catalog-bytes" ? EclipseCompositionalSaveState.CatalogFile : "eclipse-output-SPE3_GASCOND.V0010");
            var bytes = File.ReadAllBytes(path); bytes[^1] ^= 1; File.WriteAllBytes(path, bytes);
        }
        var before = File.ReadAllBytes(input.Work.MainFile);
        await Assert.ThrowsAsync<InvalidDataException>(() => EclipseCompositionalSaveState.PreparePublishedAsync(input.Storage, parameters, input.Work, TestContext.Current.CancellationToken));
        Assert.Equal(before, File.ReadAllBytes(input.Work.MainFile)); Assert.Single(Directory.GetFiles(input.Work.Root));
    }

    [Fact]
    public async Task ReuploadedPortableForecastHasItsOwnE300InspectionAndNoInitialStateEditors()
    {
        var input = Inputs(4500);
        await EclipseCompositionalSaveState.PreparePublishedAsync(input.Storage, input.Parameters, input.Work, TestContext.Current.CancellationToken);
        var service = new Grdp.SoftwareIntegration.Worker.Inspection.EclipseDataInspectionService(input.Storage);
        var hash = Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(input.Work.MainFile)));
        var outcome = await service.InspectAsync(new(input.Storage.ToStorageKey(input.Work.MainFile), hash), TestContext.Current.CancellationToken);
        Assert.Equal(200, outcome.HttpStatus);
        var body = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.ModelValidationResponse>(outcome.Body);
        Assert.Equal("eclipse_300", body.ModelKind);
        var inspection = Assert.IsType<Grdp.SoftwareIntegration.Worker.Contracts.EclipseDataInspection>(body.Inspection);
        Assert.Equal("eclipse-data-inspection/18", inspection.SchemaVersion); Assert.Equal(6, inspection.PackageFiles!.Count);
        Assert.Empty(inspection.ScheduleTimeline!);
        Assert.Null(inspection.CompositionalProductionContext); Assert.Null(inspection.CompositionalReinjectionContext); Assert.Null(inspection.CompositionalFluidPropertyContext);
        Assert.NotNull(inspection.CompositionalRestartContext); Assert.Equal(hash, inspection.CompositionalRestartContext.SourceSha256);
        Assert.Equal(4500, inspection.CompositionalRestartContext.TargetGasRate); Assert.Equal(3285, inspection.CompositionalRestartContext.HistoryCatalog.TimeDays);
        var simulation = EclipseSimulationModelReader.Read(input.Work); Assert.NotNull(simulation); Assert.Equal(300, simulation.Solver); Assert.Equal("COMPOSITIONAL", simulation.ModelType);
        File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "eclipse-compositional-restart-inspection.json"),
            JsonSerializer.Serialize(inspection, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
    }

    [Fact]
    public async Task PortableDownloadRetainsTheExactForecastOriginalSourceAndThreeNativeStateFiles()
    {
        var input = Inputs(4500);
        await EclipseCompositionalSaveState.PreparePublishedAsync(input.Storage, input.Parameters, input.Work, TestContext.Current.CancellationToken);
        var output = Path.Combine(root, "jobs/100/output"); Directory.CreateDirectory(output);
        var descriptor = await new ArtifactStore(input.Storage).WriteEclipseCompositionalForecastPackageAsync(output, input.Work, TestContext.Current.CancellationToken);
        Assert.Equal("application/zip", descriptor.ContentType);
        using var zip = System.IO.Compression.ZipFile.OpenRead(Path.Combine(output, "eclipse-forecast-applied-package.zip"));
        Assert.Equal(6, zip.Entries.Count);
        foreach (var entry in zip.Entries)
        {
            using var bytes = new MemoryStream(); using var stream = entry.Open(); await stream.CopyToAsync(bytes, TestContext.Current.CancellationToken);
            Assert.Equal(File.ReadAllBytes(Path.Combine(input.Work.Root, entry.FullName.Replace('/', Path.DirectorySeparatorChar))), bytes.ToArray());
        }
    }
}
