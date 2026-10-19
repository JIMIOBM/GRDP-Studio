using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed partial class EclipseRunService : IDisposable
{
    private readonly StorageResolver storage; private readonly ArtifactStore artifacts; private readonly EclipseExecutionCoordinator coordinator;
    private readonly PtkRunRegistry registry; private readonly EclipseLauncher launcher; private readonly EclipseDeckPackageResolver packages; private readonly WorkerOptions options; private readonly SemaphoreSlim submissions = new(1, 1);
    public EclipseRunService(StorageResolver storage, EclipseExecutionCoordinator coordinator, PtkRunRegistry registry, EclipseLauncher launcher, EclipseDeckPackageResolver packages, IOptions<WorkerOptions> options)
        => (this.storage, artifacts, this.coordinator, this.registry, this.launcher, this.packages, this.options) = (storage, new ArtifactStore(storage), coordinator, registry, launcher, packages, options.Value);

    public async Task<ApiOutcome> SubmitAsync(RunExecuteRequest request, CancellationToken cancellationToken)
    {
        var error = Validate(request); if (error is not null) return new(400, error);
        await submissions.WaitAsync(cancellationToken);
        try
        {
            var fingerprint = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("\n", request.RunId, request.ModelStorageKey, request.ExpectedModelSha256, "eclipse", request.TimeoutSeconds, request.Parameters.GetRawText()))));
            if (registry.ExistsWithFingerprint(request.RunId, fingerprint, out var existing, out var conflict)) return conflict ? new(409, WorkerApiError.Request("RUN_ID_CONFLICT", "runId is already associated with a different request.")) : new(202, existing!);
            string source;
            try { source = storage.ResolveExistingData(request.ModelStorageKey); if (await storage.ComputeSha256Async(source, cancellationToken) != request.ExpectedModelSha256) return new(422, WorkerApiError.Storage("MODEL_SHA256_MISMATCH", "The source model SHA-256 does not match expectedModelSha256.")); storage.EnsureRunDirectoryAvailable(request.RunId); }
            catch (StorageException exception) { return new(exception.HttpStatus, WorkerApiError.Storage(exception.Code, exception.Message)); }
            EclipseSimulationModel? requestedSimulation;
            try { requestedSimulation = EclipseSimulationModelReader.Read(packages.Resolve(source, storage.ResolveModelPackageRoot(source))); }
            catch (EclipseDeckPackageException exception) { return new(422, WorkerApiError.Request(exception.Code, exception.Message)); }
            var compositionalForecast = EclipseCompositionalForecastParameters.TryParse(request.Parameters, out var compositionalForecastParameters);
            if (compositionalForecast)
            {
                try
                {
                    if (requestedSimulation?.ModelType != "COMPOSITIONAL")
                        return new(400, WorkerApiError.Request("ECLIPSE_COMPOSITIONAL_FORECAST_MISMATCH", "Historical SAVE forecasting requires its qualified compositional model."));
                    _ = await EclipseCompositionalSaveState.LoadPublishedAsync(storage, compositionalForecastParameters!,
                        packages.Resolve(source, storage.ResolveModelPackageRoot(source)), cancellationToken);
                }
                catch (InvalidDataException exception) { return new(400, WorkerApiError.Request("ECLIPSE_COMPOSITIONAL_FORECAST_MISMATCH", exception.Message)); }
                catch (StorageException exception) { return new(exception.HttpStatus, WorkerApiError.Storage(exception.Code, exception.Message)); }
            }
            var thermalRock = EclipseThermalRockParameters.TryParse(request.Parameters, out var rockParameters);
            if (thermalRock && (requestedSimulation?.ModelType != "THERMAL"
                || !EclipseThermalRockEditor.Matches(EclipseThermalRockReader.Read(packages.Resolve(source, storage.ResolveModelPackageRoot(source))), rockParameters!)))
                return new(400, WorkerApiError.Request("ECLIPSE_THERMAL_ROCK_MISMATCH", "The thermal rock property does not match its complete qualified source."));
            var thermalPlan = EclipseThermalPlanParameters.TryParse(request.Parameters, out var planParameters);
            if (thermalPlan && (requestedSimulation?.ModelType != "THERMAL"
                || !EclipseThermalPlanEditor.Matches(EclipseThermalInjectionReader.Read(packages.Resolve(source, storage.ResolveModelPackageRoot(source))), planParameters!)))
                return new(400, WorkerApiError.Request("ECLIPSE_THERMAL_PLAN_MISMATCH", "The thermal plan does not match its qualified complete source and report boundaries."));
            var thermalTarget = EclipseThermalInjectionParameters.TryParse(request.Parameters, out var thermalInjection);
            if (thermalTarget && (requestedSimulation?.ModelType != "THERMAL"
                || !EclipseThermalInjectionEditor.Matches(EclipseThermalInjectionReader.Read(packages.Resolve(source, storage.ResolveModelPackageRoot(source))), thermalInjection!)))
                return new(400, WorkerApiError.Request("ECLIPSE_THERMAL_INJECTION_MISMATCH", "The thermal injection property does not match the qualified complete source."));
            var compositionalReinjection = EclipseCompositionalReinjectionParameters.TryParse(request.Parameters, out var reinjectionTarget);
            if (compositionalReinjection && (requestedSimulation?.ModelType != "COMPOSITIONAL"
                || !EclipseCompositionalReinjectionEditor.Matches(EclipseCompositionalReinjectionReader.Read(packages.Resolve(source, storage.ResolveModelPackageRoot(source))), reinjectionTarget!)))
                return new(400, WorkerApiError.Request("ECLIPSE_COMPOSITIONAL_REINJECTION_MISMATCH", "The E300 available-gas reinjection fraction does not match the qualified complete source."));
            var compositionalGasSales = EclipseCompositionalGasSalesParameters.TryParse(request.Parameters, out var gasSalesTarget);
            if (compositionalGasSales && (requestedSimulation?.ModelType != "COMPOSITIONAL"
                || !EclipseCompositionalGasSalesEditor.Matches(EclipseCompositionalReinjectionReader.Read(packages.Resolve(source, storage.ResolveModelPackageRoot(source))), gasSalesTarget!)))
                return new(400, WorkerApiError.Request("ECLIPSE_COMPOSITIONAL_GAS_SALES_MISMATCH", "The E300 constant gas sales target does not match the qualified complete source."));
            var compositionalInjectionPressure = EclipseCompositionalInjectionPressureParameters.TryParse(request.Parameters, out var injectionPressureTarget);
            if (compositionalInjectionPressure && (requestedSimulation?.ModelType != "COMPOSITIONAL"
                || !EclipseCompositionalInjectionPressureEditor.Matches(EclipseCompositionalReinjectionReader.Read(packages.Resolve(source, storage.ResolveModelPackageRoot(source))), injectionPressureTarget!)))
                return new(400, WorkerApiError.Request("ECLIPSE_COMPOSITIONAL_INJECTION_PRESSURE_MISMATCH", "The E300 injector BHP upper limit does not match the qualified complete source."));
            var compositionalFluidProperty = EclipseCompositionalFluidPropertyParameters.TryParse(request.Parameters, out var fluidPropertyTarget);
            if (compositionalFluidProperty && (requestedSimulation?.ModelType != "COMPOSITIONAL"
                || !EclipseCompositionalFluidPropertyEditor.Matches(EclipseCompositionalFluidPropertyReader.Read(packages.Resolve(source, storage.ResolveModelPackageRoot(source)), fluidPropertyTarget!.Field), fluidPropertyTarget)))
                return new(400, WorkerApiError.Request("ECLIPSE_COMPOSITIONAL_FLUID_PROPERTY_MISMATCH", "The E300 component critical property does not match the qualified complete source."));
            var compositionalPressure = EclipseCompositionalPressureParameters.TryParse(request.Parameters, out var pressureTarget);
            if (compositionalPressure && (requestedSimulation?.ModelType != "COMPOSITIONAL"
                || !EclipseCompositionalPressureEditor.Matches(EclipseCompositionalProductionReader.Read(packages.Resolve(source, storage.ResolveModelPackageRoot(source))), pressureTarget!)))
                return new(400, WorkerApiError.Request("ECLIPSE_COMPOSITIONAL_PRESSURE_MISMATCH", "The E300 producer BHP lower limit does not match the qualified complete source."));
            var compositionalTarget = EclipseCompositionalProductionParameters.TryParse(request.Parameters, out var gasTarget);
            if (compositionalTarget && (requestedSimulation?.ModelType != "COMPOSITIONAL"
                || !EclipseCompositionalProductionEditor.Matches(EclipseCompositionalProductionReader.Read(packages.Resolve(source, storage.ResolveModelPackageRoot(source))), gasTarget!)))
                return new(400, WorkerApiError.Request("ECLIPSE_COMPOSITIONAL_PRODUCTION_MISMATCH", "The E300 gas target does not match the qualified complete source."));
            if (requestedSimulation is not null && request.Parameters.ValueKind != JsonValueKind.Null && !compositionalTarget && !compositionalPressure && !compositionalReinjection && !compositionalGasSales && !compositionalInjectionPressure && !compositionalFluidProperty && !thermalTarget && !thermalPlan && !thermalRock && !compositionalForecast)
                return new(400, WorkerApiError.Request("ECLIPSE_COMPOSITIONAL_SCENARIO_UNSUPPORTED", "Only qualified E300 producer gas-target/BHP or thermal-injection parameters can edit their respective model types; E100 and restart parameters remain unsupported."));
            var capability = await launcher.GetCapabilityAsync(requestedSimulation?.Solver ?? 100, cancellationToken);
            if (!capability.Available) return new(503, new WorkerError("ENVIRONMENT", "ECLIPSE_UNAVAILABLE", "ECLIPSE launcher is unavailable.", true));
            var lease = coordinator.TryAcquire("run"); if (!lease.Acquired) return new(409, lease.Error!);
            var claim = registry.TryClaim(request, fingerprint);
            if (claim.Status != ClaimStatus.Created) { lease.Lease!.Dispose(); return new(409, claim.Error!); }
            _ = Task.Run(() => ExecuteAsync(request, source, lease.Lease!), CancellationToken.None);
            return new(202, claim.Accepted!);
        }
        finally { submissions.Release(); }
    }

    private async Task ExecuteAsync(RunExecuteRequest request, string source, PtkExecutionCoordinator.CoordinatorLease lease)
    {
        RunDirectories? dirs = null; var descriptors = new List<ArtifactDescriptor>(); JsonElement? result = null; WorkerError? error = null; string? exceptionDetail = null; var state = "FAILED"; var message = "ECLIPSE run failed."; var treeConfirmed = true; var killUsed = false; var cleanupFailed = false;
        var cancellation = registry.GetCancellationToken(request.RunId);
        try
        {
            registry.Transition(request.RunId, "PREPARING", "Preparing isolated ECLIPSE deck.");
            dirs = storage.CreateRunDirectories(request.RunId, source);
            if (await storage.ComputeSha256Async(dirs.ModelCopy, CancellationToken.None) != request.ExpectedModelSha256) throw new StorageException("MODEL_COPY_SHA256_MISMATCH", "The task model copy failed SHA-256 verification.");
            var inputPackage = packages.Resolve(dirs.ModelCopy, dirs.Input);
            await EclipsePackageIntegrity.VerifyAsync(inputPackage, request.ExpectedPackageFiles, CancellationToken.None);
            var workModel = storage.CopyInputPackageToWork(dirs);
            var workPackage = packages.Resolve(workModel, dirs.Work);
            var simulation = EclipseSimulationModelReader.Read(workPackage);
            var solver = simulation?.Solver ?? 100;
            EclipseForecastSource? verifiedFuture = null;
            await EclipsePackageIntegrity.VerifyAsync(workPackage, request.ExpectedPackageFiles, CancellationToken.None);
            var compositionalState = EclipseCompositionalSaveState.ReadPortable(workPackage);
            if (EclipseCompositionalForecastParameters.TryParse(request.Parameters, out var e300Forecast))
                compositionalState = await EclipseCompositionalSaveState.PreparePublishedAsync(storage, e300Forecast!, workPackage, cancellation);
            if (compositionalState is not null)
                descriptors.Add(await artifacts.WriteEclipseCompositionalForecastPackageAsync(dirs.Output, workPackage, cancellation));
            if (request.Parameters.ValueKind == JsonValueKind.Null && EclipsePortableRestartBinding.Validate(workPackage) is not null)
                verifiedFuture = EclipseForecastSourceReader.Read(workPackage, true, true);
            if (EclipseThermalRockParameters.TryParse(request.Parameters, out var thermalRockParameters))
            {
                if (simulation?.ModelType != "THERMAL") throw new EclipseDeckPackageException("ECLIPSE_THERMAL_ROCK_MISMATCH", "This property requires a verified E300 thermal model.");
                registry.Report(request.RunId,"PREPARING",await EclipseThermalRockEditor.ApplyAsync(workPackage,thermalRockParameters!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseThermalPlanParameters.TryParse(request.Parameters, out var thermalPlanParameters))
            {
                if (simulation?.ModelType != "THERMAL") throw new EclipseDeckPackageException("ECLIPSE_THERMAL_PLAN_MISMATCH", "This schedule requires a verified E300 thermal model.");
                registry.Report(request.RunId,"PREPARING",await EclipseThermalPlanEditor.ApplyAsync(workPackage,thermalPlanParameters!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseThermalInjectionParameters.TryParse(request.Parameters, out var thermalParameters))
            {
                if (simulation?.ModelType != "THERMAL") throw new EclipseDeckPackageException("ECLIPSE_THERMAL_INJECTION_MISMATCH", "This property requires a verified E300 thermal model.");
                registry.Report(request.RunId,"PREPARING",await EclipseThermalInjectionEditor.ApplyAsync(workPackage,thermalParameters!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseCompositionalReinjectionParameters.TryParse(request.Parameters, out var reinjectionParameters))
            {
                if (simulation?.ModelType != "COMPOSITIONAL") throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_REINJECTION_MISMATCH", "This fraction requires a verified E300 compositional model.");
                registry.Report(request.RunId, "PREPARING", await EclipseCompositionalReinjectionEditor.ApplyAsync(workPackage, reinjectionParameters!, CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
            }
            else if (EclipseCompositionalGasSalesParameters.TryParse(request.Parameters, out var gasSalesParameters))
            {
                if (simulation?.ModelType != "COMPOSITIONAL") throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_GAS_SALES_MISMATCH", "This gas sales target requires a verified E300 compositional model.");
                registry.Report(request.RunId, "PREPARING", await EclipseCompositionalGasSalesEditor.ApplyAsync(workPackage, gasSalesParameters!, CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
            }
            else if (EclipseCompositionalInjectionPressureParameters.TryParse(request.Parameters, out var injectionPressureParameters))
            {
                if (simulation?.ModelType != "COMPOSITIONAL") throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_INJECTION_PRESSURE_MISMATCH", "This injector BHP upper limit requires a verified E300 compositional model.");
                registry.Report(request.RunId, "PREPARING", await EclipseCompositionalInjectionPressureEditor.ApplyAsync(workPackage, injectionPressureParameters!, CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
            }
            else if (EclipseCompositionalFluidPropertyParameters.TryParse(request.Parameters, out var fluidPropertyParameters))
            {
                if (simulation?.ModelType != "COMPOSITIONAL") throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_FLUID_PROPERTY_MISMATCH", "This component critical pressure requires a verified E300 compositional model.");
                registry.Report(request.RunId, "PREPARING", await EclipseCompositionalFluidPropertyEditor.ApplyAsync(workPackage, fluidPropertyParameters!, CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
            }
            else if (EclipseCompositionalPressureParameters.TryParse(request.Parameters, out var pressureParameters))
            {
                if (simulation?.ModelType != "COMPOSITIONAL") throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_PRESSURE_MISMATCH", "This BHP lower limit requires a verified E300 compositional model.");
                registry.Report(request.RunId, "PREPARING", await EclipseCompositionalPressureEditor.ApplyAsync(workPackage, pressureParameters!, CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
            }
            else if (EclipseCompositionalProductionParameters.TryParse(request.Parameters, out var compositionalProduction))
            {
                if (simulation?.ModelType != "COMPOSITIONAL") throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_PRODUCTION_MISMATCH", "This target requires a verified E300 compositional model.");
                registry.Report(request.RunId, "PREPARING", await EclipseCompositionalProductionEditor.ApplyAsync(workPackage, compositionalProduction!, CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
            }
            else if (EclipseMultiWaterPvtParameters.TryParse(request.Parameters, out var multiWaterPvt))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseMultiWaterPvtEditor.ApplyAsync(workPackage,multiWaterPvt!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseMultiWetGasPvtParameters.TryParse(request.Parameters, out var multiWetGasPvt))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseMultiWetGasPvtEditor.ApplyAsync(workPackage,multiWetGasPvt!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseMultiLiveOilPvtParameters.TryParse(request.Parameters, out var multiLiveOilPvt))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseMultiLiveOilPvtEditor.ApplyAsync(workPackage,multiLiveOilPvt!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseMultiPvtParameters.TryParse(request.Parameters, out var multiPvt))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseMultiPvtEditor.ApplyAsync(workPackage,multiPvt!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseMultiSatFamilyIIParameters.TryParse(request.Parameters, out var multiFamilyII))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseMultiSatFamilyIIEditor.ApplyAsync(workPackage,multiFamilyII!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseMultiSatRelpermParameters.TryParse(request.Parameters, out var multiSatRelperm))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseMultiSatRelpermEditor.ApplyAsync(workPackage,multiSatRelperm!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseCoupledRelpermParameters.TryParse(request.Parameters, out var coupledRelperm))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseCoupledRelpermEditor.ApplyAsync(workPackage,coupledRelperm!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseRelpermParameters.TryParse(request.Parameters, out var relperm))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseRelpermEditor.ApplyAsync(workPackage,relperm!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseGasPvtParameters.TryParse(request.Parameters, out var gasPvt))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseGasPvtEditor.ApplyAsync(workPackage,gasPvt!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseWetGasPvtParameters.TryParse(request.Parameters, out var wetGasPvt))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseWetGasPvtEditor.ApplyAsync(workPackage,wetGasPvt!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseLiveOilPvtParameters.TryParse(request.Parameters, out var liveOilPvt))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseLiveOilPvtEditor.ApplyAsync(workPackage,liveOilPvt!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipseOilPvtParameters.TryParse(request.Parameters, out var oilPvt))
            {
                registry.Report(request.RunId,"PREPARING",await EclipseOilPvtEditor.ApplyAsync(workPackage,oilPvt!,CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output,workPackage,CancellationToken.None));
            }
            else if (EclipsePvtwParameters.TryParse(request.Parameters, out var pvtw))
            {
                registry.Report(request.RunId, "PREPARING", await EclipsePvtwEditor.ApplyAsync(workPackage, pvtw!, CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
            }
            else if (EclipseNewCompletionParameters.TryParse(request.Parameters, out var newCompletion))
            {
                var baselineDirectory = Path.Combine(dirs.Work, ".grdp-baseline");
                try
                {
                    var grid = await storage.CopyPublishedEclipseGridArtifactToWorkAsync(newCompletion!.BaselineRunId,
                        newCompletion.GridFile, newCompletion.GridArtifactSha256, dirs.Work, CancellationToken.None);
                    registry.Report(request.RunId, "PREPARING", await EclipseNewCompletionEditor.ApplyAsync(workPackage, newCompletion, grid, CancellationToken.None));
                    descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
                }
                finally { StorageResolver.TryDeleteDirectory(baselineDirectory); }
            }
            else if (EclipseNewWellParameters.TryParse(request.Parameters, out var newWell))
            {
                var baselineDirectory = Path.Combine(dirs.Work, ".grdp-baseline");
                try
                {
                    var grid = await storage.CopyPublishedEclipseGridArtifactToWorkAsync(newWell!.BaselineRunId,
                        newWell.GridFile, newWell.GridArtifactSha256, dirs.Work, CancellationToken.None);
                    registry.Report(request.RunId, "PREPARING", await EclipseNewWellEditor.ApplyAsync(workPackage, newWell, grid, CancellationToken.None));
                    descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
                }
                finally { StorageResolver.TryDeleteDirectory(baselineDirectory); }
            }
            else if (EclipseCompletionGeometryParameters.TryParse(request.Parameters, out var completionGeometry))
            {
                registry.Report(request.RunId, "PREPARING", await EclipseCompletionGeometryEditor.ApplyAsync(workPackage, completionGeometry!, CancellationToken.None));
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
            }
            else if (EclipseGridRegionParameters.TryParse(request.Parameters, out var region))
            {
                var baselineDirectory = Path.Combine(dirs.Work, ".grdp-baseline");
                try
                {
                    var init = await storage.CopyPublishedEclipseInitArtifactToWorkAsync(region!.BaselineRunId,
                        region.FieldFile, region.FieldArtifactSha256, dirs.Work, CancellationToken.None);
                    var grid = await storage.CopyPublishedEclipseGridArtifactToWorkAsync(region.BaselineRunId,
                        region.GridFile, region.GridArtifactSha256, dirs.Work, CancellationToken.None);
                    registry.Report(request.RunId, "PREPARING", await EclipseGridRegionEditor.ApplyAsync(workPackage, region, init, grid, CancellationToken.None));
                    descriptors.Add(await artifacts.WriteEclipseAppliedPackageAsync(dirs.Output, workPackage, CancellationToken.None));
                }
                finally { StorageResolver.TryDeleteDirectory(baselineDirectory); }
            }
            else if (EclipseGridFieldParameters.TryParse(request.Parameters, out var gridFieldParameters))
            {
                var baselineField = await storage.CopyPublishedEclipseInitArtifactToWorkAsync(
                    gridFieldParameters!.BaselineRunId, gridFieldParameters.FieldFile, gridFieldParameters.FieldArtifactSha256,
                    dirs.Work, CancellationToken.None);
                try
                {
                    var editMessage = await EclipseGridFieldEditor.ApplyAsync(workPackage, gridFieldParameters, baselineField, CancellationToken.None);
                    registry.Report(request.RunId, "PREPARING", editMessage);
                    descriptors.Add(await artifacts.WriteEclipseAppliedPackageAsync(dirs.Output, workPackage, CancellationToken.None));
                }
                finally
                {
                    var baselineDirectory = Path.GetDirectoryName(baselineField);
                    if (baselineDirectory is not null) StorageResolver.TryDeleteDirectory(baselineDirectory);
                }
            }
            else if (EclipseCompletionFactorParameters.TryParse(request.Parameters, out var completionFactorParameters))
            {
                var editMessage = await EclipseScheduleEditor.ApplyCompletionFactorAsync(workPackage, completionFactorParameters!, CancellationToken.None);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
            }
            else if (EclipseCompletionParameters.TryParse(request.Parameters, out var completionParameters))
            {
                var editMessage = await EclipseScheduleEditor.ApplyCompletionAsync(workPackage, completionParameters!, CancellationToken.None);
                registry.Report(request.RunId, "PREPARING", editMessage);
            }
            else if (EclipseGroupGasInjectionParameters.TryParse(request.Parameters, out var groupGasInjection))
            {
                var editMessage = await EclipseGroupGasInjectionEditor.ApplyAsync(workPackage, groupGasInjection!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseGroupInjectionParameters.TryParse(request.Parameters, out var groupInjection))
            {
                var editMessage = await EclipseGroupInjectionEditor.ApplyAsync(workPackage, groupInjection!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseWaterInjectionPlanParameters.TryParse(request.Parameters, out var waterInjectionPlan))
            {
                var editMessage = await EclipseWaterInjectionPlanEditor.ApplyAsync(workPackage, waterInjectionPlan!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipsePackageControlPlanParameters.TryParse(request.Parameters, out var packageControlPlan))
            {
                var editMessage = await EclipsePackageControlPlanEditor.ApplyAsync(workPackage, packageControlPlan!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseControlPlanParameters.TryParse(request.Parameters, out var controlPlan))
            {
                var editMessage = await EclipseControlPlanEditor.ApplyAsync(workPackage, controlPlan!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseGroupStructureParameters.TryParse(request.Parameters, out var groupStructure))
            {
                var editMessage = await EclipseGroupStructureEditor.ApplyAsync(workPackage, groupStructure!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseAdvancedInjectionParameters.TryParse(request.Parameters, out var advancedInjection))
            {
                var editMessage = await EclipseAdvancedInjectionEditor.ApplyAsync(workPackage, advancedInjection!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseGroupWaterProductionParameters.TryParse(request.Parameters, out var groupWaterProduction))
            {
                var editMessage = await EclipseGroupWaterProductionEditor.ApplyAsync(workPackage, groupWaterProduction!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseGroupRateParameters.TryParse(request.Parameters, out var groupRate))
            {
                var editMessage = await EclipseGroupRateEditor.ApplyAsync(workPackage, groupRate!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseGroupProductionParameters.TryParse(request.Parameters, out var groupProduction))
            {
                var editMessage = await EclipseGroupProductionEditor.ApplyAsync(workPackage, groupProduction!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseTstepParameters.TryParse(request.Parameters, out var tstep))
            {
                var editMessage = await EclipseTstepEditor.ApplyAsync(workPackage, tstep!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseNewDatesParameters.TryParse(request.Parameters, out var newDates))
            {
                var editMessage = await EclipseNewDatesEditor.ApplyAsync(workPackage, newDates!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseThpControlParameters.TryParse(request.Parameters, out var thpControl))
            {
                var editMessage = await EclipseThpControlEditor.ApplyAsync(workPackage, thpControl!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseProductionControlParameters.TryParse(request.Parameters, out var productionControl))
            {
                var editMessage = await EclipseProductionControlEditor.ApplyAsync(workPackage, productionControl!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipsePressureControlParameters.TryParse(request.Parameters, out var pressureControl))
            {
                var editMessage = await EclipsePressureControlEditor.ApplyAsync(workPackage, pressureControl!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseProductionTargetParameters.TryParse(request.Parameters, out var productionTarget))
            {
                var editMessage = await EclipseProductionTargetEditor.ApplyAsync(workPackage, productionTarget!, cancellation);
                registry.Report(request.RunId, "PREPARING", editMessage);
                descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, cancellation));
            }
            else if (EclipseScheduleParameters.TryParse(request.Parameters, out var scheduleParameters))
            {
                var editMessage = await EclipseScheduleEditor.ApplyAsync(workPackage, scheduleParameters!, CancellationToken.None);
                registry.Report(request.RunId, "PREPARING", editMessage);
                if (scheduleParameters!.SchemaVersion is EclipseScheduleParameters.MultiWellSchema or EclipseScheduleParameters.MultiDateSchema
                    or EclipseScheduleParameters.ControlSchema or EclipseScheduleParameters.InjectionSchema)
                    descriptors.Add(await artifacts.WriteEclipseSchedulePackageAsync(dirs.Output, workPackage, CancellationToken.None));
            }
            else if (EclipseRestartForecastParameters.TryParse(request.Parameters, out var restartForecast))
            {
                var binding = restartForecast!.Reference;
                var forecastPath = ResolvePackageFile(dirs.Work, binding.ForecastDataFile);
                var forecastPackage = packages.Resolve(forecastPath, dirs.Work);
                var restartPath = await storage.CopyPublishedRestartArtifactToWorkAsync(binding.HistoryRunId,
                    binding.RestartArtifactName, binding.RestartArtifactSha256, dirs.Work, CancellationToken.None);
                var catalog = EclipseRestartReader.Read(restartPath);
                restartForecast.Validate(workPackage, forecastPackage, catalog);
                verifiedFuture = EclipseForecastSourceReader.Read(forecastPackage, true, restartForecast.Binary);
                var bytes = EclipseRestartReader.Select(restartPath, catalog, binding.RestartReport);
                await File.WriteAllBytesAsync(restartPath, bytes, cancellation);
                descriptors.Add(await artifacts.WriteEclipseForecastPackageAsync(dirs.Output, forecastPackage, restartPath, cancellation));
                workPackage = forecastPackage;
                workModel = forecastPath;
                registry.Report(request.RunId, "PREPARING", $"Prepared verified forecast at {restartForecast.RestartDate}, report {binding.RestartReport}, day {restartForecast.RestartTimeDays}; exported complete portable state.");
            }
            else if (EclipseHistoryForecastParameters.TryParse(request.Parameters, out var forecastParameters))
            {
                var forecastPath = ResolvePackageFile(dirs.Work, forecastParameters!.ForecastDataFile);
                workPackage = packages.Resolve(forecastPath, dirs.Work);
                EclipseHistoryForecastParameters.ValidateRestartBinding(workPackage, forecastParameters);
                await storage.CopyPublishedRestartArtifactToWorkAsync(forecastParameters!.HistoryRunId,
                    forecastParameters.RestartArtifactName, forecastParameters.RestartArtifactSha256, dirs.Work, CancellationToken.None);
                workModel = forecastPath;
                registry.Report(request.RunId, "PREPARING", $"Prepared forecast DATA {forecastParameters.ForecastDataFile} from restart report {forecastParameters.RestartReport}.");
            }
            var deck = Path.GetRelativePath(dirs.Work, workModel);
            var compositionalHistory = compositionalState is null && simulation?.ModelType == "COMPOSITIONAL" && request.Parameters.ValueKind == JsonValueKind.Null
                ? EclipseCompositionalSaveSourceReader.Read(workPackage) : null;
            var before = Snapshot(dirs.Work);
            registry.Transition(request.RunId, "RUNNING_ECLIPSE", $"Running ECLIPSE {solver}.");
            var launch = await launcher.RunAsync(solver, deck, dirs.Work, TimeSpan.FromSeconds(request.TimeoutSeconds), cancellation);
            treeConfirmed = launch.ProcessTreeExitConfirmed; killUsed |= launch.KillUsed;
            var cleanup = await launcher.CleanupAsync(deck, dirs.Work, launch.StopReason == AdapterStopReason.Cancelled, CancellationToken.None);
            treeConfirmed &= cleanup.ProcessTreeExitConfirmed; killUsed |= cleanup.KillUsed;
            if (!treeConfirmed) { cleanupFailed = true; error = new("CLEANUP", "PROCESS_TREE_EXIT_UNCONFIRMED", "ECLIPSE process-tree exit could not be confirmed.", false); return; }
            if (cleanup.Error is not null || cleanup.ExitCode != 0) { cleanupFailed = true; error = new("CLEANUP", "ECLIPSE_CLEANUP_FAILED", "ECLIPSE cleanup could not be confirmed.", false); return; }
            if (launch.StopReason == AdapterStopReason.Cancelled) { state = "CANCELLED"; error = launch.Error; message = "Run cancellation completed after ECLIPSE cleanup."; return; }
            if (launch.StopReason == AdapterStopReason.TimedOut) { state = "TIMED_OUT"; error = launch.Error; message = "Run timed out after ECLIPSE cleanup."; return; }
            var collectCompositionalSave = compositionalHistory is not null;
            var saveNames = compositionalHistory is null ? null : new HashSet<string>(StringComparer.OrdinalIgnoreCase) {
                Path.GetFileNameWithoutExtension(compositionalHistory.Production.SourceFile) + ".V0000",
                Path.GetFileNameWithoutExtension(compositionalHistory.Production.SourceFile) + ".V" + compositionalHistory.ReportNumber.ToString("D4", System.Globalization.CultureInfo.InvariantCulture),
                Path.GetFileNameWithoutExtension(compositionalHistory.Production.SourceFile) + ".X" + compositionalHistory.ReportNumber.ToString("D4", System.Globalization.CultureInfo.InvariantCulture)
            };
            var fresh = Fresh(dirs.Work, before, collectCompositionalSave, saveNames).ToArray();
            var eclEnd = fresh.FirstOrDefault(path => path.EndsWith(".ECLEND", StringComparison.OrdinalIgnoreCase));
            var diagnostics = launch.Diagnostics + "\n" + string.Join("\n", fresh.Where(path => path.EndsWith(".MSG", StringComparison.OrdinalIgnoreCase) || path.EndsWith(".ECLEND", StringComparison.OrdinalIgnoreCase)).Select(File.ReadAllText));
            if (EclipseRunRules.HasLicenseDiagnostic(diagnostics)) { error = new("LICENSE", "LICENSE_UNAVAILABLE", "ECLIPSE reported a license diagnostic.", true); return; }
            if (launch.ExitCode != 0 || eclEnd is null || !EclipseParsers.TryParseEclEnd(await File.ReadAllTextAsync(eclEnd), out var counts)) { error = new("EXECUTION", "ECLIPSE_RUN_FAILED", "ECLIPSE did not produce a valid fresh ECLEND result.", false); return; }
            if (counts!.Errors != 0 || counts.Bugs != 0 || EclipseRunRules.HasFatalDiagnostic(diagnostics)) { error = new("SOLVER", "ECLIPSE_SOLVER_FAILED", "ECLIPSE reported fatal solver errors.", false); return; }
            var diagnosticMessages = EclipseParsers.ParseDiagnosticMessages(
                fresh.Where(path => path.EndsWith(".MSG", StringComparison.OrdinalIgnoreCase)));
            var partial = counts.Problems > 0;
            if (await storage.ComputeSha256Async(source, CancellationToken.None) != request.ExpectedModelSha256)
                throw new StorageException("MODEL_SOURCE_CHANGED", "The source model changed before ECLIPSE result publication.");
            var sourcePackage = packages.Resolve(source, storage.ResolveModelPackageRoot(source));
            await EclipsePackageIntegrity.VerifyAsync(sourcePackage, request.ExpectedPackageFiles, CancellationToken.None);
            registry.Transition(request.RunId, "COLLECTING", "Collecting fresh ECLIPSE output.");
            var smspec = fresh.FirstOrDefault(path => path.EndsWith(".SMSPEC", StringComparison.OrdinalIgnoreCase));
            var unsmry = fresh.FirstOrDefault(path => path.EndsWith(".UNSMRY", StringComparison.OrdinalIgnoreCase));
            var stepSummaries = fresh.Where(EclipseRunRules.IsStepSummaryOutput).OrderBy(path => path, StringComparer.OrdinalIgnoreCase).ToArray();
            var rsm = fresh.FirstOrDefault(path => path.EndsWith(".RSM", StringComparison.OrdinalIgnoreCase));
            var egrid = fresh.FirstOrDefault(path => path.EndsWith(".EGRID", StringComparison.OrdinalIgnoreCase));
            var caseName = Path.GetFileNameWithoutExtension(workModel);
            var formattedSmspec = fresh.SingleOrDefault(path => Path.GetFileName(path).Equals(caseName + ".FSMSPEC", StringComparison.OrdinalIgnoreCase));
            var formattedUnsmry = fresh.SingleOrDefault(path => Path.GetFileName(path).Equals(caseName + ".FUNSMRY", StringComparison.OrdinalIgnoreCase));
            EclipseCompositionalSummary? compositionalSummary = null;
            if (simulation is not null)
            {
                if (smspec is null) throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_SUMMARY_MISSING", "A complete native binary Summary specification is required for this compositional card.");
                try
                {
                    var inputMetadata = compositionalState is null ? EclipseDataInspector.InspectPackage(workPackage.Files, Path.GetFileName(workModel), workPackage.Root) : null;
                    var dimensions = compositionalState?.Catalog.Dimensions ?? inputMetadata?.Dimensions ?? throw new InvalidDataException("Verified model dimensions are required.");
                    compositionalSummary = EclipseCompositionalSummaryParser.ParseForModel(smspec, unsmry is null ? stepSummaries : [unsmry],
                        simulation.SummaryProgramIdentifier, compositionalState?.Catalog.UnitSystem ?? inputMetadata!.UnitSystem!, dimensions.Nx, dimensions.Ny, dimensions.Nz);
                    if (compositionalState is not null && (compositionalSummary.StartDate != compositionalState.Catalog.StartDate
                        || compositionalSummary.Series.Any(s => s.Points.Count == 0 || s.Points[0].TimeDays <= compositionalState.Catalog.TimeDays
                            || s.Points.Any(p => p.TimeDays > compositionalState.Catalog.EndTimeDays)
                            || !partial && s.Points[^1].TimeDays != compositionalState.Catalog.EndTimeDays)))
                        throw new InvalidDataException("Forecast actual output must preserve the original history origin and cover its qualified future interval.");
                }
                catch (InvalidDataException exception) { throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_SUMMARY_INVALID", exception.Message); }
            }
            var summaryRead = simulation is null ? await ReadSummaryWithSourceAsync(smspec, unsmry, rsm, stepSummaries, formattedSmspec, formattedUnsmry) : new SummaryRead(null, false);
            var summarySeries = summaryRead.Series;
            if (verifiedFuture is not null)
            {
                if ((!summaryRead.IsFormatted && !summaryRead.IsBinary) || summarySeries is null || !EclipseParsers.IsPublishableSummary(summarySeries)
                    || ReadSummaryOrigin(summaryRead.IsFormatted, formattedSmspec, smspec) != verifiedFuture.StartDate)
                    throw new EclipseDeckPackageException("ECLIPSE_FORECAST_TIME_ORIGIN_INVALID", "Forecast Summary must preserve the verified history START date.");
                var origin = DateOnly.ParseExact(verifiedFuture.StartDate, "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture);
                var times = verifiedFuture.FutureDates.Select(d => (double)(DateOnly.ParseExact(d, "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture).DayNumber - origin.DayNumber)).ToArray();
                if (summarySeries.Any(s => !s.Points.Select(p => p.TimeDays).SequenceEqual(times)))
                    throw new EclipseDeckPackageException("ECLIPSE_FORECAST_REPORT_TIMES_INVALID", "Forecast actual output times must match every declared future report date; no rebasing or interpolation is permitted.");
            }
            object? summary = simulation is null ? CreateSummary(summarySeries) : new { startDate = compositionalSummary!.StartDate, timeVectorIndex = compositionalSummary.TimeVectorIndex,
                series = compositionalSummary.Series.Select(s => new { vectorIndex = s.VectorIndex, keyword = s.Keyword, objectName = s.ObjectName, number = s.Number, unit = s.Unit, points = s.Points }) };
            var outputFiles = await artifacts.DescribeEclipseOutputsAsync(fresh, CancellationToken.None);
            var fieldIndex = fresh.Where(EclipseRunRules.IsFieldBinaryOutput)
                .Select(EclipseBinaryFieldIndexParser.TryParse)
                .Where(index => index is not null)
                .Cast<EclipseBinaryFieldIndex>()
                .ToArray();
            foreach (var binaryOutput in fresh.Where(EclipseRunRules.IsApprovedBinaryOutput))
                descriptors.Add(await artifacts.CopyEclipseBinaryOutputAsync(dirs.Output, binaryOutput, CancellationToken.None));
            foreach (var formattedGrid in fresh.Where(EclipseRunRules.IsFormattedGridOutput))
                descriptors.Add(await artifacts.CopyEclipseFormattedGridOutputAsync(dirs.Output, formattedGrid, CancellationToken.None));
            if (collectCompositionalSave && !partial)
            {
                try
                {
                    var saved = EclipseCompositionalSaveCatalogReader.Read(workPackage, fresh, "2024.1", verifiedSource: compositionalHistory);
                    var saveArtifacts = new List<ArtifactDescriptor>();
                    foreach (var file in saved.Files)
                    {
                        var path = fresh.Single(p => Path.GetFileName(p) == file.Name);
                        saveArtifacts.Add(await artifacts.CopyEclipseCompositionalSaveOutputAsync(dirs.Output, path, file, cancellation));
                    }
                    saveArtifacts.Add(await artifacts.WriteJsonAsync(dirs.Output, "eclipse-compositional-save-catalog.json", saved, cancellation));
                    descriptors.AddRange(saveArtifacts);
                }
                catch (InvalidDataException)
                {
                    registry.Report(request.RunId, "COLLECTING", "E300 SAVE files do not match the verified serial history subset; no selectable compositional checkpoint was published.");
                }
            }
            var restartOutput = fresh.SingleOrDefault(path => Path.GetFileName(path).Equals(caseName + (summaryRead.IsFormatted ? ".FUNRST" : ".UNRST"), StringComparison.OrdinalIgnoreCase));
            if (simulation is null && !partial && request.Parameters.ValueKind == JsonValueKind.Null && restartOutput is not null)
            {
                try
                {
                    var catalog = EclipseRestartReader.Read(restartOutput);
                    var described = EclipseForecastSourceReader.Describe(workPackage, catalog);
                    if ((!summaryRead.IsFormatted && !summaryRead.IsBinary) || ReadSummaryOrigin(summaryRead.IsFormatted, formattedSmspec, smspec) != catalog.StartDate
                        || summarySeries is null || summarySeries.Any(s => !s.Points.Select(p => p.TimeDays).SequenceEqual(catalog.Reports.Select(r => r.TimeDays))))
                        throw new InvalidDataException("History Summary and restart report clocks are not identical.");
                    descriptors.Add(await artifacts.WriteJsonAsync(dirs.Output, "eclipse-restart-catalog.json", described, cancellation));
                }
                catch (Exception exception) when (exception is InvalidDataException or EclipseDeckPackageException or DecoderFallbackException)
                {
                    registry.Report(request.RunId, "COLLECTING", "Restart source is outside the verified E100 unified global-grid scope; no selectable restart catalog was published.");
                }
            }
            if (summaryRead.IsFormatted && formattedUnsmry is not null)
                descriptors.Add(await artifacts.CopyEclipseFormattedSummaryOutputAsync(dirs.Output, formattedUnsmry, CancellationToken.None));
            var envelope = JsonSerializer.SerializeToElement(CreateResultEnvelope(deck, counts, summary, outputFiles,
                egrid is null ? null : EclipseGridMetadataParser.TryParse(egrid), fieldIndex, diagnosticMessages));
            if (simulation is not null)
            {
                var compositionalEnvelope = System.Text.Json.Nodes.JsonNode.Parse(envelope.GetRawText())!.AsObject();
                compositionalEnvelope["schemaVersion"] = simulation.ModelType == "THERMAL" ? "eclipse-summary-result/3" : "eclipse-summary-result/2";
                compositionalEnvelope["modelKind"] = "eclipse_300";
                compositionalEnvelope["simulation"] = System.Text.Json.Nodes.JsonNode.Parse(simulation.Metadata.GetRawText());
                result = JsonSerializer.SerializeToElement(compositionalEnvelope);
                if (compositionalState is null)
                    descriptors.Add(simulation.ModelType == "THERMAL"
                        ? await artifacts.WriteEclipseThermalPackageAsync(dirs.Output, workPackage, cancellation)
                        : await artifacts.WriteEclipseCompositionalPackageAsync(dirs.Output, workPackage, cancellation));
            }
            else result = envelope;
            descriptors.Add(await artifacts.WriteJsonElementAsync(dirs.Output, "normalized-result.json", result.Value));
            state = partial ? "PARTIAL_SUCCEEDED" : "SUCCEEDED";
            message = partial
                ? $"ECLIPSE {solver} completed with {counts.Problems} solver problem(s); results were published with diagnostics."
                : $"ECLIPSE {solver} completed successfully.";
        }
        catch (EclipseDeckPackageException exception) { error = new("MODEL", exception.Code, exception.Message, false); }
        catch (StorageException exception) { error = new("STORAGE", exception.Code, exception.Message, false); }
        catch (Exception exception) { exceptionDetail = $"{exception.GetType().Name}: {exception.Message}"; error = new("EXECUTION", "ECLIPSE_RUN_FAILED", "The Worker could not complete the ECLIPSE run.", false); }
        finally
        {
            if (dirs is not null)
            {
                var logLines = new List<string> { state, error?.Code ?? "SUCCESS" };
                if (exceptionDetail is not null) logLines.Add(exceptionDetail);
                descriptors.Add(await artifacts.WriteLogAsync(dirs.Output, logLines));
                descriptors.Add(await artifacts.WriteManifestAsync(dirs.Output, request.RunId, descriptors));
            }
            var cleanup = CreateCleanup(
                treeConfirmed,
                dirs is null || StorageResolver.TryDeleteDirectory(dirs.Input),
                dirs is null || StorageResolver.TryDeleteDirectory(dirs.Work),
                killUsed,
                cleanupFailed);
            PtkRunService.PublishCompletionAndRelease(registry, request.RunId, state, result, error, descriptors, cleanup, message, lease, cleanupFailed);
        }
    }

    private WorkerError? Validate(RunExecuteRequest request)
    {
        if (request.RunId <= 0) return WorkerApiError.Request("INVALID_RUN_ID", "runId must be positive.");
        if (!EclipseRunRules.IsDataStorageKey(request.ModelStorageKey)) return WorkerApiError.Request("INVALID_ECLIPSE_DATA_FILE", "modelStorageKey must reference a .DATA file.");
        if (string.IsNullOrWhiteSpace(request.ExpectedModelSha256) || request.ExpectedModelSha256.Length != 64 || request.ExpectedModelSha256.Any(value => value is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            return WorkerApiError.Request("INVALID_EXPECTED_SHA256", "expectedModelSha256 must be lowercase SHA-256.");
        if (request.RunTask != "eclipse") return WorkerApiError.Request("INVALID_RUN_TASK", "runTask must be eclipse.");
        if (request.Study is not null) return WorkerApiError.Request("INVALID_STUDY", "study must be null for ECLIPSE.");
        if (request.Parameters.ValueKind != JsonValueKind.Null &&
            !EclipseMultiLiveOilPvtParameters.TryParse(request.Parameters, out _) &&
            !EclipseMultiWetGasPvtParameters.TryParse(request.Parameters, out _) &&
            !EclipseMultiWaterPvtParameters.TryParse(request.Parameters, out _) &&
            !EclipseCompositionalReinjectionParameters.TryParse(request.Parameters, out _) &&
            !EclipseCompositionalGasSalesParameters.TryParse(request.Parameters, out _) &&
            !EclipseCompositionalInjectionPressureParameters.TryParse(request.Parameters, out _) &&
            !EclipseCompositionalFluidPropertyParameters.TryParse(request.Parameters, out _) &&
            !EclipseCompositionalPressureParameters.TryParse(request.Parameters, out _) &&
            !EclipseCompositionalProductionParameters.TryParse(request.Parameters, out _) &&
            !EclipseThermalRockParameters.TryParse(request.Parameters, out _) &&
            !EclipseThermalPlanParameters.TryParse(request.Parameters, out _) &&
            !EclipseThermalInjectionParameters.TryParse(request.Parameters, out _) &&
            !EclipseMultiPvtParameters.TryParse(request.Parameters, out _) &&
            !EclipseMultiSatFamilyIIParameters.TryParse(request.Parameters, out _) &&
            !EclipseMultiSatRelpermParameters.TryParse(request.Parameters, out _) &&
            !EclipseCoupledRelpermParameters.TryParse(request.Parameters, out _) &&
            !EclipseRelpermParameters.TryParse(request.Parameters, out _) &&
            !EclipseGasPvtParameters.TryParse(request.Parameters, out _) &&
            !EclipseWetGasPvtParameters.TryParse(request.Parameters, out _) &&
            !EclipseLiveOilPvtParameters.TryParse(request.Parameters, out _) &&
            !EclipseOilPvtParameters.TryParse(request.Parameters, out _) &&
            !EclipsePvtwParameters.TryParse(request.Parameters, out _) &&
            !EclipseNewCompletionParameters.TryParse(request.Parameters, out _) &&
            !EclipseNewWellParameters.TryParse(request.Parameters, out _) &&
            !EclipseCompletionGeometryParameters.TryParse(request.Parameters, out _) &&
            !EclipseGridRegionParameters.TryParse(request.Parameters, out _) &&
            !EclipseGridFieldParameters.TryParse(request.Parameters, out _) &&
            !EclipseCompletionFactorParameters.TryParse(request.Parameters, out _) &&
            !EclipseCompletionParameters.TryParse(request.Parameters, out _) &&
            !EclipseProductionTargetParameters.TryParse(request.Parameters, out _) &&
            !EclipseGroupGasInjectionParameters.TryParse(request.Parameters, out _) &&
            !EclipseGroupInjectionParameters.TryParse(request.Parameters, out _) &&
            !EclipseControlPlanParameters.TryParse(request.Parameters, out _) &&
            !EclipsePackageControlPlanParameters.TryParse(request.Parameters, out _) &&
            !EclipseWaterInjectionPlanParameters.TryParse(request.Parameters, out _) &&
            !EclipseGroupStructureParameters.TryParse(request.Parameters, out _) &&
            !EclipseAdvancedInjectionParameters.TryParse(request.Parameters, out _) &&
            !EclipseGroupWaterProductionParameters.TryParse(request.Parameters, out _) &&
            !EclipseGroupRateParameters.TryParse(request.Parameters, out _) &&
            !EclipseGroupProductionParameters.TryParse(request.Parameters, out _) &&
            !EclipseTstepParameters.TryParse(request.Parameters, out _) &&
            !EclipseNewDatesParameters.TryParse(request.Parameters, out _) &&
            !EclipseThpControlParameters.TryParse(request.Parameters, out _) &&
            !EclipseProductionControlParameters.TryParse(request.Parameters, out _) &&
            !EclipsePressureControlParameters.TryParse(request.Parameters, out _) &&
            !EclipseScheduleParameters.TryParse(request.Parameters, out _) &&
            !EclipseHistoryForecastParameters.TryParse(request.Parameters, out _) &&
            !EclipseRestartForecastParameters.TryParse(request.Parameters, out _) &&
            !EclipseCompositionalForecastParameters.TryParse(request.Parameters, out _))
            return WorkerApiError.Request("INVALID_ECLIPSE_PARAMETERS", "ECLIPSE parameters must be null or a valid controlled Schedule, COMPDAT, or connection-factor scenario.");
        if (request.TimeoutSeconds is <= 0 || request.TimeoutSeconds > options.EclipseMaxRunTimeoutSeconds) return WorkerApiError.Request("INVALID_TIMEOUT", "timeoutSeconds exceeds the ECLIPSE limit.");
        if (request.ExpectedPackageFiles is not null && !EclipsePackageIntegrity.IsValidManifest(request.ExpectedPackageFiles))
            return WorkerApiError.Request("INVALID_PACKAGE_MANIFEST", "expectedPackageFiles must contain unique safe relative paths and lowercase SHA-256 values.");
        return null;
    }
    private static string ResolvePackageFile(string root, string relativePath)
    {
        if (string.IsNullOrWhiteSpace(relativePath) || relativePath.Contains('\\') || relativePath.StartsWith('/') || relativePath.Contains(':') ||
            relativePath.Split('/').Any(part => part is "" or "." or "..") || !relativePath.EndsWith(".DATA", StringComparison.OrdinalIgnoreCase))
            throw new EclipseDeckPackageException("ECLIPSE_FORECAST_DATA_INVALID", "The forecast DATA path is invalid.");
        var fullRoot = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var path = Path.GetFullPath(Path.Combine(fullRoot, relativePath.Replace('/', Path.DirectorySeparatorChar)));
        if (!path.StartsWith(fullRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) || !File.Exists(path))
            throw new EclipseDeckPackageException("ECLIPSE_FORECAST_DATA_MISSING", "The forecast DATA file is not present in the isolated package.");
        return path;
    }
    private static Dictionary<string, (long Size, DateTime LastWrite)> Snapshot(string root) => Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories).ToDictionary(path => path, path => (new FileInfo(path).Length, File.GetLastWriteTimeUtc(path)), StringComparer.OrdinalIgnoreCase);
    internal static IEnumerable<string> Fresh(string root, Dictionary<string, (long Size, DateTime LastWrite)> before, bool compositionalSave = false,
        IReadOnlySet<string>? selectedSaveNames = null)
        => Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories).Where(path => (Output().IsMatch(Path.GetFileName(path))
            || compositionalSave && (selectedSaveNames is null || selectedSaveNames.Contains(Path.GetFileName(path)))
                && Regex.IsMatch(Path.GetFileName(path), @"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.(?:V|X)[0-9]{4}$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
            && EclipseRunRules.IsFresh(before.TryGetValue(path, out var prior) ? prior : null, new FileInfo(path).Length, File.GetLastWriteTimeUtc(path)));
    internal static async Task<IReadOnlyList<EclipseSummarySeries>?> ReadRsmAsync(string? path, Func<string, IReadOnlyList<EclipseSummarySeries>>? parser = null)
    {
        if (path is null) return null;
        try { return (parser ?? EclipseParsers.ParseRsm)(await File.ReadAllTextAsync(path)); }
        catch { return null; }
    }
    internal static async Task<IReadOnlyList<EclipseSummarySeries>?> ReadSummaryAsync(string? smspec, string? unsmry, string? rsm,
        IEnumerable<string>? stepSummaries = null, string? formattedSmspec = null, string? formattedUnsmry = null)
        => (await ReadSummaryWithSourceAsync(smspec, unsmry, rsm, stepSummaries, formattedSmspec, formattedUnsmry)).Series;

    private static string ReadSummaryOrigin(bool formatted, string? formattedPath, string? binaryPath) => formatted
        ? EclipseFormattedSummaryParser.ReadStartDate(formattedPath ?? throw new InvalidDataException("Missing formatted Summary specification."))
        : EclipseBinarySummaryParser.ReadStartDate(binaryPath ?? throw new InvalidDataException("Missing binary Summary specification."));

    private sealed record SummaryRead(IReadOnlyList<EclipseSummarySeries>? Series, bool IsFormatted, bool IsBinary = false);
    private static async Task<SummaryRead> ReadSummaryWithSourceAsync(string? smspec, string? unsmry, string? rsm,
        IEnumerable<string>? stepSummaries, string? formattedSmspec, string? formattedUnsmry)
    {
        var binaryCandidates = new List<IReadOnlyList<string>>();
        if (!string.IsNullOrWhiteSpace(unsmry)) binaryCandidates.Add([unsmry]);
        var splitSummaries = (stepSummaries ?? [])
            .Where(path => !string.IsNullOrWhiteSpace(path) && File.Exists(path))
            .Where(path => !string.Equals(path, unsmry, StringComparison.OrdinalIgnoreCase))
            .OrderBy(path => path, StringComparer.OrdinalIgnoreCase)
            .ToArray();
        if (splitSummaries.Length > 0) binaryCandidates.Add(splitSummaries);
        if (smspec is not null)
        {
            foreach (var summaryPaths in binaryCandidates)
            {
                try
                {
                    var binary = await Task.Run(() => EclipseBinarySummaryParser.Parse(smspec, summaryPaths));
                    if (EclipseParsers.IsPublishableSummary(binary)) return new(binary, false, true);
                }
                catch { /* Try another fresh Summary representation before the text RSM fallback. */ }
            }
        }
        if (formattedSmspec is not null && formattedUnsmry is not null)
        {
            try
            {
                var formatted = await Task.Run(() => EclipseFormattedSummaryParser.Parse(formattedSmspec, formattedUnsmry));
                if (EclipseParsers.IsPublishableSummary(formatted)) return new(formatted, true);
            }
            catch (Exception exception) when (exception is IOException or InvalidDataException or UnauthorizedAccessException or DecoderFallbackException)
            { /* A malformed representation cannot become a curve; the independent RSM fallback remains available. */ }
        }
        return new(await ReadRsmAsync(rsm), false);
    }
    internal static object? CreateSummary(IReadOnlyList<EclipseSummarySeries>? series) =>
        EclipseParsers.IsPublishableSummary(series) ? new { series } : null;
    internal static object CreateResultEnvelope(string deck, EclipseEndCounts counts, object? summary, IEnumerable<EclipseOutputMetadata> outputFiles) =>
        CreateResultEnvelope(deck, counts, summary, outputFiles, null);
    internal static object CreateResultEnvelope(string deck, EclipseEndCounts counts, object? summary,
        IEnumerable<EclipseOutputMetadata> outputFiles, EclipseGridMetadata? grid) =>
        CreateResultEnvelope(deck, counts, summary, outputFiles, grid, []);
    internal static object CreateResultEnvelope(string deck, EclipseEndCounts counts, object? summary,
        IEnumerable<EclipseOutputMetadata> outputFiles, EclipseGridMetadata? grid, IEnumerable<EclipseBinaryFieldIndex> fieldIndex) =>
        CreateResultEnvelope(deck, counts, summary, outputFiles, grid, fieldIndex, []);
    internal static object CreateResultEnvelope(string deck, EclipseEndCounts counts, object? summary,
        IEnumerable<EclipseOutputMetadata> outputFiles, EclipseGridMetadata? grid, IEnumerable<EclipseBinaryFieldIndex> fieldIndex,
        IEnumerable<EclipseDiagnosticMessage> messages) =>
        new { schemaVersion = "eclipse-summary-result/1", modelKind = "eclipse_100", runTask = "eclipse",
            resultContract = counts.Problems > 0 ? "VALID_PARTIAL" : "VALID_FULL", caseName = deck, eclEnd = counts, summary,
            grid = grid is null ? null : new { fileName = grid.FileName, nx = grid.Nx, ny = grid.Ny, nz = grid.Nz, activeCells = grid.ActiveCells },
            fieldIndex = fieldIndex.Any() ? new
            {
                schemaVersion = "eclipse-binary-field-index/1",
                files = fieldIndex.Select(file => new
                {
                    name = file.FileName,
                    sizeBytes = file.FileSize,
                    byteOrder = file.ByteOrder,
                    fields = file.Sections.Select(CreateFieldIndexEntry)
                })
            } : null,
            outputFiles = outputFiles.Select(file => new { name = file.Filename, sizeBytes = file.SizeBytes, sha256 = file.Sha256 }),
            messages = messages.ToArray() };
    private static object CreateFieldIndexEntry(EclipseBinaryFieldSection section) => section.TimeStep.HasValue
        ? new
        {
            keyword = section.Keyword,
            dataType = section.DataType,
            count = section.Count,
            elementSize = section.ElementSize,
            dataBytes = section.DataBytes,
            timeStep = section.TimeStep.Value,
            segments = section.Segments.Select(segment => new { offset = segment.Offset, length = segment.Length })
        }
        : new
        {
            keyword = section.Keyword,
            dataType = section.DataType,
            count = section.Count,
            elementSize = section.ElementSize,
            dataBytes = section.DataBytes,
            segments = section.Segments.Select(segment => new { offset = segment.Offset, length = segment.Length })
        };
    internal static RunCleanup CreateCleanup(bool treeConfirmed, bool inputDeleted, bool workDirectoryDeleted, bool killUsed, bool cleanupFailed) =>
        new(treeConfirmed, inputDeleted, workDirectoryDeleted, killUsed,
            !treeConfirmed ? "ECLIPSE process-tree exit is unconfirmed."
            : cleanupFailed ? "ECLIPSE cleanup failed or could not be confirmed."
            : "ECLIPSE cleanup completed.");
    [GeneratedRegex("^.+\\.(ECLEND|MSG|PRT|RSM|SMSPEC|UNSMRY|FSMSPEC|FUNSMRY|EGRID|INIT|FEGRID|FINIT|UNRST|FUNRST|S\\d{4,5})$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)] private static partial Regex Output();
    public void Dispose() => submissions.Dispose();
}
