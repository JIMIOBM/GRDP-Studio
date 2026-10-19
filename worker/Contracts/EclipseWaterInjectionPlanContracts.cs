namespace Grdp.SoftwareIntegration.Worker.Contracts;

public sealed record EclipseWaterInjectionPlanRecord(string Well, string SourceFile, int LineNumber, string Date,
    int TimeDays, string Unit, double OriginalInjectionRate, double BhpLimit);
public sealed record EclipseWaterInjectionControlPlan(string StartDate, int EndTimeDays, IReadOnlyList<EclipseWaterInjectionPlanRecord> Records);
public sealed record EclipseWaterInjectionSourceRecord(string Well, string SourceFile, string SourceSha256, int LineNumber,
    string Date, int TimeDays, string Unit, double OriginalInjectionRate, double BhpLimit);
public sealed record EclipseWaterInjectionControlPlanContext(string SchemaVersion, string StartDate, int EndTimeDays,
    IReadOnlyList<EclipseWaterInjectionSourceRecord> Records);
