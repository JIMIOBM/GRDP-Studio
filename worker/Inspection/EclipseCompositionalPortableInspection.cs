using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Inspection;

internal static class EclipseCompositionalPortableInspection
{
    internal static EclipseDataInspection Create(EclipseDeckPackage package, EclipseCompositionalBoundState state)
    {
        var input = Path.Combine(package.Root, "history/input");
        var historical = new EclipseDeckPackageResolver().Resolve(Path.Combine(input, state.Catalog.SourceFile), input);
        var result = EclipseDataInspector.InspectPackage(historical.Files, Path.GetFileName(package.MainFile), historical.Root);
        var data = File.ReadAllBytes(package.MainFile);
        return result with {
            SchemaVersion = "eclipse-data-inspection/18", Simulation = EclipseCompositionalSaveState.Simulation(state).Metadata,
            ScheduleTimeline = [],
            ScheduleMetadata = null, VfpTables = null, GroupControls = null, InjectionGroupControls = null, GasInjectionGroupControls = null,
            GroupRateControls = null, WaterProductionGroupControls = null, AdvancedInjectionGroupControls = null, GroupStructureControls = null, ProductionControlPlan = null,
            CompositionalProductionContext = null, CompositionalReinjectionContext = null, CompositionalFluidPropertyContext = null,
            ThermalInjectionContext = null,
            CompositionalRestartContext = new("eclipse-compositional-portable-restart-context/1", Path.GetFileName(package.MainFile),
                Convert.ToHexStringLower(SHA256.HashData(data)), data.Length, "history/input/" + state.Catalog.SourceFile,
                state.TargetGasRate, state.Catalog)
        };
    }
}
