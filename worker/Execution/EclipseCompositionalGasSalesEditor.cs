using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompositionalGasSalesEditor
{
    private static Match Literal(EclipseCompositionalReinjectionReader.Snapshot s)
    {
        var text = new UTF8Encoding(false, true).GetString(s.SourceBytes);
        var token = text.Substring(s.SalesStart, s.SalesLength);
        return Regex.Match(token, """^(?<prefix>['"]?(?:1\*)?)(?<value>[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[Ee][+-]?[0-9]+)?)(?<suffix>['"]?)$""", RegexOptions.CultureInvariant);
    }
    internal static bool Matches(EclipseCompositionalReinjectionReader.Snapshot? s, EclipseCompositionalGasSalesParameters p) => s is not null &&
        s.Context.SourceFile == p.SourceFile && s.Context.SourceSha256 == p.SourceSha256 && p.Unit == "Mscf/day" &&
        EclipseCompositionalGasSalesParameters.Rate(p.TargetSalesTarget) && p.TargetSalesTarget != p.OriginalSalesTarget && Literal(s).Success &&
        s.Context.Records.Count(r => r.Group == p.Group && r.Phase == p.Phase && r.SalesLineNumber == p.LineNumber && r.SalesTarget == p.OriginalSalesTarget) == 1;
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseCompositionalGasSalesParameters p, CancellationToken cancellation)
    {
        var s = EclipseCompositionalReinjectionReader.Read(package);
        if (!Matches(s, p)) throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_GAS_SALES_MISMATCH",
            "The E300 constant gas sales target must bind the exact source SHA, FIELD/GAS group, sales row, unit and original value; no input changed.");
        var text = new UTF8Encoding(false, true).GetString(s!.SourceBytes);
        var value = Literal(s).Groups["value"];
        var start = s.SalesStart + value.Index;
        var changed = text[..start] + p.TargetSalesTarget.ToString("R", CultureInfo.InvariantCulture) + text[(start + value.Length)..];
        await File.WriteAllBytesAsync(package.MainFile, Encoding.UTF8.GetBytes(changed), cancellation);
        return "Applied one initial E300 constant gas sales requirement; original reinjection fraction, vapor source, pressure limits, separator changes and shut schedule retained.";
    }
}
