using System.Security.Cryptography;
using System.Text;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseDataInspectionTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-inspection-tests", Guid.NewGuid().ToString("N"));

    public EclipseDataInspectionTests() => Directory.CreateDirectory(Path.Combine(root, "models"));

    [Fact]
    public void InspectorExtractsOnlyFrozenOverviewFields()
    {
        var inspection = Inspect("-- RUNSPEC\nRUNSPEC\nGRID\nRUNSPEC\nMETRIC\nFIELD\nOIL\nWATER\nOIL\nDIMENS\n 10 20 30 /\n'ignored GAS'\n\"GAS\"\nSCHEDULE\n");

        Assert.Equal("eclipse-data-inspection/1", inspection.SchemaVersion);
        Assert.Equal("CASE.DATA", inspection.CaseName);
        Assert.Equal(["RUNSPEC", "GRID", "SCHEDULE"], inspection.Sections);
        Assert.Equal("METRIC", inspection.UnitSystem);
        Assert.Equal(["OIL", "WATER", "GAS"], inspection.Phases);
        Assert.Equal(new EclipseDimensions(10, 20, 30), inspection.Dimensions);
    }

    [Theory]
    [InlineData("PVT-M\n")]
    [InlineData("pvt-m\n")]
    [InlineData("Pvt-M\n")]
    public void InspectorRecognizesHyphenatedPvtMUnitSystem(string deck)
    {
        Assert.Equal("PVT-M", Inspect(deck).UnitSystem);
    }

    [Fact]
    public void InspectorKeepsOrdinaryHyphenTokenizationForNonPvtIdentifiers()
    {
        var inspection = Inspect("MY-WELL\nRUNSPEC\nMETRIC\n");

        Assert.Equal(["RUNSPEC"], inspection.Sections);
        Assert.Equal("METRIC", inspection.UnitSystem);
    }

    [Fact]
    public void InspectorReturnsEmptySectionsWhenNoneRecognized()
    {
        var inspection = Inspect("SOMETHING\nANOTHER THING\n");

        Assert.NotNull(inspection.Sections);
        Assert.Empty(inspection.Sections);
        Assert.Empty(inspection.Phases);
        Assert.Null(inspection.UnitSystem);
    }

    [Fact]
    public void InspectorAcceptsDimensionsAtTheMaximumAndRejectsAbove()
    {
        Assert.Equal(new EclipseDimensions(1_000_000, 2, 3), Inspect("DIMENS\n1000000 2 3 /\n").Dimensions);
        Assert.Null(Inspect("DIMENS\n1000001 2 3 /\n").Dimensions);
        Assert.Null(Inspect("DIMENS\n1 1000001 3 /\n").Dimensions);
        Assert.Null(Inspect("DIMENS\n1 2 1000001 /\n").Dimensions);
    }

    [Theory]
    [InlineData("INCLUDE 'x'\n")]
    [InlineData("FOO INCLUDE 'x'\n")]
    [InlineData("\"INCLUDE\"\n")]
    public void InspectorRejectsUnquotedInclude(string deck)
    {
        var error = Assert.Throws<EclipseDataInspectionException>(() => Inspect(deck));
        Assert.Equal("ECLIPSE_INCLUDE_UNSUPPORTED", error.Code);
    }

    [Theory]
    [InlineData("'INCLUDE'\n-- INCLUDE\nFOOINCLUDE INCLUDE_FILE\n1INCLUDE\n")]
    [InlineData("'it''s INCLUDE'\nRUNSPEC\n")]
    public void InspectorPreservesIncludeLiteralAndAsciiBoundaryRules(string deck)
    {
        Assert.NotNull(Inspect(deck));
    }

    [Theory]
    [InlineData("DIMENS\n1 2 3 /\nDIMENS\n4 5 6 /\n")]
    [InlineData("DIMENS\n1 2 X /\n")]
    [InlineData("DIMENS\n01 2 3 /\n")]
    [InlineData("DIMENS\n1 2 3\n")]
    public void InspectorReturnsNullDimensionsForDuplicateOrMalformedCandidates(string deck)
    {
        Assert.Null(Inspect(deck).Dimensions);
    }

    [Fact]
    public void InspectorRejectsNulAndMalformedUtf8()
    {
        var nul = Assert.Throws<EclipseDataInspectionException>(() => Inspect("RUNSPEC\0"));
        Assert.Equal("ECLIPSE_DATA_NUL_BYTE", nul.Code);

        var path = Path.Combine(root, "invalid.DATA");
        File.WriteAllBytes(path, [0xc3, 0x28]);
        var error = Assert.Throws<EclipseDataInspectionException>(() => EclipseDataInspector.Inspect(path, "invalid.DATA"));
        Assert.Equal("ECLIPSE_DATA_INVALID_UTF8", error.Code);
    }

    [Fact]
    public void InspectorEnforcesTokenAndDimensResourceLimits()
    {
        var token = Assert.Throws<EclipseDataInspectionException>(() => Inspect(new string('A', 1025)));
        Assert.Equal("ECLIPSE_DATA_TOKEN_TOO_LONG", token.Code);

        var oversizedDimens = "DIMENS\n" + string.Join(' ', Enumerable.Repeat(new string('1', 1024), 65)) + " /";
        Assert.Null(Inspect(oversizedDimens).Dimensions);
    }

    [Fact]
    public void InspectorEnforcesPhysicalLineAndLexicalTokenLimits()
    {
        var lines = Assert.Throws<EclipseDataInspectionException>(() => Inspect(new string('\n', 2_000_001)));
        Assert.Equal("ECLIPSE_DATA_LINE_LIMIT", lines.Code);

        var tokens = Assert.Throws<EclipseDataInspectionException>(() => Inspect(string.Join(' ', Enumerable.Repeat("A", 1_000_001))));
        Assert.Equal("ECLIPSE_DATA_TOKEN_LIMIT", tokens.Code);
    }

    [Fact]
    public async Task ServiceReturnsValidationCompatibleReadyResponseAndHashFailure()
    {
        var path = Path.Combine(root, "models", "CASE.DATA");
        await File.WriteAllTextAsync(path, "RUNSPEC\nDIMENS\n1 2 3 /\n", TestContext.Current.CancellationToken);
        var service = new EclipseDataInspectionService(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));
        await using var stream = File.OpenRead(path);
        var hash = Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, TestContext.Current.CancellationToken));

        var ready = await service.InspectAsync(new("models/CASE.DATA", hash), TestContext.Current.CancellationToken);
        var mismatch = await service.InspectAsync(new("models/CASE.DATA", new string('0', 64)), TestContext.Current.CancellationToken);

        var response = Assert.IsType<ModelValidationResponse>(ready.Body);
        Assert.Equal(200, ready.HttpStatus);
        Assert.Equal("READY", response.Status);
        Assert.Empty(response.Studies);
        Assert.Equal("eclipse_100", response.ModelKind);
        Assert.NotNull(response.Inspection);
        Assert.Equal("CASE.DATA", response.Inspection.CaseName);
        Assert.Equal(422, mismatch.HttpStatus);
        Assert.IsType<WorkerError>(mismatch.Body);
    }

    [Theory]
    [InlineData("models/CASE.TXT")]
    [InlineData("models/CASE")]
    [InlineData("models/CASE.DATA2")]
    public async Task ServiceRejectsNonDataStorageKeyBeforeInspecting(string key)
    {
        var service = new EclipseDataInspectionService(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));

        var result = await service.InspectAsync(new(key, new string('a', 64)), TestContext.Current.CancellationToken);

        Assert.Equal(400, result.HttpStatus);
        var error = Assert.IsType<WorkerError>(result.Body);
        Assert.Equal("REQUEST", error.Category);
        Assert.Equal("INVALID_ECLIPSE_INSPECTION_REQUEST", error.Code);
    }

    [Fact]
    public async Task ServiceAcceptsDataKeyIgnoringExtensionCase()
    {
        var path = Path.Combine(root, "models", "case.data");
        await File.WriteAllTextAsync(path, "RUNSPEC\n", TestContext.Current.CancellationToken);
        var service = new EclipseDataInspectionService(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));
        await using var stream = File.OpenRead(path);
        var hash = Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, TestContext.Current.CancellationToken));

        var result = await service.InspectAsync(new("models/case.data", hash), TestContext.Current.CancellationToken);

        Assert.Equal(200, result.HttpStatus);
        var response = Assert.IsType<ModelValidationResponse>(result.Body);
        Assert.Equal("case.data", response.Inspection!.CaseName);
    }

    [Fact]
    public async Task ServiceRejectsOversizedDataBeforeHashingOrInspection()
    {
        var path = Path.Combine(root, "models", "large.DATA");
        await using (var stream = File.Create(path)) stream.SetLength(64L * 1024 * 1024 + 1);
        var service = new EclipseDataInspectionService(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));

        var result = await service.InspectAsync(new("models/large.DATA", new string('a', 64)), TestContext.Current.CancellationToken);

        var response = Assert.IsType<ModelValidationResponse>(result.Body);
        Assert.Equal(422, result.HttpStatus);
        Assert.Equal("INVALID", response.Status);
        Assert.Null(response.Inspection);
        Assert.Equal("ECLIPSE_DATA_TOO_LARGE", response.Error!.Code);
        Assert.Equal("MODEL", response.Error.Category);
    }

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }

    private EclipseDataInspection Inspect(string deck)
    {
        var path = Path.Combine(root, Guid.NewGuid().ToString("N") + ".DATA");
        File.WriteAllText(path, deck, new UTF8Encoding(encoderShouldEmitUTF8Identifier: true));
        return EclipseDataInspector.Inspect(path, "CASE.DATA");
    }
}
