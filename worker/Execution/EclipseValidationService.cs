using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed class EclipseValidationService
{
    private readonly StorageResolver storage; private readonly EclipseLauncher launcher; private readonly EclipseDeckPackageResolver packages; private readonly WorkerOptions options;
    public EclipseValidationService(StorageResolver storage, EclipseLauncher launcher, EclipseDeckPackageResolver packages, IOptions<WorkerOptions> options) => (this.storage, this.launcher, this.packages, this.options) = (storage, launcher, packages, options.Value);
    public async Task<ApiOutcome> ValidateAsync(ModelValidationRequest request, CancellationToken cancellationToken)
    {
        if (!EclipseRunRules.IsDataStorageKey(request.ModelStorageKey) || string.IsNullOrWhiteSpace(request.ExpectedSha256) || request.ExpectedSha256.Length != 64 || request.ExpectedSha256.Any(value => value is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            return new(400, WorkerApiError.Request("INVALID_ECLIPSE_REQUEST", "modelStorageKey must reference a .DATA file and a lowercase SHA-256 is required."));
        try
        {
            var source = storage.ResolveExistingData(request.ModelStorageKey);
            if (await storage.ComputeSha256Async(source, cancellationToken) != request.ExpectedSha256) return new(422, WorkerApiError.Storage("MODEL_SHA256_MISMATCH", "The source model SHA-256 does not match expectedSha256."));
            var directories = storage.CreateValidationDirectories(source);
            try
            {
                if (await storage.ComputeSha256Async(directories.ModelCopy, cancellationToken) != request.ExpectedSha256) return new(422, WorkerApiError.Storage("MODEL_COPY_SHA256_MISMATCH", "The validation model copy failed SHA-256 verification."));
                packages.Resolve(directories.ModelCopy, directories.Input);
                var capability = await launcher.GetCapabilityAsync(cancellationToken);
                return capability.Available ? new(200, new ModelValidationResponse("READY", [], "ECLIPSE 100 deck validated.", "eclipse_100")) : new(503, new ModelValidationResponse("ENVIRONMENT_ERROR", [], "ECLIPSE launcher is unavailable.", Error: new WorkerError("ENVIRONMENT", "ECLIPSE_UNAVAILABLE", "ECLIPSE launcher is unavailable.", true)));
            }
            finally { StorageResolver.TryDeleteDirectory(directories.Root); }
        }
        catch (EclipseDeckPackageException error) { return Invalid(error.Code, error.Message); }
        catch (StorageException error) { return new(error.HttpStatus, WorkerApiError.Storage(error.Code, error.Message)); }
    }

    private static ApiOutcome Invalid(string code, string message) => new(StatusCodes.Status422UnprocessableEntity,
        new ModelValidationResponse("INVALID", [], message, Error: new WorkerError("MODEL", code, message, false)));
}
