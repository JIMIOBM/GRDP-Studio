using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed partial class PtkValidationService
{
    private readonly StorageResolver storage;
    private readonly PtkExecutionCoordinator coordinator;
    private readonly PtkProcessRunner runner;
    private readonly WorkerOptions options;

    public PtkValidationService(
        StorageResolver storage,
        PtkExecutionCoordinator coordinator,
        PtkProcessRunner runner,
        IOptions<WorkerOptions> options)
    {
        this.storage = storage;
        this.coordinator = coordinator;
        this.runner = runner;
        this.options = options.Value;
    }

    public async Task<ApiOutcome> ValidateAsync(ModelValidationRequest request, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(request.ModelStorageKey))
            return new(StatusCodes.Status400BadRequest, WorkerApiError.Request("MODEL_STORAGE_KEY_REQUIRED", "modelStorageKey is required."));
        if (string.IsNullOrWhiteSpace(request.ExpectedSha256) || !LowerSha256().IsMatch(request.ExpectedSha256))
            return new(StatusCodes.Status400BadRequest, WorkerApiError.Request("INVALID_EXPECTED_SHA256", "expectedSha256 must be 64 lowercase hexadecimal characters."));

        string sourceModel;
        IReadOnlyList<EclipsePackageFile>? packageFiles = null;
        try
        {
            sourceModel = storage.ResolveExistingModel(request.ModelStorageKey);
            var actualSha = await storage.ComputeSha256Async(sourceModel, cancellationToken);
            if (actualSha != request.ExpectedSha256)
                return new(StatusCodes.Status422UnprocessableEntity, WorkerApiError.Storage("MODEL_SHA256_MISMATCH", "The source model SHA-256 does not match expectedSha256."));
            packageFiles = await PipesimPackageIntegrity.DescribeAsync(
                storage.ResolveModelPackageRoot(sourceModel), cancellationToken);
        }
        catch (StorageException exception)
        {
            return new(exception.HttpStatus, WorkerApiError.Storage(exception.Code, exception.Message));
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            return new(StatusCodes.Status503ServiceUnavailable,
                WorkerApiError.Storage("STORAGE_IO_ERROR", "Worker storage could not be read or written.", true));
        }

        if (!File.Exists(options.EffectivePythonPath) || !File.Exists(options.EffectivePipesimPtkPath))
            return new(StatusCodes.Status503ServiceUnavailable, new WorkerError("ENVIRONMENT", "PTK_UNAVAILABLE", "Python or PIPESIM Python Toolkit is unavailable.", true));

        var acquired = coordinator.TryAcquire("validation");
        if (!acquired.Acquired) return new(StatusCodes.Status409Conflict, acquired.Error!);

        RunDirectories? directories = null;
        var processTreeConfirmed = true;
        try
        {
            directories = storage.CreateValidationDirectories(sourceModel);
            var copySha = await storage.ComputeSha256Async(directories.ModelCopy, cancellationToken);
            if (copySha != request.ExpectedSha256)
                return new(StatusCodes.Status422UnprocessableEntity, WorkerApiError.Storage("MODEL_COPY_SHA256_MISMATCH", "The validation model copy failed SHA-256 verification."));

            var result = await runner.ValidateAsync(
                directories.ModelCopy,
                acquired.Lease!.AcquiredAtUtc.AddSeconds(options.ValidationTimeoutSeconds) - DateTimeOffset.UtcNow,
                cancellationToken);
            processTreeConfirmed = result.ProcessTreeExitConfirmed;
            if (!processTreeConfirmed)
            {
                acquired.Lease!.BlockRelease();
                return new(StatusCodes.Status503ServiceUnavailable,
                    new WorkerError("CLEANUP", "PROCESS_TREE_EXIT_UNCONFIRMED", "Validation process-tree exit is unconfirmed; PIPESIM coordination remains blocked.", false));
            }
            var sourceShaAfter = await storage.ComputeSha256Async(sourceModel, CancellationToken.None);
            if (sourceShaAfter != request.ExpectedSha256)
                return new(StatusCodes.Status422UnprocessableEntity, WorkerApiError.Storage("SOURCE_MODEL_CHANGED", "The source model SHA-256 changed during validation."));
            await PipesimPackageIntegrity.VerifyAsync(
                storage.ResolveModelPackageRoot(sourceModel), packageFiles, CancellationToken.None);
            if (result.Error is not null)
                return new(StatusCodes.Status503ServiceUnavailable, result.Error);
            if (result.Envelope is null)
                return new(StatusCodes.Status503ServiceUnavailable, new WorkerError("PROTOCOL", "INVALID_VALIDATION_PROTOCOL", "The validation adapter returned no result.", false));

            var envelope = result.Envelope.Value;
            var status = envelope.TryGetProperty("status", out var statusElement) ? statusElement.GetString() : null;
            var message = envelope.TryGetProperty("message", out var messageElement) ? messageElement.GetString() : null;
            var studies = envelope.TryGetProperty("studies", out var studiesElement) && studiesElement.ValueKind == JsonValueKind.Array
                ? studiesElement.EnumerateArray().Where(item => item.ValueKind == JsonValueKind.String).Select(item => item.GetString()!).ToArray()
                : [];
            var modelKind = envelope.TryGetProperty("modelKind", out var modelKindElement) ? modelKindElement.GetString() : null;
            var well = envelope.TryGetProperty("well", out var wellElement) ? wellElement.GetString() : null;
            IReadOnlyList<string>? espContexts = null;
            if (envelope.TryGetProperty("espContexts", out var espContextsElement))
            {
                if (espContextsElement.ValueKind != JsonValueKind.Array || espContextsElement.GetArrayLength() > 128)
                    return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_ESP_CONTEXTS", "The validation adapter returned invalid ESP context metadata.", false));
                var contexts = new List<string>();
                foreach (var item in espContextsElement.EnumerateArray())
                {
                    if (item.ValueKind != JsonValueKind.String || string.IsNullOrWhiteSpace(item.GetString()) || item.GetString()!.Length > 255 ||
                        item.GetString()!.Contains("..", StringComparison.Ordinal) || item.GetString()!.IndexOfAny(['/', '\\']) >= 0 ||
                        item.GetString()!.Any(char.IsControl))
                    {
                        return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_ESP_CONTEXTS", "The validation adapter returned invalid ESP context metadata.", false));
                    }
                    contexts.Add(item.GetString()!);
                }
                if (contexts.Count != contexts.Distinct(StringComparer.Ordinal).Count())
                    return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_ESP_CONTEXTS", "The validation adapter returned duplicate ESP contexts.", false));
                espContexts = contexts;
            }
            IReadOnlyList<WellEspContextInspection>? wellContexts = null;
            var hasTubingComponents = false;
            if (envelope.TryGetProperty("wellContexts", out var wellsElement))
            {
                if (wellsElement.ValueKind != JsonValueKind.Array || wellsElement.GetArrayLength() is 0 or > 128 || espContexts is null)
                    return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_WELL_CONTEXTS", "The validation adapter returned invalid well context metadata.", false));
                var contexts = new List<WellEspContextInspection>();
                var seenWells = new HashSet<string>(StringComparer.Ordinal);
                var allEspContexts = new HashSet<string>(StringComparer.Ordinal);
                bool? tubingDetailsExpected = null;
                foreach (var item in wellsElement.EnumerateArray())
                {
                    if (item.ValueKind != JsonValueKind.Object || item.EnumerateObject().Count() is not (4 or 5) ||
                        !item.TryGetProperty("context", out var contextElement) || contextElement.ValueKind != JsonValueKind.String ||
                        !item.TryGetProperty("espContexts", out var contextEspsElement) || contextEspsElement.ValueKind != JsonValueKind.Array ||
                        contextEspsElement.GetArrayLength() > 128 ||
                        !item.TryGetProperty("completionContexts", out var completionsElement) || completionsElement.ValueKind != JsonValueKind.Array || completionsElement.GetArrayLength() > 128 ||
                        !item.TryGetProperty("tubingContexts", out var tubingsElement) || tubingsElement.ValueKind != JsonValueKind.Array || tubingsElement.GetArrayLength() > 128)
                        return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_WELL_CONTEXTS", "The validation adapter returned invalid well context metadata.", false));
                    var context = contextElement.GetString()!;
                    if (!IsSafePipesimContext(context) || !seenWells.Add(context))
                        return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_WELL_CONTEXTS", "The validation adapter returned an unsafe or duplicate well context.", false));
                    var hasTubingDetails = item.TryGetProperty("tubingComponents", out var tubingComponentsElement);
                    if (tubingDetailsExpected.HasValue && tubingDetailsExpected.Value != hasTubingDetails)
                        return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_TUBING_COMPONENTS", "The validation adapter returned inconsistent Tubing parameter metadata across wells.", false));
                    tubingDetailsExpected = hasTubingDetails;
                    var scopedEspContexts = new List<string>();
                    var seenScopedEspContexts = new HashSet<string>(StringComparer.Ordinal);
                    var completionContexts = ReadScopedContexts(completionsElement);
                    var tubingContexts = ReadScopedContexts(tubingsElement);
                    if (completionContexts is null || tubingContexts is null)
                        return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_WELL_CONTEXTS", "The validation adapter returned invalid per-well completion or tubing metadata.", false));
                    IReadOnlyList<WellTubingComponentInspection>? tubingComponents = null;
                    if (hasTubingDetails)
                    {
                        hasTubingComponents = true;
                        if (item.EnumerateObject().Count() != 5 || tubingComponentsElement.ValueKind != JsonValueKind.Array || tubingComponentsElement.GetArrayLength() > 128)
                            return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_TUBING_COMPONENTS", "The validation adapter returned invalid Tubing parameter metadata.", false));
                        var parsedTubingComponents = new List<WellTubingComponentInspection>();
                        var seenTubingComponents = new HashSet<string>(StringComparer.Ordinal);
                        foreach (var tubingComponent in tubingComponentsElement.EnumerateArray())
                        {
                            if (tubingComponent.ValueKind != JsonValueKind.Object || tubingComponent.EnumerateObject().Count() != 3 ||
                                !tubingComponent.TryGetProperty("context", out var tubingContext) || tubingContext.ValueKind != JsonValueKind.String ||
                                !tubingComponent.TryGetProperty("innerDiameter", out var diameterElement) || diameterElement.ValueKind != JsonValueKind.Number ||
                                !diameterElement.TryGetDouble(out var diameter) || !double.IsFinite(diameter) || diameter <= 0 || diameter > 1000 ||
                                !tubingComponent.TryGetProperty("unit", out var unitElement) || unitElement.ValueKind != JsonValueKind.String ||
                                !IsSafePipesimContext(tubingContext.GetString()) || !IsSafePipesimUnit(unitElement.GetString()) || !seenTubingComponents.Add(tubingContext.GetString()!) ||
                                !tubingContexts.Contains(tubingContext.GetString()!, StringComparer.Ordinal))
                                return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_TUBING_COMPONENTS", "The validation adapter returned unsafe or inconsistent Tubing parameter metadata.", false));
                            parsedTubingComponents.Add(new(tubingContext.GetString()!, diameter, unitElement.GetString()!));
                        }
                        tubingComponents = parsedTubingComponents;
                    }
                    foreach (var contextEspElement in contextEspsElement.EnumerateArray())
                    {
                        if (contextEspElement.ValueKind != JsonValueKind.String || !IsSafePipesimContext(contextEspElement.GetString()) ||
                            !seenScopedEspContexts.Add(contextEspElement.GetString()!))
                            return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_WELL_CONTEXTS", "The validation adapter returned invalid ESP-to-well context metadata.", false));
                        scopedEspContexts.Add(contextEspElement.GetString()!);
                        allEspContexts.Add(contextEspElement.GetString()!);
                    }
                    contexts.Add(new(context, scopedEspContexts, completionContexts, tubingContexts, tubingComponents));
                }
                if (!allEspContexts.SetEquals(espContexts))
                    return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_PIPESIM_WELL_CONTEXTS", "The well-scoped ESP list does not match the discovered ESP contexts.", false));
                wellContexts = contexts;
            }
            IReadOnlyList<GasLiftCapabilityInspection>? gasLiftCapabilities = null;
            if (envelope.TryGetProperty("gasLiftCapabilities", out var gasLiftElement))
            {
                gasLiftCapabilities = wellContexts is null || !hasTubingComponents ? null
                    : GasLiftCapabilityReader.Read(gasLiftElement, wellContexts, studies);
                if (gasLiftCapabilities is null)
                    return new(StatusCodes.Status502BadGateway, new WorkerError("PROTOCOL", "INVALID_GAS_LIFT_CAPABILITIES", "The adapter returned inconsistent gas lift discovery metadata.", false));
            }
            WorkerError? structuredError = null;
            if (envelope.TryGetProperty("error", out var errorElement) && errorElement.ValueKind == JsonValueKind.Object)
            {
                try
                {
                    structuredError = JsonSerializer.Deserialize<WorkerError>(errorElement, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                }
                catch (JsonException) { }
            }
            object? inspection = modelKind == "network"
                ? NetworkInspectionReader.Read(envelope, status, modelKind)
                : WellInspectionReader.Read(envelope, status, modelKind);
            var networkCapability = modelKind == "network" ? null
                : NetworkInspectionReader.ReadCapability(envelope, status, modelKind, studies, packageFiles);
            inspection = inspection switch
            {
                WellDataInspection wellInspection => wellInspection with
                {
                    SchemaVersion = gasLiftCapabilities is not null ? "pipesim-well-inspection/9" : hasTubingComponents ? "pipesim-well-inspection/8" : networkCapability is not null ? "pipesim-well-inspection/7" : wellContexts is not null ? "pipesim-well-inspection/6" : espContexts is null ? "pipesim-well-inspection/3" : "pipesim-well-inspection/4",
                    PackageFiles = packageFiles,
                    Well = well,
                    EspContexts = espContexts,
                    WellContexts = wellContexts,
                    NetworkCapability = networkCapability,
                    GasLiftCapabilities = gasLiftCapabilities
                },
                NetworkDataInspection network => network with
                {
                    SchemaVersion = "pipesim-network-inspection/3",
                    PackageFiles = packageFiles
                },
                _ => inspection
            };
            var response = new ModelValidationResponse(status ?? "INVALID", studies, message ?? "Model validation failed.", modelKind, well, structuredError,
                inspection, espContexts);
            return status switch
            {
                "READY" => new(StatusCodes.Status200OK, response),
                "ENVIRONMENT_ERROR" => new(StatusCodes.Status503ServiceUnavailable,
                    response with { Error = structuredError ?? new WorkerError("ENVIRONMENT", "VALIDATION_ENVIRONMENT_ERROR", response.Message, true) }),
                _ => new(StatusCodes.Status422UnprocessableEntity,
                    response with { Error = new WorkerError("MODEL", "MODEL_INVALID", response.Message, false) })
            };
        }
        catch (StorageException exception)
        {
            return new(exception.HttpStatus, WorkerApiError.Storage(exception.Code, exception.Message));
        }
        finally
        {
            if (directories is not null) StorageResolver.TryDeleteDirectory(directories.Root);
            if (processTreeConfirmed) acquired.Lease!.Dispose();
        }
    }

    private static IReadOnlyList<string>? ReadScopedContexts(JsonElement contexts)
    {
        var values = new List<string>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var item in contexts.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.String || !IsSafePipesimContext(item.GetString()) || !seen.Add(item.GetString()!)) return null;
            values.Add(item.GetString()!);
        }
        return values;
    }

    [GeneratedRegex("^[0-9a-f]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex LowerSha256();

    private static bool IsSafePipesimContext(string? value) =>
        !string.IsNullOrWhiteSpace(value) && value.Length <= 255 && !value.Contains("..", StringComparison.Ordinal) &&
        value.IndexOfAny(['/', '\\']) < 0 && !value.Any(char.IsControl);

    private static bool IsSafePipesimUnit(string? value) =>
        !string.IsNullOrWhiteSpace(value) && value.Length <= 32 && !value.Any(char.IsControl);
}
