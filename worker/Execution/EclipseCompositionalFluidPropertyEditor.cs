using System.Globalization;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompositionalFluidPropertyEditor
{
    internal static bool Matches(EclipseCompositionalFluidPropertyReader.Snapshot? s, EclipseCompositionalFluidPropertyParameters p) => s is not null &&
        p.SourceFile == s.Context.SourceFile && p.SourceSha256 == s.Context.SourceSha256 &&
        p.Unit == EclipseCompositionalFluidPropertyParameters.PropertyUnit(p.Field) &&
        EclipseCompositionalFluidPropertyParameters.PropertyValue(p.Field, p.TargetValue) && p.TargetValue != p.OriginalValue &&
        s.Context.Records.Count(r => r.ComponentIndex == p.ComponentIndex && r.ComponentName == p.ComponentName && r.Field == p.Field &&
            r.LineNumber == p.LineNumber && r.Unit == p.Unit && r.OriginalValue == p.OriginalValue) == 1;
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseCompositionalFluidPropertyParameters p, CancellationToken cancellation)
    {
        var s = EclipseCompositionalFluidPropertyReader.Read(package, p.Field);
        if (!Matches(s, p)) throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_FLUID_PROPERTY_MISMATCH",
            "The component critical property must bind the complete source SHA, property row, component index and name, physical unit and original value; no input changed.");
        var target = s!.Targets.Single(t => t.ComponentIndex == p.ComponentIndex);
        var text = new UTF8Encoding(false, true).GetString(s.SourceBytes);
        var changed = text[..target.Start] + p.TargetValue.ToString("R", CultureInfo.InvariantCulture) + text[(target.Start + target.Length)..];
        await File.WriteAllBytesAsync(package.MainFile, Encoding.UTF8.GetBytes(changed), cancellation);
        return $"Applied one explicit component {p.Field} value; all other component properties, compositions, controls, geometry and time schedule retained.";
    }
}
