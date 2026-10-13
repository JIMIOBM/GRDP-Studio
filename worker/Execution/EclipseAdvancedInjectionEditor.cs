using System.Globalization;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseAdvancedInjectionEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseAdvancedInjectionParameters p, CancellationToken cancellation)
    {
        if (package.Files.Count != 1 || Path.GetFileName(package.MainFile) != p.SourceFile || !double.IsFinite(p.TargetRate)
            || p.TargetRate <= 0 || p.TargetRate > 100_000_000 || p.TargetRate == p.OriginalTargetRate) Fail();
        var encoding = new UTF8Encoding(false, true);
        var bytes = await File.ReadAllBytesAsync(package.MainFile, cancellation);
        var text = encoding.GetString(bytes);
        var matches = EclipseAdvancedInjectionReader.Read(text, p.SourceFile).Where(t => t.Control.Group == p.Group).ToArray();
        if (matches.Length != 1) Fail();
        var record = matches[0]; var c = record.Control;
        if (c.SourceFile != p.SourceFile || c.LineNumber != p.LineNumber || c.InjectionPhase != p.InjectionPhase || c.ControlMode != p.ControlMode || c.ReferenceGroup != p.ReferenceGroup || !c.ReferenceMembers.SequenceEqual(p.ReferenceMembers) || c.Unit != p.Unit || c.OriginalTargetRate != p.OriginalTargetRate
            || !c.Members.Select(m => m.Well).SequenceEqual(p.Members) || !encoding.GetBytes(text).SequenceEqual(bytes)) Fail();
        var updated = text[..record.Start] + p.TargetRate.ToString("G17", CultureInfo.InvariantCulture) + text[(record.Start + record.Length)..];
        cancellation.ThrowIfCancellationRequested();
        await File.WriteAllBytesAsync(package.MainFile, encoding.GetBytes(updated), cancellation);
        return $"Applied GCONINJE {p.Group} {p.InjectionPhase} {p.ControlMode} {p.OriginalTargetRate:G17}->{p.TargetRate:G17} {p.Unit}; {p.Members.Count} GRUP wells and all pressure/rate limits preserved.";
    }
    private static void Fail() => throw new EclipseDeckPackageException("ECLIPSE_ADVANCED_INJECTION_MISMATCH", "Advanced group injection requires a unique initial direct group, at least two OPEN GRUP injectors, literal BHP upper limits, matching source/members/target/unit, and no dynamic, hierarchy, guide-rate or efficiency overrides.");
}
