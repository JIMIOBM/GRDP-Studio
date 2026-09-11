using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Storage;

namespace Grdp.SoftwareIntegration.Worker.Inspection;

public sealed class EclipseDataInspectionService(StorageResolver storage)
{
    private const long MaxDataBytes = 64L * 1024 * 1024;

    public async Task<ApiOutcome> InspectAsync(ModelValidationRequest request, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(request.ModelStorageKey)
            || !string.Equals(Path.GetExtension(request.ModelStorageKey), ".DATA", StringComparison.OrdinalIgnoreCase)
            || !IsSha256(request.ExpectedSha256))
        {
            return new(StatusCodes.Status400BadRequest,
                WorkerApiError.Request("INVALID_ECLIPSE_INSPECTION_REQUEST", "modelStorageKey must reference a .DATA file and a lowercase SHA-256 is required."));
        }

        try
        {
            var source = storage.ResolveExistingData(request.ModelStorageKey);
            if (new FileInfo(source).Length > MaxDataBytes) return Invalid("ECLIPSE_DATA_TOO_LARGE", "The ECLIPSE .DATA file exceeds the 64 MiB inspection limit.");
            if (await storage.ComputeSha256Async(source, cancellationToken) != request.ExpectedSha256)
            {
                return new(StatusCodes.Status422UnprocessableEntity,
                    WorkerApiError.Storage("MODEL_SHA256_MISMATCH", "The source model SHA-256 does not match expectedSha256."));
            }

            var inspection = EclipseDataInspector.Inspect(source, Path.GetFileName(request.ModelStorageKey));
            return new(StatusCodes.Status200OK, new ModelValidationResponse(
                "READY", [], "ECLIPSE .DATA inspection completed.", "eclipse_100",
                Inspection: inspection));
        }
        catch (EclipseDataInspectionException error)
        {
            return Invalid(error.Code, error.Message);
        }
        catch (StorageException error)
        {
            return new(error.HttpStatus, WorkerApiError.Storage(error.Code, error.Message));
        }
    }

    private static ApiOutcome Invalid(string code, string message) => new(StatusCodes.Status422UnprocessableEntity,
        new ModelValidationResponse("INVALID", [], message, Error: new WorkerError("MODEL", code, message, false)));

    private static bool IsSha256(string? value) => value is { Length: 64 } && value.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');
}
