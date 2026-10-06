using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class TemplateCreationTests
{
    private static JsonElement Inputs() => JsonSerializer.SerializeToElement(new
    {
        schemaVersion = "pipesim-template-profile-inputs/1", unitsSystem = "PIPESIM_FIELD", study = "Study 1",
        oilApi = 35, gasSpecificGravity = .65, waterSpecificGravity = 1.05, gorScfStb = 200,
        waterCutPercent = 20, reservoirPressurePsia = 4000, reservoirTemperatureDegF = 150,
        outletPressurePsia = 250, liquidRateStbDay = 1000
    });

    private static JsonElement Result(JsonElement inputs) => JsonSerializer.SerializeToElement(new
    {
        schemaVersion = "pipesim-template-profile-preflight/1", template = "Simple vertical", well = "ConsoleWell",
        nativeState = "Completed", geometryOrigin = "official-template-inherited",
        fluidOrigin = "explicit-scalar-inputs-with-installed-SDK-default-correlations",
        calculationVerified = true, platformVerified = false, modelDiagnostics = Array.Empty<string>(), inputs,
        readback = new
        {
            fluid = new { API = 35, GasSpecificGravity = .65, WaterSpecificGravity = 1.05, GOR = 200, WaterCut = 20 },
            completion = new { ReservoirPressure = 4000, ReservoirTemperature = 150 }
        },
        conditionsReadback = new { Producer = "ConsoleWell", CalculationVariableType = "calcInletPressure", FlowRateType = "LiquidFlowRate", OutletPressure = 250, LiquidFlowRate = 1000 },
        profile = new[] { new { depth = 0, pressure = 249.297, temperature = 92.08 } }
    });

    [Theory]
    [InlineData("oilApi", "true")]
    [InlineData("liquidRateStbDay", "0")]
    [InlineData("waterCutPercent", "101")]
    [InlineData("unitsSystem", "\"PIPESIM_SI\"")]
    [InlineData("extra", "1")]
    public void InvalidTypesBoundsAndUnknownFieldsAreRejected(string field, string json)
    {
        var input = JsonNode.Parse(Inputs().GetRawText())!;
        input[field] = JsonNode.Parse(json);
        Assert.False(TemplateCreationParameters.IsValid(new(Guid.NewGuid(), "ConsoleWell", JsonSerializer.SerializeToElement(input))));
    }

    [Fact]
    public void MissingFieldUnsafeNameAndEmptyIdAreRejected()
    {
        var request = new TemplateCreationRequest(Guid.NewGuid(), "ConsoleWell", Inputs());
        Assert.False(TemplateCreationParameters.IsValid(request with { RequestId = Guid.Empty }));
        Assert.False(TemplateCreationParameters.IsValid(request with { Well = "../Well" }));
        Assert.False(TemplateCreationParameters.IsValid(request with { Well = "Well\n" }));
        var input = JsonNode.Parse(Inputs().GetRawText())!.AsObject();
        input.Remove("gorScfStb");
        Assert.False(TemplateCreationParameters.IsValid(request with { Inputs = JsonSerializer.SerializeToElement(input) }));
    }

    [Fact]
    public void FingerprintIgnoresFieldOrderButNotInputChanges()
    {
        var request = new TemplateCreationRequest(Guid.NewGuid(), "ConsoleWell", Inputs());
        var reversed = request.Inputs.EnumerateObject().Reverse().ToDictionary(item => item.Name, item => item.Value);
        Assert.Equal(TemplateCreationParameters.Fingerprint(request), TemplateCreationParameters.Fingerprint(request with { Inputs = JsonSerializer.SerializeToElement(reversed) }));
    }

    [Theory]
    [InlineData("well", "\"OtherWell\"")]
    [InlineData("nativeState", "\"Failed\"")]
    [InlineData("calculationVerified", "false")]
    [InlineData("platformVerified", "true")]
    [InlineData("profile", "[]")]
    [InlineData("readback", "{}")]
    public void InvalidNativeResultCannotPublish(string field, string json)
    {
        var result = JsonNode.Parse(Result(Inputs()).GetRawText())!;
        result[field] = JsonNode.Parse(json);
        Assert.False(TemplateCreationResultValidator.IsValid(JsonSerializer.SerializeToElement(result), new(Guid.NewGuid(), "ConsoleWell", Inputs())));
    }

    [Fact]
    public async Task SuccessPersistsAndIdenticalRetryNeverReruns()
    {
        using var setup = new Fixture();
        var request = setup.Request;
        var success = await setup.Service.CreateAsync(request, TestContext.Current.CancellationToken);
        Assert.Equal(201, success.HttpStatus);
        var record = Assert.IsType<TemplateCreationRecord>(success.Body);
        Assert.Equal("SUCCEEDED", record.Status);
        Assert.NotNull(record.Model);
        Assert.Equal(64, record.Model.Sha256.Length);
        Assert.Equal(200, (await setup.Service.CreateAsync(request, TestContext.Current.CancellationToken)).HttpStatus);
        Assert.Equal(1, setup.Adapter.Calls);
        // A fresh store instance simulates reading after a Worker restart.
        Assert.Equal("SUCCEEDED", new TemplateCreationStore(setup.Storage).Read(request.RequestId)!.Status);
        Assert.False(setup.Coordinator.IsBusy);
    }

    [Fact]
    public async Task ConflictAndBusyNeverInvokeAdapter()
    {
        using var setup = new Fixture();
        using (var busy = setup.Coordinator.TryAcquire("existing-run").Lease!)
            Assert.Equal(409, (await setup.Service.CreateAsync(setup.Request, TestContext.Current.CancellationToken)).HttpStatus);
        Assert.Equal(0, setup.Adapter.Calls);
        await setup.Service.CreateAsync(setup.Request, TestContext.Current.CancellationToken);
        Assert.Equal(409, (await setup.Service.CreateAsync(setup.Request with { Well = "Other" }, TestContext.Current.CancellationToken)).HttpStatus);
        Assert.Equal(1, setup.Adapter.Calls);
    }

    [Fact]
    public async Task InvalidReadbackRetainsFailureButNeverDownloadableModel()
    {
        using var setup = new Fixture();
        setup.Adapter.Invalid = true;
        Assert.Equal(422, (await setup.Service.CreateAsync(setup.Request, TestContext.Current.CancellationToken)).HttpStatus);
        var record = setup.Store.Read(setup.Request.RequestId)!;
        Assert.Equal("FAILED", record.Status);
        Assert.Null(record.Model);
        Assert.False(setup.Coordinator.IsBusy);
    }

    [Fact]
    public async Task ExitUnconfirmedRetainsEngineLockAndPublishesNoModel()
    {
        using var setup = new Fixture();
        setup.Adapter.Confirmed = false;
        Assert.Equal(503, (await setup.Service.CreateAsync(setup.Request, TestContext.Current.CancellationToken)).HttpStatus);
        Assert.True(setup.Coordinator.IsBusy);
        Assert.Null(setup.Store.Read(setup.Request.RequestId)!.Model);
    }

    [Fact]
    public async Task InterruptedCreationAfterRestartNeverResumesAutomatically()
    {
        using var setup = new Fixture();
        setup.Store.Claim(setup.Request, TemplateCreationParameters.Fingerprint(setup.Request));
        var restarted = new TemplateCreationStore(setup.Storage);
        Assert.Equal("INTERRUPTED", restarted.Read(setup.Request.RequestId)!.Status);
        var service = new TemplateCreationService(restarted, setup.Coordinator, setup.Adapter, setup.Options);
        Assert.Equal(200, (await service.CreateAsync(setup.Request, TestContext.Current.CancellationToken)).HttpStatus);
        Assert.Equal(0, setup.Adapter.Calls);
        Assert.Null(restarted.Read(setup.Request.RequestId)!.Model);
    }

    [Fact]
    public async Task TimeoutAndCancellationPersistWithoutPublishingModel()
    {
        foreach (var reason in new[] { AdapterStopReason.TimedOut, AdapterStopReason.Cancelled })
        {
            using var setup = new Fixture();
            setup.Adapter.StopReason = reason;
            Assert.Equal(422, (await setup.Service.CreateAsync(setup.Request, TestContext.Current.CancellationToken)).HttpStatus);
            var record = setup.Store.Read(setup.Request.RequestId)!;
            Assert.Equal(reason == AdapterStopReason.TimedOut ? "TIMED_OUT" : "CANCELLED", record.Status);
            Assert.Null(record.Model);
            Assert.False(setup.Coordinator.IsBusy);
        }
    }

    [Fact]
    public async Task ActiveCancellationStopsAdapterAndCannotRewriteTerminalRecord()
    {
        using var setup = new Fixture();
        setup.Adapter.BlockUntilCancelled = true;
        var pending = setup.Service.CreateAsync(setup.Request, TestContext.Current.CancellationToken);
        await setup.Adapter.Started.Task.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        Assert.Equal(404, setup.Service.Cancel(Guid.NewGuid()).HttpStatus);
        Assert.Equal(400, setup.Service.Cancel(Guid.Empty).HttpStatus);
        Assert.Equal(202, setup.Service.Cancel(setup.Request.RequestId).HttpStatus);
        var outcome = await pending.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        Assert.Equal(422, outcome.HttpStatus);
        var record = setup.Store.Read(setup.Request.RequestId)!;
        Assert.Equal("CANCELLED", record.Status);
        Assert.Null(record.Model);
        Assert.False(setup.Coordinator.IsBusy);
        Assert.Equal(200, setup.Service.Cancel(setup.Request.RequestId).HttpStatus);
        Assert.Equal(record, setup.Store.Read(setup.Request.RequestId));
        Assert.Equal(200, (await setup.Service.CreateAsync(setup.Request, TestContext.Current.CancellationToken)).HttpStatus);
        Assert.Equal(1, setup.Adapter.Calls);
    }

    [Fact]
    public async Task CancelBeforePublicationCannotExposeCompletedModel()
    {
        using var setup = new Fixture();
        setup.Adapter.BeforeReturn = () => Assert.Equal(202, setup.Service.Cancel(setup.Request.RequestId).HttpStatus);
        Assert.Equal(422, (await setup.Service.CreateAsync(setup.Request, TestContext.Current.CancellationToken)).HttpStatus);
        Assert.Equal("CANCELLED", setup.Store.Read(setup.Request.RequestId)!.Status);
        Assert.Null(setup.Store.Read(setup.Request.RequestId)!.Model);
        Assert.False(setup.Coordinator.IsBusy);
    }

    [Fact]
    public async Task CancellationWithUnconfirmedExitRetainsEngineLock()
    {
        using var setup = new Fixture();
        setup.Adapter.BlockUntilCancelled = true; setup.Adapter.Confirmed = false;
        var pending = setup.Service.CreateAsync(setup.Request, TestContext.Current.CancellationToken);
        await setup.Adapter.Started.Task.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        Assert.Equal(202, setup.Service.Cancel(setup.Request.RequestId).HttpStatus);
        Assert.Equal(503, (await pending.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken)).HttpStatus);
        Assert.True(setup.Coordinator.IsBusy);
        Assert.Null(setup.Store.Read(setup.Request.RequestId)!.Model);
    }

    [Fact]
    public async Task CompletedCreationCannotBeCancelledAndUnownedPreparationIsRejected()
    {
        using var setup = new Fixture();
        await setup.Service.CreateAsync(setup.Request, TestContext.Current.CancellationToken);
        var original = setup.Store.Read(setup.Request.RequestId)!;
        Assert.Equal(200, setup.Service.Cancel(setup.Request.RequestId).HttpStatus);
        var unchanged = setup.Store.Read(setup.Request.RequestId)!;
        Assert.Equal(original.Status, unchanged.Status);
        Assert.Equal(original.UpdatedAtUtc, unchanged.UpdatedAtUtc);
        Assert.Equal(original.Model, unchanged.Model);
        Assert.Equal(original.Result!.Value.GetRawText(), unchanged.Result!.Value.GetRawText());
        var waiting = setup.Request with { RequestId = Guid.NewGuid() };
        setup.Store.Claim(waiting, TemplateCreationParameters.Fingerprint(waiting));
        Assert.Equal(409, setup.Service.Cancel(waiting.RequestId).HttpStatus);
        Assert.Equal("PREPARING", setup.Store.Read(waiting.RequestId)!.Status);
    }

    [Fact]
    public async Task RequestAbortSharesTheSameCancellationAndCleanupPath()
    {
        using var setup = new Fixture();
        using var abort = new CancellationTokenSource();
        setup.Adapter.BlockUntilCancelled = true;
        var pending = setup.Service.CreateAsync(setup.Request, abort.Token);
        await setup.Adapter.Started.Task.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        abort.Cancel();
        await pending.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        Assert.Equal("CANCELLED", setup.Store.Read(setup.Request.RequestId)!.Status);
        Assert.False(setup.Coordinator.IsBusy);
    }

    private sealed class Fixture : IDisposable
    {
        private readonly string directory = Path.Combine(Path.GetTempPath(), "grdp-template-test-" + Guid.NewGuid().ToString("N"));
        public PtkExecutionCoordinator Coordinator { get; } = new("grdp-template-test-" + Guid.NewGuid().ToString("N"));
        public FakeAdapter Adapter { get; } = new();
        public TemplateCreationRequest Request { get; } = new(Guid.NewGuid(), "ConsoleWell", Inputs());
        public StorageResolver Storage { get; }
        public TemplateCreationStore Store { get; }
        public TemplateCreationService Service { get; }
        public IOptions<WorkerOptions> Options { get; }
        public Fixture()
        {
            Directory.CreateDirectory(directory);
            var python = Path.Combine(directory, "python.exe");
            var toolkit = Path.Combine(directory, "sdk.zip");
            File.WriteAllText(python, "test stub");
            File.WriteAllText(toolkit, "test stub");
            var options = Microsoft.Extensions.Options.Options.Create(new WorkerOptions { StorageRoot = directory, PythonPath = python, PipesimPtkPath = toolkit });
            Options = options;
            Storage = new(options);
            Store = new(Storage);
            Service = new(Store, Coordinator, Adapter, options);
        }
        public void Dispose() { Coordinator.Dispose(); Directory.Delete(directory, recursive: true); }
    }

    private sealed class FakeAdapter : ITemplateCreationAdapter
    {
        public int Calls { get; private set; }
        public bool Invalid { get; set; }
        public bool Confirmed { get; set; } = true;
        public AdapterStopReason StopReason { get; set; }
        public bool BlockUntilCancelled { get; set; }
        public Action? BeforeReturn { get; set; }
        public TaskCompletionSource Started { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public async Task<AdapterExecutionResult> ExecuteAsync(string path, TimeSpan timeout, CancellationToken token)
        {
            Calls++;
            if (BlockUntilCancelled)
            {
                Started.TrySetResult();
                try { await Task.Delay(Timeout.InfiniteTimeSpan, token); }
                catch (OperationCanceledException)
                {
                    return new(AdapterStopReason.Cancelled, 0, null, Confirmed, false, false,
                        new("CANCELLATION", "CREATION_CANCELLED", "Test cancellation fixture", false));
                }
            }
            using var request = JsonDocument.Parse(await File.ReadAllTextAsync(path, token));
            await File.WriteAllTextAsync(request.RootElement.GetProperty("output").GetString()!, "test pips bytes", token);
            var result = Invalid ? JsonSerializer.SerializeToElement(new { calculationVerified = true }) : Result(request.RootElement.GetProperty("inputs"));
            BeforeReturn?.Invoke();
            return new(StopReason, 0, result, Confirmed, false, false,
                StopReason == AdapterStopReason.None ? null : new("EXECUTION", "TEST_STOP", "Test stop fixture", false));
        }
    }
}
