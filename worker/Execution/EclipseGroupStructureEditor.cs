using System.Globalization;
using System.Text;
namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseGroupStructureEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseGroupStructureParameters p,CancellationToken cancellation)
    {
        if(package.Files.Count!=1 || Path.GetFileName(package.MainFile)!=p.SourceFile || !double.IsFinite(p.TargetValue) || p.TargetValue<=0 || p.TargetValue>100_000_000 || p.TargetValue==p.OriginalValue || p.Keyword=="GEFAC" && p.TargetValue>1) Fail();
        var encoding=new UTF8Encoding(false,true);var bytes=await File.ReadAllBytesAsync(package.MainFile,cancellation);var text=encoding.GetString(bytes);
        var matches=EclipseGroupStructureReader.Read(text,p.SourceFile).Where(t=>t.Control.Keyword==p.Keyword && t.Control.ObjectName==p.ObjectName).ToArray();if(matches.Length!=1)Fail();
        var target=matches[0];var c=target.Control;
        if(c.SourceFile!=p.SourceFile || c.LineNumber!=p.LineNumber || c.Unit!=p.Unit || c.OriginalValue!=p.OriginalValue || c.ParentGroup!=p.ParentGroup
            || !c.Leaves.SelectMany(l=>l.Members).Select(m=>m.Well).SequenceEqual(p.Members) || !encoding.GetBytes(text).SequenceEqual(bytes))Fail();
        var updated=text[..target.Start]+p.TargetValue.ToString("G17",CultureInfo.InvariantCulture)+text[(target.Start+target.Length)..];cancellation.ThrowIfCancellationRequested();
        await File.WriteAllBytesAsync(package.MainFile,encoding.GetBytes(updated),cancellation);
        return $"Applied {p.Keyword} {p.ObjectName} {p.OriginalValue:G17}->{p.TargetValue:G17} {p.Unit}; parent {p.ParentGroup}, complete tree and all well constraints preserved.";
    }
    private static void Fail()=>throw new EclipseDeckPackageException("ECLIPSE_GROUP_STRUCTURE_MISMATCH","Group structure edits require a unique initial ORAT parent with literal FLD production leaves, complete OPEN/GRUP wells, immutable source, ordered membership and original value, and supported fixed guide or leaf downtime semantics.");
}
