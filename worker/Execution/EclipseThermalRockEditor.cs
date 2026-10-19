using System.Globalization;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseThermalRockEditor
{
    internal static bool Matches(EclipseThermalRockReader.Snapshot? s,EclipseThermalRockParameters p)=>s is not null
        &&s.Context.SourceFile==p.SourceFile&&s.Context.SourceSha256==p.SourceSha256&&s.Context.CellCount==p.CellCount
        &&EclipseThermalRockParameters.Value(p.Field,p.TargetValue)&&p.TargetValue!=p.OriginalValue
        &&s.Targets.Count(t=>t.Record.Field==p.Field&&t.Record.LineNumber==p.LineNumber&&t.Record.Unit==p.Unit&&t.Record.OriginalValue==p.OriginalValue)==1;
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseThermalRockParameters p,CancellationToken cancellation)
    {
        var s=EclipseThermalRockReader.Read(package);
        if(!Matches(s,p))throw new EclipseDeckPackageException("ECLIPSE_THERMAL_ROCK_MISMATCH","The thermal property requires the exact complete original source, physical row, cell count, unit and value; no input changed.");
        var target=s!.Targets.Single(t=>t.Record.Field==p.Field);var text=new UTF8Encoding(false,true).GetString(s.SourceBytes);
        var original=text.Substring(target.Start,target.Length);var value=p.TargetValue.ToString("R",CultureInfo.InvariantCulture);
        if(original.Contains('.')&&original.IndexOfAny(['e','E'])<0&&value.IndexOfAny(['e','E'])<0)
        {
            var digits=original.Length-original.IndexOf('.')-1;
            if(digits is >0 and <=16){if(!value.Contains('.'))value+=".";var present=value.Length-value.IndexOf('.')-1;if(present<digits)value+=new string('0',digits-present);}
        }
        await File.WriteAllBytesAsync(package.MainFile,Encoding.UTF8.GetBytes(text[..target.Start]+value+text[(target.Start+target.Length)..]),cancellation);
        return $"Applied {p.Field} to {s.Context.ActiveCellCount} original active cells; original array count, inactive zero overrides, other thermal properties and well controls retained.";
    }
}
