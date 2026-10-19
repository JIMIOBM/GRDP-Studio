using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompositionalInjectionPressureEditor
{
    private static Match Literal(EclipseCompositionalReinjectionReader.Snapshot s)
    {
        var text = new UTF8Encoding(false, true).GetString(s.SourceBytes);
        var token = text.Substring(s.InjectionPressureStart, s.InjectionPressureLength);
        return Regex.Match(token, """^(?<prefix>['"]?(?:1\*)?)(?<value>[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[Ee][+-]?[0-9]+)?)(?<suffix>['"]?)$""", RegexOptions.CultureInvariant);
    }
    internal static bool Matches(EclipseCompositionalReinjectionReader.Snapshot? s, EclipseCompositionalInjectionPressureParameters p) => s is not null &&
        s.Context.SourceFile == p.SourceFile && s.Context.SourceSha256 == p.SourceSha256 && p.Unit == "psia" &&
        double.IsFinite(p.TargetBhpUpperLimit) && p.TargetBhpUpperLimit is > 0 and <= 100_000_000 && p.TargetBhpUpperLimit != p.OriginalBhpUpperLimit && Literal(s).Success &&
        s.Context.Records.Count(r => r.InjectionWell == p.Well && r.InjectionLineNumber == p.LineNumber && r.InjectionBhpUpperLimit == p.OriginalBhpUpperLimit) == 1;
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseCompositionalInjectionPressureParameters p, CancellationToken cancellation)
    {
        var s = EclipseCompositionalReinjectionReader.Read(package);
        if (!Matches(s, p)) throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_INJECTION_PRESSURE_MISMATCH",
            "The E300 injector BHP upper limit must bind the exact source SHA, GAS injection well, WCONINJE row, unit and original value; no input changed.");
        var text = new UTF8Encoding(false, true).GetString(s!.SourceBytes);
        var value = Literal(s).Groups["value"];
        var start = s.InjectionPressureStart + value.Index;
        var changed = text[..start] + p.TargetBhpUpperLimit.ToString("R", CultureInfo.InvariantCulture) + text[(start + value.Length)..];
        await File.WriteAllBytesAsync(package.MainFile, Encoding.UTF8.GetBytes(changed), cancellation);
        return "Applied one initial E300 injector BHP upper limit; original reinjection fraction, sales, vapor source, producer pressure limit, separator changes and shut schedule retained.";
    }
}
