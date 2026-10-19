using System.Globalization;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompositionalReinjectionEditor
{
    internal static bool Matches(EclipseCompositionalReinjectionReader.Snapshot? s, EclipseCompositionalReinjectionParameters p) => s is not null &&
        s.Context.SourceFile == p.SourceFile && s.Context.SourceSha256 == p.SourceSha256 && p.Unit == "fraction" &&
        EclipseCompositionalReinjectionParameters.Fraction(p.TargetFraction) && p.TargetFraction != p.OriginalFraction &&
        s.Context.Records.Count(r => r.Group == p.Group && r.Phase == p.Phase && r.LineNumber == p.LineNumber && r.OriginalFraction == p.OriginalFraction) == 1;
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseCompositionalReinjectionParameters p, CancellationToken cancellation)
    {
        var s = EclipseCompositionalReinjectionReader.Read(package);
        if (!Matches(s, p)) throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_REINJECTION_MISMATCH",
            "The E300 available-gas reinjection fraction must bind the exact source SHA, FIELD/GAS group, row, unit and original value; no input changed.");
        var text = new UTF8Encoding(false, true).GetString(s!.SourceBytes);
        var changed = text[..s.Start] + p.TargetFraction.ToString("R", CultureInfo.InvariantCulture) + text[(s.Start + s.Length)..];
        await File.WriteAllBytesAsync(package.MainFile, Encoding.UTF8.GetBytes(changed), cancellation);
        return "Applied one initial E300 available-gas reinjection fraction; original sales, vapor source, pressures, separator changes and shut schedule retained.";
    }
}
