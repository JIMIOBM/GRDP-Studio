using System.Globalization;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompositionalPressureEditor
{
    internal static bool Matches(EclipseCompositionalProductionReader.Snapshot? snapshot, EclipseCompositionalPressureParameters parameters) =>
        snapshot is not null && snapshot.Context.SourceFile == parameters.SourceFile && snapshot.Context.SourceSha256 == parameters.SourceSha256
        && double.IsFinite(parameters.TargetBhpLowerLimit) && parameters.TargetBhpLowerLimit is > 0 and <= 100_000_000 && parameters.TargetBhpLowerLimit != parameters.OriginalBhpLowerLimit
        && snapshot.Targets.Count(t => t.Record.Well == parameters.Well && t.Record.BhpLineNumber == parameters.LineNumber
            && (t.Record.Unit == "Mscf/day" ? "psia" : "barsa") == parameters.Unit && t.Record.BhpLimit == parameters.OriginalBhpLowerLimit) == 1;

    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseCompositionalPressureParameters parameters, CancellationToken cancellation)
    {
        var snapshot = EclipseCompositionalProductionReader.Read(package);
        if (snapshot is null || snapshot.Context.SourceFile != parameters.SourceFile || snapshot.Context.SourceSha256 != parameters.SourceSha256
            || !double.IsFinite(parameters.TargetBhpLowerLimit) || parameters.TargetBhpLowerLimit is <= 0 or > 100_000_000 || parameters.TargetBhpLowerLimit == parameters.OriginalBhpLowerLimit) Fail();
        var targets = snapshot!.Targets.Where(t => t.Record.Well == parameters.Well && t.Record.BhpLineNumber == parameters.LineNumber
            && (t.Record.Unit == "Mscf/day" ? "psia" : "barsa") == parameters.Unit && t.Record.BhpLimit == parameters.OriginalBhpLowerLimit).ToArray();
        if (targets.Length != 1) Fail();
        var target = targets[0];
        var text = new UTF8Encoding(false, true).GetString(snapshot.SourceBytes);
        var changed = text[..target.PressureStart] + parameters.TargetBhpLowerLimit.ToString("R", CultureInfo.InvariantCulture) + text[(target.PressureStart + target.PressureLength)..];
        await File.WriteAllBytesAsync(package.MainFile, Encoding.UTF8.GetBytes(changed), cancellation);
        return "Applied the existing E300 producer BHP lower limit; original components, EOS, gas target, reinjection and later schedule retained.";
    }
    private static void Fail() => throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_PRESSURE_MISMATCH",
        "The initial E300 producer BHP lower limit must match the verified complete source SHA, physical row, well, unit and original value; no input changed.");
}
