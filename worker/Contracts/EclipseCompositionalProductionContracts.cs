namespace Grdp.SoftwareIntegration.Worker.Contracts;

public sealed record EclipseCompositionalProductionRecord(string Well, int LineNumber, string ControlMode, string Unit,
    double OriginalGasRate, double BhpLimit, int BhpLineNumber);
public sealed record EclipseCompositionalProductionContext(string SchemaVersion, string SourceFile, string SourceSha256,
    long SourceSizeBytes, int Solver, int ComponentCount, IReadOnlyList<string> ComponentNames, string EquationOfState,
    IReadOnlyList<EclipseCompositionalProductionRecord> Records);
