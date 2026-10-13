using System.Text.Json;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record EclipseSimulationModel(int Solver, string ModelType, int SummaryProgramIdentifier, JsonElement Metadata);

public static class EclipseSimulationModelReader
{
    public static EclipseSimulationModel? Read(EclipseDeckPackage package)
    {
        var thermal = EclipseThermalReader.Read(package);
        if (thermal is not null) return new(300, "THERMAL", 500, JsonSerializer.SerializeToElement(thermal));
        var compositional = EclipseSimulationReader.Read(package);
        return compositional is null ? null : new(300, "COMPOSITIONAL", 300, JsonSerializer.SerializeToElement(compositional));
    }
}
