using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
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

        Assert.Equal("eclipse-data-inspection/2", inspection.SchemaVersion);
        Assert.Equal("CASE.DATA", inspection.CaseName);
        Assert.Equal(["RUNSPEC", "GRID", "SCHEDULE"], inspection.Sections);
        Assert.Equal("METRIC", inspection.UnitSystem);
        Assert.Equal(["OIL", "WATER", "GAS"], inspection.Phases);
        Assert.Equal(new EclipseDimensions(10, 20, 30), inspection.Dimensions);
    }

    [Fact]
    public void InspectorExtractsOnlyCompletedScheduleBlocksAfterSchedule()
    {
        var inspection = Inspect("WELSPECS\n'BEFORE' /\n/\nSCHEDULE -- only this starts schedule inspection\nWELSPECS\n'Well'' One' 1 /\nWELL_2 2 /\n'Well'' One' 3 /\n/\nDATES\n31 feb 2024 /\n1 JAN 2025 /\n/\nTSTEP\n1 0.5 1E2 2*3 -1 NaN Infinity /\n2 1e9999 -0.1 /\n/\nDATES\n1 JAN 2025\n");

        Assert.Equal(["Well' One", "WELL_2"], inspection.WellNames);
        Assert.Equal(2, inspection.ScheduleTimeline!.Count);
        Assert.Equal([new EclipseScheduleDate("31", "FEB", "2024"), new EclipseScheduleDate("1", "JAN", "2025")], inspection.ScheduleTimeline[0].Records);
        Assert.Equal(["1", "0.5", "1E2", "2"], inspection.ScheduleTimeline[1].Steps);
    }

    [Fact]
    public void InspectorOmitsEntireUnterminatedMultiRecordScheduleBlock()
    {
        var inspection = Inspect("SCHEDULE\nWELSPECS\nWELL_A /\nWELL_B /\nDATES\n1 JAN 2025 /\n2 FEB 2025 /\n");

        Assert.Empty(inspection.WellNames!);
        Assert.Empty(inspection.ScheduleTimeline!);
    }

    [Fact]
    public void InspectorTerminatesScheduleBlocksOnlyOnStandaloneSlashLines()
    {
        var unterminated = Inspect("SCHEDULE\nWELSPECS\nWELL_A /\n/ trailing-token\n");
        var completed = Inspect("SCHEDULE\nWELSPECS\nWELL_A / WELL_B /\n/ -- terminator comment\n");

        Assert.Empty(unterminated.WellNames!);
        Assert.Equal(["WELL_A", "WELL_B"], completed.WellNames);
    }

    [Fact]
    public void InspectorOmitsClosedQuotedEmptyWellNames()
    {
        var inspection = Inspect("SCHEDULE\nWELSPECS\n'' /\nSAFE_WELL /\n/\n");

        Assert.Equal(["SAFE_WELL"], inspection.WellNames);
    }

    [Theory]
    [InlineData("'C:\\private\\WELL'")]
    [InlineData("'nested/WELL'")]
    [InlineData("'WELL:2'")]
    [InlineData("'WELL..2'")]
    [InlineData("'net.pipe://localhost/pipe/private'")]
    [InlineData("'WELL_SECRET'")]
    [InlineData("'LICENSE_SERVER'")]
    [InlineData("'ENVIRONMENT_NAME'")]
    public void InspectorOmitsUnsafeQuotedWellNames(string unsafeName)
    {
        var inspection = Inspect($"SCHEDULE\nWELSPECS\n{unsafeName} /\nSAFE_WELL /\n/\n");

        Assert.Equal(["SAFE_WELL"], inspection.WellNames);
    }

    [Theory]
    [InlineData("SCHEDULE extra\nWELSPECS\nWELL_A /\n/\n")]
    [InlineData("SCHEDULE\nWELSPECS extra\nWELL_A /\n/\n")]
    [InlineData("SCHEDULE\n'WELSPECS'\nWELL_A /\n/\n")]
    [InlineData("SCHEDULE\nWELSPECS /\n")]
    public void InspectorRequiresSoleUnquotedLineTokenForScheduleCandidates(string deck)
    {
        var inspection = Inspect(deck);

        Assert.Empty(inspection.WellNames!);
        Assert.Empty(inspection.ScheduleTimeline!);
    }

    [Fact]
    public void InspectorOmitsMalformedScheduleRecordsAndKeepsLexicalDateValues()
    {
        var inspection = Inspect("SCHEDULE\nWELSPECS\n1INVALID /\nGOOD-NAME /\n'bad\u0001name' /\n/\nDATES\n01 JAN 2024 /\n1 JAN 2024 24:00 /\n31 FEB 0000 /\n/\nTSTEP\n01 .5 1. 1e9999 -1 2 /\n/\n");

        Assert.Equal(["GOOD-NAME"], inspection.WellNames);
        Assert.Equal(2, inspection.ScheduleTimeline!.Count);
        Assert.Equal(new EclipseScheduleDate("31", "FEB", "0000"), Assert.Single(inspection.ScheduleTimeline[0].Records!));
        Assert.Equal(["01", "2"], inspection.ScheduleTimeline[1].Steps);
    }

    [Fact]
    public void ScheduleTimelineSerializationUsesOnlyTheAllowedFieldsForEachEventKind()
    {
        var inspection = Inspect("SCHEDULE\nDATES\n1 JAN 2025 /\n/\nTSTEP\n1 0.5 /\n/\n");
        var options = new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

        using var document = JsonDocument.Parse(JsonSerializer.Serialize(inspection, options));
        var timeline = document.RootElement.GetProperty("scheduleTimeline");
        var dates = timeline[0];
        var tstep = timeline[1];

        Assert.Equal(["kind", "records"], dates.EnumerateObject().Select(property => property.Name));
        Assert.Equal("DATES", dates.GetProperty("kind").GetString());
        Assert.True(dates.TryGetProperty("records", out var records));
        Assert.Equal(JsonValueKind.Array, records.ValueKind);
        Assert.False(dates.TryGetProperty("steps", out _));
        Assert.Equal(["kind", "steps"], tstep.EnumerateObject().Select(property => property.Name));
        Assert.Equal("TSTEP", tstep.GetProperty("kind").GetString());
        Assert.True(tstep.TryGetProperty("steps", out var steps));
        Assert.Equal(JsonValueKind.Array, steps.ValueKind);
        Assert.False(tstep.TryGetProperty("records", out _));

        using var emptyDocument = JsonDocument.Parse(JsonSerializer.Serialize(new[]
        {
            new EclipseScheduleEvent("DATES", Array.Empty<EclipseScheduleDate>()),
            new EclipseScheduleEvent("TSTEP", Steps: Array.Empty<string>())
        }, options));
        Assert.Equal(JsonValueKind.Array, emptyDocument.RootElement[0].GetProperty("records").ValueKind);
        Assert.Equal(0, emptyDocument.RootElement[0].GetProperty("records").GetArrayLength());
        Assert.Equal(JsonValueKind.Array, emptyDocument.RootElement[1].GetProperty("steps").ValueKind);
        Assert.Equal(0, emptyDocument.RootElement[1].GetProperty("steps").GetArrayLength());
    }

    [Fact]
    public async Task ServiceReturnsScheduleLimitAsModel422WithoutInspection()
    {
        var path = Path.Combine(root, "models", "limit.DATA");
        var deck = new StringBuilder("SCHEDULE\nWELSPECS\n");
        for (var index = 0; index <= 1_000; index++) deck.Append("WELL_").Append(index).Append(" /\n");
        deck.Append("/\n");
        await File.WriteAllTextAsync(path, deck.ToString(), TestContext.Current.CancellationToken);
        await using var stream = File.OpenRead(path);
        var hash = Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, TestContext.Current.CancellationToken));
        var service = new EclipseDataInspectionService(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));

        var result = await service.InspectAsync(new("models/limit.DATA", hash), TestContext.Current.CancellationToken);

        var response = Assert.IsType<ModelValidationResponse>(result.Body);
        Assert.Equal(422, result.HttpStatus);
        Assert.Equal("ECLIPSE_DATA_SCHEDULE_LIMIT", response.Error!.Code);
        Assert.Null(response.Inspection);
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
