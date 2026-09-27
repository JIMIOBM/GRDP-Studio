using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

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
