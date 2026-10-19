using System.Globalization;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseThermalPlanEditor
{
    internal static bool Matches(EclipseThermalInjectionReader.Snapshot? snapshot, EclipseThermalPlanParameters p)
    {
        if (snapshot is null || !EclipseThermalPlanParameters.Valid(p) || snapshot.Context.SourceFile != p.SourceFile
            || snapshot.Context.SourceSha256 != p.SourceSha256) return false;
        var clock = EclipseThermalStageSchedule.Read(snapshot.SourceBytes);
        return p.Stages.All(s => EclipseThermalStageSchedule.SplitIndex(clock, s.StartTimeDays) > 0)
            && snapshot.Targets.Count(t => t.Record.Well == p.Well && t.Record.LineNumber == p.LineNumber
                && t.Record.ControlLineNumber == p.ControlLineNumber && t.Record.TemperatureUnit == p.TemperatureUnit
                && t.Record.RateUnit == p.RateUnit && (t.Record.RateUnit == "STB/day" ? "psia" : "barsa") == p.PressureUnit
                && new EclipseThermalPlanValues(t.Record.SurfaceInjectionRate, t.Record.OriginalTemperature,
                    t.Record.OriginalSteamQuality, t.Record.BhpUpperLimit) == p.Original) == 1;
    }
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseThermalPlanParameters p, CancellationToken cancellation)
    {
        var snapshot = EclipseThermalInjectionReader.Read(package);
        if (!Matches(snapshot,p)) throw new EclipseDeckPackageException("ECLIPSE_THERMAL_PLAN_MISMATCH",
            "The thermal plan must bind the complete original source, well, both rows, units, values and original internal report boundaries; no input changed.");
        var clock = EclipseThermalStageSchedule.Read(snapshot!.SourceBytes)!;
        var text = new UTF8Encoding(false,true).GetString(snapshot.SourceBytes);
        var nl = text.Contains("\r\n",StringComparison.Ordinal) ? "\r\n" : "\n";
        var well = p.Well.Contains(' ') ? "'"+p.Well+"'" : p.Well;
        static string Number(double v) => v.ToString("R",CultureInfo.InvariantCulture);
        var segments = new List<string>(); var previous = 0;
        foreach (var stage in p.Stages)
        {
            var split = EclipseThermalStageSchedule.SplitIndex(clock,stage.StartTimeDays);
            segments.Add("TSTEP\n  "+string.Join(" ",clock.Steps.Skip(previous).Take(split-previous))+" /");
            segments.Add($"WCONINJE\n  {well} WATER OPEN RATE {Number(stage.SurfaceInjectionRate)} 1* {Number(stage.BhpUpperLimit)} /\n/");
            segments.Add($"WINJTEMP\n  {well} {Number(stage.SteamQuality)} {Number(stage.InjectionTemperature)} /\n/");
            previous = split;
        }
        segments.Add("TSTEP\n  "+string.Join(" ",clock.Steps.Skip(previous))+" /");
        var replacement = string.Join("\n\n",segments).Replace("\n",nl,StringComparison.Ordinal);
        await File.WriteAllBytesAsync(package.MainFile,Encoding.UTF8.GetBytes(text[..clock.Start]+replacement+text[clock.End..]),cancellation);
        return $"Applied {p.Stages.Count} thermal injection stages after original report boundaries; original earlier controls, all report intervals and final time retained.";
    }
}
