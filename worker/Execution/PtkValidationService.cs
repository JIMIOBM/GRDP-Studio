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
        try
        {
            sourceModel = storage.ResolveExistingModel(request.ModelStorageKey);
            var actualSha = await storage.ComputeSha256Async(sourceModel, cancellationToken);
            if (actualSha != request.ExpectedSha256)
                return new(StatusCodes.Status422UnprocessableEntity, WorkerApiError.Storage("MODEL_SHA256_MISMATCH", "The source model SHA-256 does not match expectedSha256."));
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
            WorkerError? structuredError = null;
            if (envelope.TryGetProperty("error", out var errorElement) && errorElement.ValueKind == JsonValueKind.Object)
            {
                try
                {
                    structuredError = JsonSerializer.Deserialize<WorkerError>(errorElement, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                }
                catch (JsonException) { }
            }
            var response = new ModelValidationResponse(status ?? "INVALID", studies, message ?? "Model validation failed.", modelKind, well, structuredError);
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

    [GeneratedRegex("^[0-9a-f]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex LowerSha256();
}
