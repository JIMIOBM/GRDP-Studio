using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseThermalStageSchedule
{
    internal sealed record Schedule(int Start, int End, IReadOnlyList<int> Steps);

    internal static Schedule? Read(byte[] bytes)
    {
        try
        {
            var text = new UTF8Encoding(false, true).GetString(bytes);
            var headers = Regex.Matches(text, "(?im)^[ \\t]*([A-Z][A-Z0-9-]*)[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$").ToArray();
            var schedule = headers.Where(h => h.Groups[1].Value.Equals("SCHEDULE", StringComparison.OrdinalIgnoreCase)).ToArray();
            if (schedule.Length != 1) return null;
            var clocks = headers.Where(h => h.Index > schedule[0].Index && h.Groups[1].Value.ToUpperInvariant() is "TSTEP" or "DATES").ToArray();
            if (clocks.Length != 1 || !clocks[0].Groups[1].Value.Equals("TSTEP", StringComparison.OrdinalIgnoreCase)
                || headers.Any(h => h.Index > clocks[0].Index && !h.Groups[1].Value.Equals("END", StringComparison.OrdinalIgnoreCase))) return null;
            var clock = clocks[0]; var bodyStart = clock.Index + clock.Length;
            var end = headers.FirstOrDefault(h => h.Index > clock.Index)?.Index ?? text.Length;
            var steps = new List<int>(); var slash = -1;
            foreach (Match token in Regex.Matches(text[bodyStart..end], "--[^\\r\\n]*|/|[^\\s/]+"))
            {
                if (token.Value.StartsWith("--", StringComparison.Ordinal)) continue;
                if (slash >= 0) return null;
                if (token.Value == "/") { slash = bodyStart + token.Index + 1; continue; }
                var value = Regex.Match(token.Value, "^(?:(?<count>[1-9][0-9]{0,3})\\*)?(?<days>[1-9][0-9]{0,8})(?:\\.0+)?$");
                if (!value.Success) return null;
                var count = value.Groups["count"].Success ? int.Parse(value.Groups["count"].Value, CultureInfo.InvariantCulture) : 1;
                var days = int.Parse(value.Groups["days"].Value, CultureInfo.InvariantCulture);
                if (count > 1000 || steps.Count + count > 1000) return null;
                for (var i = 0; i < count; i++) steps.Add(days);
            }
            if (slash < 0 || steps.Count < 2 || steps.Sum(s => (long)s) > 1_000_000_000) return null;
            return new(clock.Index, slash, steps);
        }
        catch (Exception e) when (e is DecoderFallbackException or FormatException or OverflowException) { return null; }
    }

    internal static int SplitIndex(Schedule? schedule, int day)
    {
        if (schedule is null || day <= 0) return -1;
        long elapsed = 0;
        for (var i = 0; i < schedule.Steps.Count - 1; i++)
        {
            elapsed += schedule.Steps[i];
            if (elapsed == day) return i + 1;
        }
        return -1;
    }

    internal static string Apply(EclipseThermalInjectionReader.Snapshot snapshot, EclipseThermalInjectionParameters p)
    {
        var schedule = Read(snapshot.SourceBytes); var split = SplitIndex(schedule, p.StartTimeDays ?? 0);
        if (split < 1 || p.Field is not ("SURFACE_INJECTION_RATE" or "INJECTION_TEMPERATURE"))
            throw new InvalidOperationException("The staged thermal change requires an existing internal report boundary.");
        var text = new UTF8Encoding(false, true).GetString(snapshot.SourceBytes);
        var nl = text.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : "\n";
        var record = snapshot.Targets.Single(t => t.Record.Well == p.Well).Record;
        static string Number(double value) => value.ToString("R", CultureInfo.InvariantCulture);
        var well = p.Well.Contains(' ') ? "'" + p.Well + "'" : p.Well;
        var control = p.Field == "SURFACE_INJECTION_RATE"
            ? $"WCONINJE\n  {well} WATER OPEN RATE {Number(p.TargetValue)} 1* {Number(record.BhpUpperLimit)} /\n/"
            : $"WINJTEMP\n  {well} {Number(record.OriginalSteamQuality)} {Number(p.TargetValue)} /\n/";
        var replacement = "TSTEP\n  " + string.Join(" ", schedule!.Steps.Take(split)) + " /\n\n" + control
            + "\n\nTSTEP\n  " + string.Join(" ", schedule.Steps.Skip(split)) + " /";
        return text[..schedule.Start] + replacement.Replace("\n", nl, StringComparison.Ordinal) + text[schedule.End..];
    }
}
