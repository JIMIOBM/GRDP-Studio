namespace Grdp.SoftwareIntegration.Worker.Contracts;

public sealed record EclipseThermalRockRecord(string Field, int LineNumber, string Unit, double OriginalValue);
public sealed record EclipseThermalRockContext(string SchemaVersion, string SourceFile, string SourceSha256,
    long SourceSizeBytes, int CellCount, int ActiveCellCount, IReadOnlyList<EclipseThermalRockRecord> Records);
