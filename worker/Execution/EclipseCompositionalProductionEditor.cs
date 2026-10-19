using System.Globalization;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompositionalProductionEditor
{
    internal static bool Matches(EclipseCompositionalProductionReader.Snapshot? snapshot, EclipseCompositionalProductionParameters parameters) =>
        snapshot is not null && snapshot.Context.SourceFile == parameters.SourceFile && snapshot.Context.SourceSha256 == parameters.SourceSha256
        && double.IsFinite(parameters.TargetGasRate) && parameters.TargetGasRate is > 0 and <= 100_000_000 && parameters.TargetGasRate != parameters.OriginalGasRate
        && snapshot.Targets.Count(t => t.Record.Well == parameters.Well && t.Record.LineNumber == parameters.LineNumber
            && t.Record.Unit == parameters.Unit && t.Record.OriginalGasRate == parameters.OriginalGasRate) == 1;

    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseCompositionalProductionParameters parameters, CancellationToken cancellation)
    {
        var snapshot = EclipseCompositionalProductionReader.Read(package);
        if (snapshot is null || snapshot.Context.SourceFile != parameters.SourceFile || snapshot.Context.SourceSha256 != parameters.SourceSha256
            || !double.IsFinite(parameters.TargetGasRate) || parameters.TargetGasRate is <= 0 or > 100_000_000 || parameters.TargetGasRate == parameters.OriginalGasRate) Fail();
        var targets = snapshot!.Targets.Where(t => t.Record.Well == parameters.Well && t.Record.LineNumber == parameters.LineNumber
            && t.Record.Unit == parameters.Unit && t.Record.OriginalGasRate == parameters.OriginalGasRate).ToArray();
        if (targets.Length != 1) Fail();
        var target = targets[0];
        var text = new UTF8Encoding(false, true).GetString(snapshot.SourceBytes);
        var changed = text[..target.Start] + parameters.TargetGasRate.ToString("R", CultureInfo.InvariantCulture) + text[(target.Start + target.Length)..];
        await File.WriteAllBytesAsync(package.MainFile, Encoding.UTF8.GetBytes(changed), cancellation);
        return "Applied the existing E300 gas production target; original components, EOS, pressure limit, reinjection and later schedule retained.";
    }
    private static void Fail() => throw new EclipseDeckPackageException("ECLIPSE_COMPOSITIONAL_PRODUCTION_MISMATCH",
        "The initial E300 gas target must match the verified complete source SHA, physical row, well, unit and original value; no input changed.");
}
