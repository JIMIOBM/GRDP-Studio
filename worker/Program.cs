using System.Text.Json;
using System.Text.Json.Serialization;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.AspNetCore.Http.Json;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);
builder.Configuration.AddEnvironmentVariables(prefix: "GRDP_WORKER_");
var listenUrls = builder.Configuration["Urls"] ?? "http://127.0.0.1:5150";
foreach (var value in listenUrls.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
{
    if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) ||
        !(string.Equals(uri.Host, "localhost", StringComparison.OrdinalIgnoreCase) ||
          (System.Net.IPAddress.TryParse(uri.Host, out var address) && System.Net.IPAddress.IsLoopback(address))))
    {
        throw new InvalidOperationException("The Worker may listen only on a loopback address.");
    }
}
builder.WebHost.UseUrls(listenUrls);
builder.Services.AddOptions<WorkerOptions>()
    .Bind(builder.Configuration.GetSection("Worker"))
    .Validate(options =>
        !string.IsNullOrWhiteSpace(options.WorkerId) &&
        !string.IsNullOrWhiteSpace(options.StorageRoot) && Path.IsPathFullyQualified(options.StorageRoot) &&
        !string.IsNullOrWhiteSpace(options.PipesimHome) && Path.IsPathFullyQualified(options.PipesimHome) &&
        (options.PythonPath is null || Path.IsPathFullyQualified(options.PythonPath)) &&
        (options.PipesimPtkPath is null || Path.IsPathFullyQualified(options.PipesimPtkPath)) &&
        options.MaxRunTimeoutSeconds > 0 &&
        options.ValidationTimeoutSeconds > 0 &&
        options.GracefulStopSeconds > 0 &&
        options.ProcessExitConfirmationSeconds > 0,
        "Worker identity, storage, PIPESIM, and timeout configuration must be valid.")
    .ValidateOnStart();
builder.Services.Configure<JsonOptions>(options =>
{
    options.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase;
    options.SerializerOptions.DictionaryKeyPolicy = JsonNamingPolicy.CamelCase;
    options.SerializerOptions.DefaultIgnoreCondition = JsonIgnoreCondition.Never;
    options.SerializerOptions.UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow;
});
builder.Services.AddSingleton<WorkerIdentity>();
builder.Services.AddSingleton<StorageResolver>();
builder.Services.AddSingleton<PtkExecutionCoordinator>();
builder.Services.AddSingleton<PtkRunRegistry>();
builder.Services.AddSingleton<PtkProcessRunner>();
builder.Services.AddSingleton<PtkRunService>();
builder.Services.AddSingleton<PtkValidationService>();

var app = builder.Build();

app.MapGet("/api/health", (WorkerIdentity identity, PtkRunRegistry registry, PtkExecutionCoordinator coordinator) =>
{
    var activeRunId = registry.ActiveRunId;
    return Results.Ok(new
    {
        status = "UP",
        worker = "grdp-software-integration",
        version = "0.2.0",
        timestamp = DateTimeOffset.UtcNow,
        workerId = identity.WorkerId,
        generationId = identity.GenerationId,
        activeRunId,
        idle = activeRunId is null && !coordinator.IsBusy
    });
});

app.MapGet("/api/capabilities", (WorkerIdentity identity, PtkRunRegistry registry, PtkExecutionCoordinator coordinator, IOptions<WorkerOptions> configuredOptions) =>
{
    var options = configuredOptions.Value;
    var installationFound = Directory.Exists(options.PipesimHome);
    var pythonToolkitFound = File.Exists(options.EffectivePipesimPtkPath);
    var pythonFound = File.Exists(options.EffectivePythonPath);
    var activeRunId = registry.ActiveRunId;
    return Results.Ok(new
    {
        workerId = identity.WorkerId,
        generationId = identity.GenerationId,
        activeRunId,
        idle = activeRunId is null && !coordinator.IsBusy,
        pipesimWell = new
        {
            version = "2022.1",
            installationFound,
            pythonToolkitFound,
            pythonFound,
            status = installationFound && pythonToolkitFound && pythonFound ? "AVAILABLE" : "UNAVAILABLE",
            runTasks = new[] { "nodal", "profile", "combined" },
            maxTimeoutSeconds = options.MaxRunTimeoutSeconds
        }
    });
});

app.MapPost("/api/models/validate", async (
    ModelValidationRequest request,
    PtkValidationService service,
    CancellationToken cancellationToken) =>
{
    var outcome = await service.ValidateAsync(request, cancellationToken);
    return outcome.HttpStatus switch
    {
        StatusCodes.Status200OK => Results.Ok(outcome.Body),
        StatusCodes.Status404NotFound => Results.NotFound(outcome.Body),
        StatusCodes.Status409Conflict => Results.Conflict(outcome.Body),
        StatusCodes.Status422UnprocessableEntity => Results.UnprocessableEntity(outcome.Body),
        StatusCodes.Status503ServiceUnavailable => Results.Json(outcome.Body, statusCode: outcome.HttpStatus),
        _ => Results.BadRequest(outcome.Body)
    };
});

app.MapPost("/api/runs/execute", async (
    RunExecuteRequest request,
    PtkRunService service,
    CancellationToken cancellationToken) =>
{
    var outcome = await service.SubmitAsync(request, cancellationToken);
    return outcome.HttpStatus switch
    {
        StatusCodes.Status202Accepted => Results.Json(outcome.Body, statusCode: StatusCodes.Status202Accepted),
        StatusCodes.Status404NotFound => Results.NotFound(outcome.Body),
        StatusCodes.Status409Conflict => Results.Conflict(outcome.Body),
        StatusCodes.Status422UnprocessableEntity => Results.UnprocessableEntity(outcome.Body),
        StatusCodes.Status503ServiceUnavailable => Results.Json(outcome.Body, statusCode: outcome.HttpStatus),
        _ => Results.BadRequest(outcome.Body)
    };
});

app.MapGet("/api/runs/{runId:long}", (long runId, long? afterSequence, PtkRunRegistry registry) =>
{
    if (runId <= 0 || afterSequence is < 0)
    {
        return Results.BadRequest(WorkerApiError.Request("INVALID_RUN_QUERY", "runId must be positive and afterSequence must be non-negative."));
    }

    var snapshot = registry.GetSnapshot(runId, afterSequence ?? 0);
    return snapshot is null ? Results.NotFound(WorkerApiError.Request("RUN_NOT_FOUND", "Run was not found in this Worker generation.")) : Results.Ok(snapshot);
});

app.MapPost("/api/runs/{runId:long}/cancel", (long runId, PtkRunRegistry registry) =>
{
    if (runId <= 0)
    {
        return Results.BadRequest(WorkerApiError.Request("INVALID_RUN_ID", "runId must be positive."));
    }

    var outcome = registry.RequestCancel(runId);
    return outcome.Status switch
    {
        CancelRequestStatus.Accepted => Results.Json(outcome.Body, statusCode: StatusCodes.Status202Accepted),
        CancelRequestStatus.AlreadyCancelled => Results.Ok(outcome.Body),
        CancelRequestStatus.TerminalConflict => Results.Conflict(outcome.Body),
        _ => Results.NotFound(outcome.Body)
    };
});

app.Run();

public partial class Program;
