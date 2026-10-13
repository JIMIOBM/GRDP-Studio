using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static partial class EclipseScheduleEditor
{
    internal static async Task<string> ApplyCompletionFactorAsync(EclipseDeckPackage package, EclipseCompletionFactorParameters parameters, CancellationToken cancellationToken)
    {
        foreach (var file in package.Files)
        {
            var relative = Path.GetRelativePath(package.Root, file).Replace('\\', '/');
            if (!relative.Equals(parameters.SourceFile, StringComparison.OrdinalIgnoreCase)) continue;
            var original = await File.ReadAllTextAsync(file, new UTF8Encoding(false, true), cancellationToken);
            var lines = original.Split(["\r\n", "\n", "\r"], StringSplitOptions.None);
            if (parameters.LineNumber > lines.Length) break;
            var block = string.Empty;
            for (var index = 0; index < lines.Length; index++)
            {
                var trimmed = StripComment(lines[index]).Trim();
                if (trimmed.Equals("COMPDAT", StringComparison.OrdinalIgnoreCase)) { block = "COMPDAT"; continue; }
                if (trimmed.Equals("COMPDATM", StringComparison.OrdinalIgnoreCase)) { block = "COMPDATM"; continue; }
                if (trimmed == "/") { block = string.Empty; continue; }
                if (index + 1 != parameters.LineNumber || block != "COMPDAT") continue;
                var match = CompdatFactorRow().Match(lines[index]);
                if (!match.Success || !string.Equals(match.Groups["well"].Value, parameters.Well, StringComparison.Ordinal)
                    || match.Groups["i"].Value != parameters.I || match.Groups["j"].Value != parameters.J
                    || match.Groups["k1"].Value != parameters.K1 || match.Groups["k2"].Value != parameters.K2)
                    break;
                if (!double.TryParse(match.Groups["cf"].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var originalFactor)
                    || Math.Abs(originalFactor - parameters.OriginalConnectionFactor) > Math.Max(1e-12, Math.Abs(parameters.OriginalConnectionFactor) * 1e-12))
                    break;
                var target = parameters.TargetConnectionFactor.ToString("G17", CultureInfo.InvariantCulture);
                lines[index] = match.Groups["prefix"].Value + "'" + match.Groups["status"].Value + "'" + match.Groups["satPrefix"].Value
                    + match.Groups["sat"].Value + match.Groups["cfPrefix"].Value + target + match.Groups["suffix"].Value;
                var newline = original.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : original.Contains('\n') ? "\n" : "\r";
                await File.WriteAllTextAsync(file, string.Join(newline, lines), new UTF8Encoding(false), cancellationToken);
                return $"Applied COMPDAT CF {parameters.Well} I={parameters.I} J={parameters.J} K={parameters.K1}-{parameters.K2} {parameters.OriginalConnectionFactor:G17}->{target} at {parameters.SourceFile}:{parameters.LineNumber}.";
            }
            break;
        }
        throw new EclipseDeckPackageException("ECLIPSE_COMPLETION_FACTOR_TARGET_MISSING", "The requested COMPDAT connection-factor row was not found at the inspected source file and line, or its original value changed.");
    }

    internal static async Task<string> ApplyCompletionAsync(EclipseDeckPackage package, EclipseCompletionParameters parameters, CancellationToken cancellationToken)
    {
        foreach (var file in package.Files)
        {
            var relative = Path.GetRelativePath(package.Root, file).Replace('\\', '/');
            if (!relative.Equals(parameters.SourceFile, StringComparison.OrdinalIgnoreCase)) continue;
            var original = await File.ReadAllTextAsync(file, new UTF8Encoding(false, true), cancellationToken);
            var lines = original.Split(["\r\n", "\n", "\r"], StringSplitOptions.None);
            if (parameters.LineNumber > lines.Length) break;
            var block = string.Empty;
            for (var index = 0; index < lines.Length; index++)
            {
                var trimmed = StripComment(lines[index]).Trim();
                if (trimmed.Equals("COMPDAT", StringComparison.OrdinalIgnoreCase)) { block = "COMPDAT"; continue; }
                if (trimmed.Equals("COMPDATM", StringComparison.OrdinalIgnoreCase)) { block = "COMPDATM"; continue; }
                if (trimmed == "/") { block = string.Empty; continue; }
                if (index + 1 != parameters.LineNumber || block != "COMPDAT") continue;
                var match = CompdatRow().Match(lines[index]);
                if (!match.Success || !string.Equals(match.Groups["well"].Value, parameters.Well, StringComparison.Ordinal)
                    || match.Groups["i"].Value != parameters.I || match.Groups["j"].Value != parameters.J
                    || match.Groups["k1"].Value != parameters.K1 || match.Groups["k2"].Value != parameters.K2
                    || match.Groups["status"].Value.Equals(parameters.Status, StringComparison.OrdinalIgnoreCase)) break;
                lines[index] = match.Groups["prefix"].Value + "'" + parameters.Status + "'" + match.Groups["suffix"].Value;
                var newline = original.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : original.Contains('\n') ? "\n" : "\r";
                await File.WriteAllTextAsync(file, string.Join(newline, lines), new UTF8Encoding(false), cancellationToken);
                return $"Applied COMPDAT {parameters.Well} I={parameters.I} J={parameters.J} K={parameters.K1}-{parameters.K2}={parameters.Status} at {parameters.SourceFile}:{parameters.LineNumber}.";
            }
            break;
        }
        throw new EclipseDeckPackageException("ECLIPSE_COMPLETION_TARGET_MISSING", "The requested COMPDAT completion row was not found at the inspected source file and line.");
    }

    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseScheduleParameters parameters, CancellationToken cancellationToken)
    {
        if (parameters.SchemaVersion is EclipseScheduleParameters.MultiEventSchema or EclipseScheduleParameters.MultiWellSchema or EclipseScheduleParameters.MultiDateSchema)
            return await ApplyWellScheduleEventsAsync(package, parameters, cancellationToken);
        if (parameters.ControlMode == "ORAT")
        {
            return parameters.SchemaVersion == EclipseScheduleParameters.ProductionSchema
                ? await ApplyWconProdAsync(package, parameters, cancellationToken)
                : await ApplyWconHistAsync(package, parameters, cancellationToken);
        }
        if (parameters.ControlMode == "RATE")
        {
            return await ApplyWconInjeAsync(package, parameters, cancellationToken);
        }
        foreach (var file in package.Files.Where(path => Path.GetExtension(path) is ".DATA" or ".SCH" or ".INC"))
        {
            var original = await File.ReadAllTextAsync(file, new UTF8Encoding(false, true), cancellationToken);
            var lines = original.Split(["\r\n", "\n", "\r"], StringSplitOptions.None);
            var newline = original.Contains("\r\n", StringComparison.Ordinal)
                ? "\r\n"
                : original.Contains('\n') ? "\n" : "\r";
            var inDates = false;
            var matchingDate = false;
            var inWellOpen = false;
            var changed = false;
            for (var index = 0; index < lines.Length; index++)
            {
                var trimmed = StripComment(lines[index]).Trim();
                if (trimmed.Equals("DATES", StringComparison.OrdinalIgnoreCase))
                {
                    inDates = true;
                    matchingDate = false;
                    inWellOpen = false;
                    continue;
                }
                if (inDates)
                {
                    if (TryParseDate(trimmed, out var date)) matchingDate = date == parameters.Date;
                    if (trimmed.EndsWith('/')) inDates = false;
                    continue;
                }
                if (!matchingDate) continue;
                if (!inWellOpen && trimmed.Equals("WELOPEN", StringComparison.OrdinalIgnoreCase))
                {
                    inWellOpen = true;
                    continue;
                }
                if (!inWellOpen) continue;
                if (trimmed == "/")
                {
                    if (changed)
                    {
                        await File.WriteAllTextAsync(file, string.Join(newline, lines), new UTF8Encoding(false), cancellationToken);
                        return $"Applied WELOPEN {parameters.Well}={parameters.Status} at {parameters.Date:yyyy-MM-dd} in {Path.GetFileName(file)}.";
                    }
                    inWellOpen = false;
                    continue;
                }
                var match = WellOpenRow().Match(lines[index]);
                if (!match.Success || !string.Equals(match.Groups["well"].Value, parameters.Well, StringComparison.Ordinal)) continue;
                lines[index] = match.Groups["prefix"].Value + "'" + parameters.Status + "'" + match.Groups["suffix"].Value;
                changed = true;
            }
        }
        throw new EclipseDeckPackageException("ECLIPSE_SCHEDULE_TARGET_MISSING", "The requested well and DATES entry do not contain an existing whole-well WELOPEN row.");
    }

    private static async Task<string> ApplyWellScheduleEventsAsync(EclipseDeckPackage package, EclipseScheduleParameters parameters, CancellationToken cancellationToken)
    {
        var events = parameters.Events;
        if (events is null || events.Count < 2 || events.Count > (parameters.SchemaVersion == EclipseScheduleParameters.MultiDateSchema ? 24 : 2))
            throw new EclipseDeckPackageException("ECLIPSE_SCHEDULE_EVENTS_INVALID", "The ECLIPSE well schedule requires ordered OPEN/SHUT events within the supported limit.");
        IReadOnlyList<string> wells = parameters.Wells ?? [parameters.Well];

        var matches = new Dictionary<DateOnly, List<(string File, string[] Lines, ScheduleDateBlock Block, string Newline)>>();
        foreach (var file in package.Files.Where(IsScheduleFile))
        {
            var original = await File.ReadAllTextAsync(file, new UTF8Encoding(false, true), cancellationToken);
            var lines = original.Split(["\r\n", "\n", "\r"], StringSplitOptions.None);
            var newline = original.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : original.Contains('\n') ? "\n" : "\r";
            foreach (var block in FindDateBlocks(lines))
            {
                if (events.Any(item => item.Date == block.Date))
                {
                    if (!matches.TryGetValue(block.Date, out var list)) matches[block.Date] = list = [];
                    list.Add((file, lines, block, newline));
                }
            }
        }

        var selected = new List<(string File, string[] Lines, ScheduleDateBlock Block, string Newline)>(events.Count);
        foreach (var item in events)
        {
            if (!matches.TryGetValue(item.Date, out var dateMatches) || dateMatches.Count != 1)
                throw new EclipseDeckPackageException(dateMatches is { Count: > 1 } ? "ECLIPSE_SCHEDULE_DATE_AMBIGUOUS" : "ECLIPSE_SCHEDULE_TARGET_MISSING",
                    dateMatches is { Count: > 1 } ? "A selected DATES date occurs more than once in the model package." : "A selected DATES date does not exist as one unambiguous schedule block.");
            selected.Add(dateMatches[0]);
        }
        if (selected.Any(item => !string.Equals(selected[0].File, item.File, StringComparison.OrdinalIgnoreCase)))
            throw new EclipseDeckPackageException("ECLIPSE_SCHEDULE_SPLIT_FILE_UNSUPPORTED", "The first supported two-event plan requires both DATES blocks in the same schedule file; INCLUDE files are preserved.");
        if (parameters.SchemaVersion is EclipseScheduleParameters.MultiWellSchema or EclipseScheduleParameters.MultiDateSchema
            && selected.Zip(selected.Skip(1)).Any(pair => pair.First.Block.ContentEnd >= pair.Second.Block.ContentStart))
            throw new EclipseDeckPackageException("ECLIPSE_SCHEDULE_ORDER_UNPROVEN", "The selected event blocks must follow the requested date order in the schedule file.");

        var scheduleFile = selected[0].File;
        foreach (var file in package.Files.Where(IsScheduleFile))
        {
            if (string.Equals(file, scheduleFile, StringComparison.OrdinalIgnoreCase)) continue;
            var text = await File.ReadAllTextAsync(file, new UTF8Encoding(false, true), cancellationToken);
            if (ContainsScheduleControl(text))
                throw new EclipseDeckPackageException("ECLIPSE_SCHEDULE_ORDER_UNPROVEN", "Schedule controls are split across INCLUDE files; the final event order cannot be proven for this first two-event workflow.");
        }

        var sourceLines = selected[0].Lines;
        if (parameters.SchemaVersion is EclipseScheduleParameters.MultiWellSchema or EclipseScheduleParameters.MultiDateSchema)
            ValidateGroupTargets(package, wells, scheduleFile, selected[0].Block.ContentEnd);
        foreach (var target in selected)
        {
            if (sourceLines.Skip(target.Block.ContentStart).Take(target.Block.ContentEnd - target.Block.ContentStart)
                .Any(line => StripComment(line).Trim().Equals("INCLUDE", StringComparison.OrdinalIgnoreCase)))
                throw new EclipseDeckPackageException("ECLIPSE_SCHEDULE_ORDER_UNPROVEN", "An INCLUDE occurs inside a selected date block; the final same-date control order is ambiguous.");
        }

        var edits = selected.Select((target, index) => (target.Block.ContentEnd, Event: events[index]))
            .OrderByDescending(item => item.ContentEnd).ToArray();
        var updated = sourceLines.ToList();
        foreach (var edit in edits)
        {
            var records = new List<string>
            {
                "",
                "-- GRDP-Studio controlled final well-state event; original schedule records are retained.",
                "WELOPEN"
            };
            records.AddRange(wells.Select(well => $"       '{well}' '{edit.Event.Status}' /"));
            records.Add("/");
            updated.InsertRange(edit.ContentEnd, records);
        }

        var content = string.Join(selected[0].Newline, updated);
        await WriteAtomicallyAsync(scheduleFile, content, cancellationToken);
        return $"Applied ordered WELOPEN events {string.Join(", ", wells)}={string.Join(", ", events.Select(item => $"{item.Status} at {item.Date:yyyy-MM-dd}"))} after same-date schedule controls in {Path.GetFileName(scheduleFile)}.";
    }

    private static void ValidateGroupTargets(EclipseDeckPackage package, IReadOnlyList<string> wells, string scheduleFile, int firstEventEnd)
    {
        var metadata = EclipseDataInspector.InspectPackage(package.Files, Path.GetFileName(package.MainFile)).ScheduleMetadata;
        var file = Path.GetRelativePath(package.Root, scheduleFile).Replace('\\', '/');
        foreach (var well in wells)
        {
            var definition = metadata?.Wells.SingleOrDefault(item => item.Name == well);
            var declared = definition is { LineNumber: > 0 } && definition.LineNumber <= firstEventEnd
                && string.Equals(definition.SourceFile, file, StringComparison.OrdinalIgnoreCase);
            var controlled = metadata?.Records.Any(record => record.Keyword is "WCONHIST" or "WCONPROD"
                && record.Values.Count > 0 && record.Values[0] == well && record.LineNumber <= firstEventEnd
                && string.Equals(record.SourceFile, file, StringComparison.OrdinalIgnoreCase)) == true;
            if (!declared || !controlled)
                throw new EclipseDeckPackageException("ECLIPSE_SCHEDULE_GROUP_TARGET_INVALID",
                    $"Selected producer '{well}' must be defined and have production controls before the first event in the supported schedule file.");
        }
    }

    private static IReadOnlyList<ScheduleDateBlock> FindDateBlocks(string[] lines)
    {
        var result = new List<ScheduleDateBlock>();
        for (var index = 0; index < lines.Length; index++)
        {
            if (!StripComment(lines[index]).Trim().Equals("DATES", StringComparison.OrdinalIgnoreCase)) continue;
            var dates = new List<DateOnly>();
            var malformed = false;
            var cursor = index + 1;
            var terminated = false;
            for (; cursor < lines.Length; cursor++)
            {
                var record = StripComment(lines[cursor]).Trim();
                if (record == "/") { terminated = true; cursor++; break; }
                if (record.Length == 0) continue;
                if (TryParseDate(record, out var date)) dates.Add(date);
                else malformed = true;
            }
            if (terminated && !malformed && dates.Count == 1)
            {
                var end = cursor;
                while (end < lines.Length)
                {
                    var next = StripComment(lines[end]).Trim();
                    if (next.Equals("DATES", StringComparison.OrdinalIgnoreCase)
                        || next.Equals("TSTEP", StringComparison.OrdinalIgnoreCase)
                        || next.Equals("END", StringComparison.OrdinalIgnoreCase)) break;
                    end++;
                }
                result.Add(new(dates[0], cursor, end));
            }
            index = Math.Max(index, cursor - 1);
        }
        return result;
    }

    private static bool IsScheduleFile(string path)
    {
        var extension = Path.GetExtension(path);
        return extension.Equals(".DATA", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".SCH", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".INC", StringComparison.OrdinalIgnoreCase);
    }

    private static bool ContainsScheduleControl(string content)
    {
        string[] controls = ["DATES", "TSTEP", "WELOPEN", "WCONHIST", "WCONINJE", "WCONPROD", "COMPDAT", "COMPDATM"];
        return content.Split(["\r\n", "\n", "\r"], StringSplitOptions.None)
            .Select(line => StripComment(line).Trim())
            .Any(line => controls.Contains(line, StringComparer.OrdinalIgnoreCase));
    }

    private static async Task WriteAtomicallyAsync(string path, string content, CancellationToken cancellationToken)
    {
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            await File.WriteAllTextAsync(temporary, content, new UTF8Encoding(false), cancellationToken);
            File.Move(temporary, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    private sealed record ScheduleDateBlock(DateOnly Date, int ContentStart, int ContentEnd);

    private static async Task<string> ApplyWconHistAsync(EclipseDeckPackage package, EclipseScheduleParameters parameters, CancellationToken cancellationToken)
    {
        foreach (var file in package.Files.Where(path => Path.GetExtension(path) is ".DATA" or ".SCH" or ".INC"))
        {
            var original = await File.ReadAllTextAsync(file, new UTF8Encoding(false, true), cancellationToken);
            var lines = original.Split(["\r\n", "\n", "\r"], StringSplitOptions.None);
            var newline = original.Contains("\r\n", StringComparison.Ordinal)
                ? "\r\n"
                : original.Contains('\n') ? "\n" : "\r";
            var inDates = false;
            var matchingDate = false;
            var inWconHist = false;
            for (var index = 0; index < lines.Length; index++)
            {
                var trimmed = StripComment(lines[index]).Trim();
                if (trimmed.Equals("DATES", StringComparison.OrdinalIgnoreCase))
                {
                    inDates = true;
                    matchingDate = false;
                    inWconHist = false;
                    continue;
                }
                if (inDates)
                {
                    if (TryParseDate(trimmed, out var date)) matchingDate = date == parameters.Date;
                    if (trimmed.EndsWith('/')) inDates = false;
                    continue;
                }
                if (trimmed.Equals("WCONHIST", StringComparison.OrdinalIgnoreCase))
                {
                    inWconHist = matchingDate;
                    continue;
                }
                if (!inWconHist) continue;
                if (trimmed == "/")
                {
                    inWconHist = false;
                    continue;
                }
                var match = WconHistOratRow().Match(lines[index]);
                if (!match.Success || !string.Equals(match.Groups["well"].Value, parameters.Well, StringComparison.Ordinal)) continue;
                var target = parameters.TargetOilRate!.Value.ToString("G17", CultureInfo.InvariantCulture);
                lines[index] = match.Groups["prefix"].Value + "'" + parameters.Status + "'" + match.Groups["modePrefix"].Value + target + match.Groups["suffix"].Value;
                await File.WriteAllTextAsync(file, string.Join(newline, lines), new UTF8Encoding(false), cancellationToken);
                return $"Applied WCONHIST {parameters.Well}=ORAT {target} at {parameters.Date:yyyy-MM-dd} in {Path.GetFileName(file)}.";
            }
        }
        throw new EclipseDeckPackageException("ECLIPSE_SCHEDULE_TARGET_MISSING", "The requested well and DATES entry do not contain an existing WCONHIST ORAT row.");
    }

    private static async Task<string> ApplyWconInjeAsync(EclipseDeckPackage package, EclipseScheduleParameters parameters, CancellationToken cancellationToken)
    {
        foreach (var file in package.Files.Where(path => Path.GetExtension(path) is ".DATA" or ".SCH" or ".INC"))
        {
            var original = await File.ReadAllTextAsync(file, new UTF8Encoding(false, true), cancellationToken);
            var lines = original.Split(["\r\n", "\n", "\r"], StringSplitOptions.None);
            var newline = original.Contains("\r\n", StringComparison.Ordinal)
                ? "\r\n"
                : original.Contains('\n') ? "\n" : "\r";
            var inDates = false;
            var matchingDate = false;
            var inWconInje = false;
            for (var index = 0; index < lines.Length; index++)
            {
                var trimmed = StripComment(lines[index]).Trim();
                if (trimmed.Equals("DATES", StringComparison.OrdinalIgnoreCase))
                {
                    inDates = true;
                    matchingDate = false;
                    inWconInje = false;
                    continue;
                }
                if (inDates)
                {
                    if (TryParseDate(trimmed, out var date)) matchingDate = date == parameters.Date;
                    if (trimmed.EndsWith('/')) inDates = false;
                    continue;
                }
                if (trimmed.Equals("WCONINJE", StringComparison.OrdinalIgnoreCase))
                {
                    inWconInje = matchingDate;
                    continue;
                }
                if (!inWconInje) continue;
                if (trimmed == "/")
                {
                    inWconInje = false;
                    continue;
                }
                var match = WconInjeRateRow().Match(lines[index]);
                if (!match.Success || !string.Equals(match.Groups["well"].Value, parameters.Well, StringComparison.Ordinal)
                    || !string.Equals(match.Groups["fluid"].Value, parameters.InjectionType, StringComparison.OrdinalIgnoreCase)) continue;
                var target = parameters.TargetInjectionRate!.Value.ToString("G17", CultureInfo.InvariantCulture);
                lines[index] = match.Groups["prefix"].Value + target + match.Groups["suffix"].Value;
                await File.WriteAllTextAsync(file, string.Join(newline, lines), new UTF8Encoding(false), cancellationToken);
                return $"Applied WCONINJE {parameters.Well}/{parameters.InjectionType}=RATE {target} at {parameters.Date:yyyy-MM-dd} in {Path.GetFileName(file)}.";
            }
        }
        throw new EclipseDeckPackageException("ECLIPSE_SCHEDULE_TARGET_MISSING", "The requested well and DATES entry do not contain an existing WCONINJE RATE row.");
    }

    private static async Task<string> ApplyWconProdAsync(EclipseDeckPackage package, EclipseScheduleParameters parameters, CancellationToken cancellationToken)
    {
        var forecastBlockSeen = false;
        foreach (var file in package.Files.Where(path => Path.GetExtension(path) is ".DATA" or ".SCH" or ".INC"))
        {
            var original = await File.ReadAllTextAsync(file, new UTF8Encoding(false, true), cancellationToken);
            var lines = original.Split(["\r\n", "\n", "\r"], StringSplitOptions.None);
            var newline = original.Contains("\r\n", StringComparison.Ordinal)
                ? "\r\n"
                : original.Contains('\n') ? "\n" : "\r";
            var inWconProd = false;
            for (var index = 0; index < lines.Length; index++)
            {
                var trimmed = StripComment(lines[index]).Trim();
                if (!forecastBlockSeen && trimmed.Equals("WCONPROD", StringComparison.OrdinalIgnoreCase))
                {
                    forecastBlockSeen = true;
                    inWconProd = true;
                    continue;
                }
                if (!inWconProd) continue;
                if (trimmed == "/")
                {
                    inWconProd = false;
                    continue;
                }
                var match = WconProdOratRow().Match(lines[index]);
                if (!match.Success || !string.Equals(match.Groups["well"].Value, parameters.Well, StringComparison.Ordinal)) continue;
                var target = parameters.TargetOilRate!.Value.ToString("G17", CultureInfo.InvariantCulture);
                lines[index] = match.Groups["prefix"].Value + "'" + parameters.Status + "'" + match.Groups["modePrefix"].Value + target + match.Groups["suffix"].Value;
                await File.WriteAllTextAsync(file, string.Join(newline, lines), new UTF8Encoding(false), cancellationToken);
                return $"Applied WCONPROD {parameters.Well}=ORAT {target} at FORECAST_INITIAL in {Path.GetFileName(file)}.";
            }
        }
        throw new EclipseDeckPackageException("ECLIPSE_SCHEDULE_TARGET_MISSING", "The requested forecast-initial WCONPROD ORAT row does not exist in the first forecast production block.");
    }

    private static string StripComment(string line)
    {
        var index = line.IndexOf("--", StringComparison.Ordinal);
        return index >= 0 ? line[..index] : line;
    }

    private static bool TryParseDate(string value, out DateOnly date)
    {
        date = default;
        var match = DateRow().Match(value);
        if (!match.Success) return false;
        var month = match.Groups[2].Value.ToUpperInvariant() switch
        {
            "JAN" => 1, "FEB" => 2, "MAR" => 3, "APR" => 4, "MAY" => 5, "JUN" => 6,
            "JUL" => 7, "AUG" => 8, "SEP" => 9, "OCT" => 10, "NOV" => 11, "DEC" => 12, _ => 0
        };
        return month > 0 && DateOnly.TryParseExact($"{match.Groups[3].Value}-{month:00}-{int.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture):00}", "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out date);
    }

    [GeneratedRegex("^\\s*(\\d{1,2})\\s+'?([A-Za-z]{3})'?\\s+(\\d{4})\\s*/\\s*$", RegexOptions.CultureInvariant)]
    private static partial Regex DateRow();
    [GeneratedRegex("^(?<prefix>\\s*'(?<well>[^']+)'\\s+)(?:'OPEN'|'SHUT')(?<suffix>\\s+.*)$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex WellOpenRow();
    [GeneratedRegex("^(?<prefix>\\s*'(?<well>[^']+)'\\s+)'(?:OPEN|SHUT)'(?<modePrefix>\\s+'ORAT'\\s+)(?<rate>[+\\-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[Ee][+\\-]?\\d+)?)(?<suffix>\\s+.*)$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex WconHistOratRow();
    [GeneratedRegex("^(?<prefix>\\s*'(?<well>[^']+)'\\s+'(?<fluid>[^']+)'\\s+.*?'RATE'\\s+)(?<rate>[+\\-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[Ee][+\\-]?\\d+)?)(?<suffix>\\s+.*)$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex WconInjeRateRow();
    [GeneratedRegex("^(?<prefix>\\s*'(?<well>[^']+)'\\s+)(?:'OPEN'|'SHUT')(?<modePrefix>\\s+'ORAT'\\s+)(?<rate>[+\\-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[Ee][+\\-]?\\d+)?)(?<suffix>\\s+.*)$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex WconProdOratRow();
    [GeneratedRegex("^(?<prefix>\\s*'(?<well>[^']+)'\\s+(?<i>[1-9]\\d*)\\s+(?<j>[1-9]\\d*)\\s+(?<k1>[1-9]\\d*)\\s+(?<k2>[1-9]\\d*)\\s+)'(?<status>OPEN|SHUT)'(?<suffix>\\s+.*)$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex CompdatRow();
    [GeneratedRegex("^(?<prefix>\\s*'(?<well>[^']+)'\\s+(?<i>[1-9]\\d*)\\s+(?<j>[1-9]\\d*)\\s+(?<k1>[1-9]\\d*)\\s+(?<k2>[1-9]\\d*)\\s+)'(?<status>OPEN|SHUT)'(?<satPrefix>\\s+)(?<sat>\\S+)(?<cfPrefix>\\s+)(?<cf>[+\\-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[Ee][+\\-]?\\d+)?)(?<suffix>\\s+.*)$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex CompdatFactorRow();
}
