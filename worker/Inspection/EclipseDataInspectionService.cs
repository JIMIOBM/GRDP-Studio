using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;

namespace Grdp.SoftwareIntegration.Worker.Inspection;

public sealed class EclipseDataInspectionService(StorageResolver storage)
{
    private const long MaxDataBytes = 64L * 1024 * 1024;
    private readonly EclipseDeckPackageResolver packages = new();

    public EclipseDataInspectionService(StorageResolver storage, EclipseDeckPackageResolver packages) : this(storage) => this.packages = packages;

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

            var package = packages.Resolve(source, storage.ResolveModelPackageRoot(source));
            var packageBytes = package.Files.Sum(path => new FileInfo(path).Length);
            if (packageBytes > MaxDataBytes) return Invalid("ECLIPSE_DATA_PACKAGE_TOO_LARGE", "The ECLIPSE DATA package exceeds the 64 MiB inspection limit.");
            var inspection = EclipseDataInspector.InspectPackage(package.Files, Path.GetFileName(request.ModelStorageKey));
            var packageFiles = await DescribePackageAsync(package, cancellationToken);
            inspection = inspection with
            {
                SchemaVersion = inspection.ScheduleMetadata is null ? "eclipse-data-inspection/3" : "eclipse-data-inspection/4",
                PackageFiles = packageFiles
            };
            return new(StatusCodes.Status200OK, new ModelValidationResponse(
                "READY", [], "ECLIPSE .DATA inspection completed.", "eclipse_100",
                Inspection: inspection));
        }
        catch (EclipseDataInspectionException error)
        {
            return Invalid(error.Code, error.Message);
        }
        catch (EclipseDeckPackageException error)
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

    private static async Task<IReadOnlyList<EclipsePackageFile>> DescribePackageAsync(
        EclipseDeckPackage package, CancellationToken cancellationToken)
    {
        var files = new List<EclipsePackageFile>();
        foreach (var path in Directory.EnumerateFiles(package.Root, "*", SearchOption.AllDirectories)
                     .OrderBy(item => Path.GetRelativePath(package.Root, item), StringComparer.OrdinalIgnoreCase))
        {
            cancellationToken.ThrowIfCancellationRequested();
            var info = new FileInfo(path);
            try
            {
                await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read,
                    64 * 1024, FileOptions.SequentialScan | FileOptions.Asynchronous);
                var hash = await SHA256.HashDataAsync(stream, cancellationToken);
                var relative = Path.GetRelativePath(package.Root, path).Replace(Path.DirectorySeparatorChar, '/');
                files.Add(new EclipsePackageFile(relative, info.Length, Convert.ToHexStringLower(hash)));
            }
            catch (IOException)
            {
                throw new EclipseDeckPackageException("ECLIPSE_INCLUDE_READ_FAILED", "An ECLIPSE package file could not be hashed.");
            }
        }
        return files;
    }

    private static bool IsSha256(string? value) => value is { Length: 64 } && value.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');
}
