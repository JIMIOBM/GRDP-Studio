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
            var portable = EclipseCompositionalSaveState.ReadPortable(package);
            if (portable is not null)
            {
                var portableInspection = EclipseCompositionalPortableInspection.Create(package, portable) with {
                    PackageFiles = await DescribePackageAsync(package, cancellationToken)
                };
                return new(StatusCodes.Status200OK, new ModelValidationResponse("READY", [],
                    "ECLIPSE 300 compositional historical-state forecast inspection completed.", "eclipse_300", Inspection: portableInspection));
            }
            var inspection = EclipseDataInspector.InspectPackage(package.Files, Path.GetFileName(request.ModelStorageKey), package.Root);
            var packageFiles = await DescribePackageAsync(package, cancellationToken);
            var simulation = EclipseSimulationModelReader.Read(package);
            if (simulation is not null)
            {
                // Keep E100 schedule editors outside the E300 metadata contract.
                var productionContext = simulation.ModelType == "COMPOSITIONAL" ? EclipseCompositionalProductionReader.Read(package)?.Context : null;
                var reinjectionContext = simulation.ModelType == "COMPOSITIONAL" ? EclipseCompositionalReinjectionReader.Read(package)?.Context : null;
                if (reinjectionContext is not null && (productionContext is null || productionContext.SourceSha256 != reinjectionContext.SourceSha256)) reinjectionContext = null;
                var fluidPropertyContext = simulation.ModelType == "COMPOSITIONAL" ? EclipseCompositionalFluidPropertyReader.Read(package)?.Context : null;
                if (fluidPropertyContext is not null && (productionContext is null || productionContext.SourceSha256 != fluidPropertyContext.SourceSha256 || productionContext.SourceSizeBytes != fluidPropertyContext.SourceSizeBytes)) fluidPropertyContext = null;
                var temperatureContext = fluidPropertyContext is not null ? EclipseCompositionalFluidPropertyReader.Read(package, "TCRIT")?.Context : null;
                if (temperatureContext is not null && (temperatureContext.SourceSha256 != fluidPropertyContext!.SourceSha256 || temperatureContext.SourceSizeBytes != fluidPropertyContext.SourceSizeBytes)) temperatureContext = null;
                var criticalZContext = temperatureContext is not null ? EclipseCompositionalFluidPropertyReader.Read(package, "ZCRIT")?.Context : null;
                if (criticalZContext is not null && (criticalZContext.SourceSha256 != temperatureContext!.SourceSha256 || criticalZContext.SourceSizeBytes != temperatureContext.SourceSizeBytes)) criticalZContext = null;
                var acentricFactorContext = criticalZContext is not null ? EclipseCompositionalFluidPropertyReader.Read(package, "ACF")?.Context : null;
                if (acentricFactorContext is not null && (acentricFactorContext.SourceSha256 != criticalZContext!.SourceSha256 || acentricFactorContext.SourceSizeBytes != criticalZContext.SourceSizeBytes)) acentricFactorContext = null;
                var eosProperties = new[] { "MW", "OMEGAA", "OMEGAB" }.Select(field => acentricFactorContext is not null ? EclipseCompositionalFluidPropertyReader.Read(package, field)?.Context : null).ToArray();
                IReadOnlyList<EclipseCompositionalFluidPropertyContext>? eosPropertyContexts = eosProperties.All(c => c is not null && c.SourceSha256 == acentricFactorContext!.SourceSha256 && c.SourceSizeBytes == acentricFactorContext.SourceSizeBytes) ? eosProperties.Select(c => c!).ToArray() : null;
                var thermalRock = simulation.ModelType == "THERMAL" ? EclipseThermalRockReader.Read(package)?.Context : null;
                inspection = inspection with { SchemaVersion = simulation.ModelType == "THERMAL" ? thermalRock is null ? "eclipse-data-inspection/15" : "eclipse-data-inspection/23" : eosPropertyContexts is not null ? "eclipse-data-inspection/22" : acentricFactorContext is not null ? "eclipse-data-inspection/21" : criticalZContext is not null ? "eclipse-data-inspection/20" : temperatureContext is not null ? "eclipse-data-inspection/19" : fluidPropertyContext is not null ? "eclipse-data-inspection/17" : reinjectionContext is null ? "eclipse-data-inspection/14" : "eclipse-data-inspection/16", PackageFiles = packageFiles, Simulation = simulation.Metadata,
                    ScheduleMetadata = null, VfpTables = null, GroupControls = null, InjectionGroupControls = null, GasInjectionGroupControls = null,
                    GroupRateControls = null, WaterProductionGroupControls = null, AdvancedInjectionGroupControls = null, GroupStructureControls = null, ProductionControlPlan = null,
                    CompositionalProductionContext = productionContext, CompositionalReinjectionContext = reinjectionContext, CompositionalFluidPropertyContext = fluidPropertyContext, CompositionalTemperatureContext = temperatureContext, CompositionalCriticalZContext = criticalZContext, CompositionalAcentricFactorContext = acentricFactorContext, CompositionalEosPropertyContexts = eosPropertyContexts,
                    ThermalInjectionContext = simulation.ModelType == "THERMAL" ? EclipseThermalInjectionReader.Read(package)?.Context : null, ThermalRockContext = thermalRock };
                return new(StatusCodes.Status200OK, new ModelValidationResponse("READY", [], $"ECLIPSE 300 {simulation.ModelType.ToLowerInvariant()} DATA inspection completed.", "eclipse_300", Inspection: inspection));
            }
            inspection = inspection with
            {
                SchemaVersion = inspection.ProductionControlPlan is not null ? "eclipse-data-inspection/13" : inspection.GroupStructureControls is not null ? "eclipse-data-inspection/12" : inspection.AdvancedInjectionGroupControls is not null ? "eclipse-data-inspection/11" : inspection.WaterProductionGroupControls is not null ? "eclipse-data-inspection/10" : inspection.GroupRateControls is not null ? "eclipse-data-inspection/9" : inspection.GasInjectionGroupControls is not null ? "eclipse-data-inspection/8" : inspection.InjectionGroupControls is not null ? "eclipse-data-inspection/7" : inspection.GroupControls is not null ? "eclipse-data-inspection/6" : inspection.VfpTables is not null ? "eclipse-data-inspection/5" : inspection.ScheduleMetadata is null ? "eclipse-data-inspection/3" : "eclipse-data-inspection/4",
                PackageFiles = packageFiles
            };
            return new(StatusCodes.Status200OK, new ModelValidationResponse(
                "READY", [], "ECLIPSE .DATA inspection completed.", "eclipse_100",
                Inspection: inspection));
        }
        catch (InvalidDataException error)
        {
            return Invalid("ECLIPSE_COMPOSITIONAL_RESTART_INVALID", error.Message);
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
