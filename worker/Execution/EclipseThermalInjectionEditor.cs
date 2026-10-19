using System.Globalization;
using System.Text;
namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseThermalInjectionEditor
{
    internal static bool Matches(EclipseThermalInjectionReader.Snapshot? snapshot, EclipseThermalInjectionParameters p) => snapshot is not null
        && snapshot.Context.SourceFile == p.SourceFile && snapshot.Context.SourceSha256 == p.SourceSha256
        && EclipseThermalInjectionParameters.Value(p.Field,p.Unit,p.TargetValue) && p.TargetValue != p.OriginalValue
        && (p.StartTimeDays is null || p.Field is ("SURFACE_INJECTION_RATE" or "INJECTION_TEMPERATURE") && EclipseThermalStageSchedule.SplitIndex(EclipseThermalStageSchedule.Read(snapshot.SourceBytes), p.StartTimeDays.Value) > 0)
        && snapshot.Targets.Count(t => t.Record.Well == p.Well && (p.Field is "STEAM_QUALITY" or "INJECTION_TEMPERATURE" ? t.Record.LineNumber : t.Record.ControlLineNumber) == p.LineNumber &&
            (p.Field switch {
                "STEAM_QUALITY" => p.Unit == "fraction" && t.Record.OriginalSteamQuality == p.OriginalValue,
                "INJECTION_TEMPERATURE" => p.Unit == t.Record.TemperatureUnit && t.Record.OriginalTemperature == p.OriginalValue,
                "SURFACE_INJECTION_RATE" => p.Unit == t.Record.RateUnit && t.Record.SurfaceInjectionRate == p.OriginalValue,
                "BHP_UPPER_LIMIT" => p.Unit == (t.Record.RateUnit == "STB/day" ? "psia" : "barsa") && t.Record.BhpUpperLimit == p.OriginalValue,
                _ => false
            })) == 1;
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseThermalInjectionParameters p, CancellationToken cancellation)
    {
        var snapshot = EclipseThermalInjectionReader.Read(package);
        if (!Matches(snapshot,p)) throw new EclipseDeckPackageException("ECLIPSE_THERMAL_INJECTION_MISMATCH",
            "The thermal injection value must bind the exact qualified source SHA, well, physical row, unit and original value; no input changed.");
        if (p.StartTimeDays is not null)
        {
            await File.WriteAllBytesAsync(package.MainFile, Encoding.UTF8.GetBytes(EclipseThermalStageSchedule.Apply(snapshot!, p)), cancellation);
            return "Applied a thermal injection change after the selected original report boundary; earlier controls and the complete report schedule retained.";
        }
        var t = snapshot!.Targets.Single(t => t.Record.Well == p.Well && (p.Field is "STEAM_QUALITY" or "INJECTION_TEMPERATURE" ? t.Record.LineNumber : t.Record.ControlLineNumber) == p.LineNumber);
        var (start,length) = p.Field switch {
            "STEAM_QUALITY" => (t.QualityStart,t.QualityLength), "INJECTION_TEMPERATURE" => (t.TemperatureStart,t.TemperatureLength),
            "SURFACE_INJECTION_RATE" => (t.RateStart,t.RateLength), "BHP_UPPER_LIMIT" => (t.PressureStart,t.PressureLength),
            _ => throw new InvalidOperationException("Unverified thermal field.")
        };
        var text = new UTF8Encoding(false,true).GetString(snapshot.SourceBytes);
        var replacement = p.TargetValue.ToString("R",CultureInfo.InvariantCulture);
        var original = text.Substring(start,length);
        // Keep the selected token decimal style by padding only; never round away target precision.
        if (original.Contains('.') && original.IndexOfAny(['E','e']) < 0 && replacement.IndexOfAny(['E','e']) < 0)
        {
            var digits = original.Length - original.IndexOf('.') - 1;
            if (digits is > 0 and <= 16)
            {
                if (!replacement.Contains('.')) replacement += ".";
                var present = replacement.Length - replacement.IndexOf('.') - 1;
                if (present < digits) replacement += new string('0',digits-present);
            }
        }
        await File.WriteAllBytesAsync(package.MainFile,Encoding.UTF8.GetBytes(text[..start]+replacement+text[(start+length)..]),cancellation);
        return "Applied one initial thermal injection value; all other injection settings, production controls and thermal physics retained.";
    }
}
