using System.Globalization;
using System.Text;
namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseControlPlanEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseControlPlanParameters p,CancellationToken cancellation)
    {
        if(package.Files.Count!=1 || p.Edits.Count is <1 or >24 || p.Edits.Any(e=>Path.GetFileName(package.MainFile)!=e.SourceFile || !double.IsFinite(e.TargetOilRate) || e.TargetOilRate<=0 || e.TargetOilRate>100_000_000 || e.TargetOilRate==e.OriginalOilRate)
            || p.Edits.Select(e=>e.LineNumber).Distinct().Count()!=p.Edits.Count || !p.Edits.Select(e=>e.LineNumber).SequenceEqual(p.Edits.Select(e=>e.LineNumber).Order()))Fail();
        var utf8=new UTF8Encoding(false,true);var bytes=await File.ReadAllBytesAsync(package.MainFile,cancellation);var text=utf8.GetString(bytes);
        var snapshot=EclipseControlPlanReader.Read(text,Path.GetFileName(package.MainFile));if(snapshot is null || !utf8.GetBytes(text).SequenceEqual(bytes))Fail();
        var targets=new List<(EclipseControlPlanReader.Target Target,EclipseControlPlanEdit Edit)>();
        foreach(var edit in p.Edits)
        {
            var matches=snapshot!.Targets.Where(t=>t.Record.Well==edit.Well && t.Record.SourceFile==edit.SourceFile && t.Record.LineNumber==edit.LineNumber && t.Record.Date==edit.Date
                && t.Record.TimeDays==edit.TimeDays && t.Record.Unit==edit.Unit && t.Record.OriginalOilRate==edit.OriginalOilRate).ToArray();
            if(matches.Length!=1)Fail();targets.Add((matches[0],edit));
        }
        foreach(var (target,edit) in targets.OrderByDescending(t=>t.Target.Start))text=text[..target.Start]+edit.TargetOilRate.ToString("G17",CultureInfo.InvariantCulture)+text[(target.Start+target.Length)..];
        cancellation.ThrowIfCancellationRequested();await File.WriteAllBytesAsync(package.MainFile,utf8.GetBytes(text),cancellation);
        return $"Applied {targets.Count} source-bound production targets; existing dates, complete override chain, unselected controls, physical inputs and BHP limits preserved.";
    }
    private static void Fail()=>throw new EclipseDeckPackageException("ECLIPSE_CONTROL_PLAN_MISMATCH","Production plans require existing literal OPEN/ORAT records, ordered dates and exact source/phase/original value bindings; no input was changed.");
}
