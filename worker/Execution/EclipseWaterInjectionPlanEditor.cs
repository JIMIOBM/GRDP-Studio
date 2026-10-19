using System.Globalization;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseWaterInjectionPlanEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseWaterInjectionPlanParameters parameters, CancellationToken cancellation)
    {
        var snapshot = EclipsePackageWaterInjectionPlanReader.Read(package);
        if (snapshot is null || parameters.Edits.Count is < 1 or > 24) Fail();
        var selected = new List<(EclipsePackageWaterInjectionPlanReader.Target Target, EclipseWaterInjectionPlanEdit Edit)>();
        var previous = -1;
        foreach (var edit in parameters.Edits)
        {
            if (!double.IsFinite(edit.TargetInjectionRate) || edit.TargetInjectionRate is <= 0 or > 100_000_000 || edit.TargetInjectionRate == edit.OriginalInjectionRate) Fail();
            var matches = snapshot!.Targets.Where(t => t.Record.Well == edit.Well && t.Record.SourceFile == edit.SourceFile && t.SourceSha256 == edit.SourceSha256
                && t.Record.LineNumber == edit.LineNumber && t.Record.Date == edit.Date && t.Record.TimeDays == edit.TimeDays && t.Record.Unit == edit.Unit && t.Record.OriginalInjectionRate == edit.OriginalInjectionRate).ToArray();
            if (matches.Length != 1 || matches[0].ExecutionOrder <= previous) Fail();
            previous = matches[0].ExecutionOrder; selected.Add((matches[0], edit));
        }
        var changes = new List<(string Path, byte[] Data)>();
        foreach (var group in selected.GroupBy(x => x.Target.Record.SourceFile))
        {
            var text = new UTF8Encoding(false, true).GetString(snapshot!.Sources[group.Key]);
            foreach (var (target, edit) in group.OrderByDescending(x => x.Target.Start))
                text = text[..target.Start] + edit.TargetInjectionRate.ToString("R", CultureInfo.InvariantCulture) + text[(target.Start + target.Length)..];
            changes.Add((Path.Combine(package.Root, group.Key.Replace('/', Path.DirectorySeparatorChar)), Encoding.UTF8.GetBytes(text)));
        }
        cancellation.ThrowIfCancellationRequested();
        foreach (var (path, data) in changes) await File.WriteAllBytesAsync(path, data, cancellation);
        return $"Applied {selected.Count} dated water injection targets in {changes.Count} physical files; original dates, later overrides and all unselected input retained.";
    }
    private static void Fail() => throw new EclipseDeckPackageException("ECLIPSE_WATER_INJECTION_PLAN_MISMATCH", "Dated water injection targets must match the complete INCLUDE execution order, physical source SHA, row, date, units and original value; no input changed.");
}
