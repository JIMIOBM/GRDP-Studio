namespace Grdp.SoftwareIntegration.Worker.Contracts;
public sealed record EclipseThermalInjectionRecord(string Well, int LineNumber, double OriginalSteamQuality,
    double OriginalTemperature, string TemperatureUnit, double SurfaceInjectionRate, string RateUnit, double BhpUpperLimit, int ControlLineNumber);
public sealed record EclipseThermalInjectionContext(string SchemaVersion, string SourceFile, string SourceSha256,
    long SourceSizeBytes, int Solver, int ComponentCount, IReadOnlyList<string> ComponentNames, string FluidModel,
    IReadOnlyList<EclipseThermalInjectionRecord> Records);
