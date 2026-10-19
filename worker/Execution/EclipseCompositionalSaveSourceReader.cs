using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseCompositionalSaveSourceReader
{
    internal sealed record Snapshot(EclipseCompositionalProductionContext Production, byte[] OriginalBytes,
        string StartDate, int ReportNumber, double TimeDays, double EndTimeDays,
        IReadOnlyList<double> FutureReportTimes, string SummaryText, string FutureSchedule, string InjectionWell);
    private sealed record Block(string Name, int Start, int BodyStart, int End);

    internal static Snapshot? Read(EclipseDeckPackage package)
    {
        try { return ReadCore(package); }
        catch (Exception e) when (e is FormatException or OverflowException or InvalidOperationException
            or ArgumentOutOfRangeException or DecoderFallbackException or EclipseDeckPackageException) { return null; }
    }

    private static Snapshot? ReadCore(EclipseDeckPackage package)
    {
        var reinjection = EclipseCompositionalReinjectionReader.Read(package);
        var production = EclipseCompositionalProductionReader.Read(package);
        var fluid = EclipseCompositionalFluidPropertyReader.Read(package);
        if (reinjection is null || production is null || fluid is null || production.Context.Records.Count != 1
            || !production.SourceBytes.SequenceEqual(reinjection.SourceBytes) || !production.SourceBytes.SequenceEqual(fluid.SourceBytes)) return null;
        var source = new UTF8Encoding(false, true).GetString(production.SourceBytes).Replace("\r\n", "\n", StringComparison.Ordinal);
        if (source.Contains('\r')) return null;
        var matches = Regex.Matches(source, "(?im)^[ \\t]*([A-Z][A-Z0-9-]*)[ \\t]*(?:--[^\\n]*|=+[ \\t]*(?:--[^\\n]*)?)?$").ToArray();
        var blocks = matches.Select((m, n) => new Block(m.Groups[1].Value.ToUpperInvariant(), m.Index, m.Index + m.Length,
            n + 1 < matches.Length ? matches[n + 1].Index : source.Length)).ToArray();
        if (blocks.Any(b => b.Name is "UNIFSAVE" or "FMTSAVE" or "PARALLEL" or "UNIFIN" or "FMTIN"
            or "LOAD" or "RESTART" or "SKIPREST" or "AUTOSAVE")) return null;
        var starts = blocks.Where(b => b.Name == "START").ToArray();
        var summaries = blocks.Where(b => b.Name == "SUMMARY").ToArray();
        var schedules = blocks.Where(b => b.Name == "SCHEDULE").ToArray();
        var runSpecs = blocks.Where(b => b.Name == "RUNSPEC").ToArray();
        var grids = blocks.Where(b => b.Name == "GRID").ToArray();
        var saves = blocks.Where(b => b.Name == "SAVE").ToArray();
        var multiple = blocks.Where(b => b.Name == "MULTSAVE").ToArray();
        if (starts.Length > 1 || summaries.Length != 1 || schedules.Length != 1 || saves.Length != 1 || multiple.Length != 1
            || runSpecs.Length != 1 || grids.Length != 1 || runSpecs[0].Start >= grids[0].Start
            || starts.Length == 1 && (starts[0].Start <= runSpecs[0].Start || starts[0].Start >= grids[0].Start)
            || summaries[0].Start >= schedules[0].Start || saves[0].Start <= schedules[0].Start
            || multiple[0].Start <= runSpecs[0].Start || multiple[0].Start >= grids[0].Start
            || Body(source, multiple[0]) != "0 /" || Body(source, saves[0]).Length != 0) return null;
        // Reference START: the explicit E300 default is midnight 1 January 1990.
        var start = Regex.Match(starts.Length == 0 ? "1 JAN 1990 /" : Body(source, starts[0]),
            "^([0-9]{1,2}) ['\"]?([A-Za-z]{3})['\"]? ([0-9]{4}) /$", RegexOptions.CultureInvariant);
        var months = new[] { "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC" };
        if (!start.Success) return null;
        var month = Array.IndexOf(months, start.Groups[2].Value.ToUpperInvariant()) + 1;
        if (month < 1) return null;
        var origin = new DateOnly(int.Parse(start.Groups[3].Value, CultureInfo.InvariantCulture), month,
            int.Parse(start.Groups[1].Value, CultureInfo.InvariantCulture));
        double elapsed = 0, savedTime = -1;
        var report = 0; var savedReport = -1; var future = new List<double>();
        foreach (var block in blocks.Where(b => b.Start > schedules[0].Start))
        {
            if (block.Name == "SAVE") { savedTime = elapsed; savedReport = report; }
            if (block.Name != "TSTEP") continue;
            var body = Body(source, block);
            if (!body.EndsWith(" /", StringComparison.Ordinal) || body[..^2].Contains('/')) return null;
            var tokens = body[..^2].Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (tokens.Length == 0 || tokens.Length > 128) return null;
            foreach (var token in tokens)
            {
                var m = Regex.Match(token, "^(?:(?<repeat>[0-9]+)\\*)?(?<days>[0-9]+(?:\\.[0-9]+)?)$", RegexOptions.CultureInvariant);
                if (!m.Success) return null;
                var count = m.Groups["repeat"].Success ? int.Parse(m.Groups["repeat"].Value, CultureInfo.InvariantCulture) : 1;
                var days = double.Parse(m.Groups["days"].Value, CultureInfo.InvariantCulture);
                if (count is < 1 or > 128 || !double.IsFinite(days) || days is <= 0 or > 100_000) return null;
                for (var j = 0; j < count; j++)
                {
                    elapsed += days; report++;
                    if (report > 4096 || !double.IsFinite(elapsed) || elapsed > 1_000_000) return null;
                    if (savedReport >= 0) future.Add(elapsed);
                }
            }
        }
        if (savedReport is < 1 or > 9999 || savedTime <= 0 || savedTime >= elapsed || Math.Truncate(savedTime) != savedTime
            || future.Count is < 1 or > 4096 || Math.Truncate(elapsed) != elapsed) return null;
        _ = origin.AddDays(checked((int)elapsed));
        return new(production.Context, production.SourceBytes, origin.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
            savedReport, savedTime, elapsed, future, source[summaries[0].Start..schedules[0].Start], source[saves[0].BodyStart..],
            reinjection.Context.Records.Single().InjectionWell);
    }

    internal static byte[] BuildForecast(Snapshot snapshot, double? targetGasRate, string stateRoot = "HISTORY")
    {
        if (targetGasRate is not null && (!double.IsFinite(targetGasRate.Value) || targetGasRate is <= 0 or > 100_000_000))
            throw new InvalidDataException("Forecast gas target must be a finite positive FIELD volume rate.");
        if (stateRoot != "HISTORY" && stateRoot != "history/state/" + Path.GetFileNameWithoutExtension(snapshot.Production.SourceFile))
            throw new InvalidDataException("Forecast state root must be the controlled history package location.");
        var producer = snapshot.Production.Records.Single();
        var control = targetGasRate is null ? "" : "\nWCONPROD\n" + producer.Well + " OPEN GRAT 2* "
            + targetGasRate.Value.ToString("R", CultureInfo.InvariantCulture) + " 2* "
            + producer.BhpLimit.ToString("R", CultureInfo.InvariantCulture) + " /\n/\n";
        var text = "LOAD\n'" + stateRoot + "' " + snapshot.ReportNumber.ToString(CultureInfo.InvariantCulture) + " /\n"
            + snapshot.SummaryText + "\nSCHEDULE\n" + control + snapshot.FutureSchedule;
        return Encoding.UTF8.GetBytes(text);
    }

    private static string Body(string source, Block block) => Regex.Replace(
        EclipseSimulationReader.StripComments(source[block.BodyStart..block.End]), "\\s+", " ").Trim();
}
