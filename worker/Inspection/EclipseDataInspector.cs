using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Inspection;

public sealed class EclipseDataInspectionException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

public static class EclipseDataInspector
{
    private const int MaxLines = 2_000_000;
    private const int MaxTokens = 1_000_000;
    private const int MaxTokenBytes = 1024;
    private const int MaxDimensBytes = 64 * 1024;
    private const int MaxDimension = 1_000_000;
    private static readonly string[] SectionKeywords = ["RUNSPEC", "GRID", "EDIT", "PROPS", "REGIONS", "SOLUTION", "SUMMARY", "SCHEDULE"];
    private static readonly string[] UnitKeywords = ["METRIC", "FIELD", "LAB", "PVT-M"];
    private static readonly string[] PhaseKeywords = ["OIL", "WATER", "GAS"];

    public static EclipseDataInspection Inspect(string path, string caseName)
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.SequentialScan);
            using var reader = new StreamReader(stream, new UTF8Encoding(false, true), detectEncodingFromByteOrderMarks: false, bufferSize: 64 * 1024);
            var v1 = new Parser(caseName).Read(reader);
            using var scheduleStream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.SequentialScan);
            using var scheduleReader = new StreamReader(scheduleStream, new UTF8Encoding(false, true), detectEncodingFromByteOrderMarks: false, bufferSize: 64 * 1024);
            var schedule = new ScheduleParser().Read(scheduleReader);
            return v1 with
            {
                SchemaVersion = "eclipse-data-inspection/2",
                WellNames = schedule.WellNames,
                ScheduleTimeline = schedule.Events
            };
        }
        catch (DecoderFallbackException)
        {
            throw new EclipseDataInspectionException("ECLIPSE_DATA_INVALID_UTF8", "The ECLIPSE .DATA file is not valid UTF-8.");
        }
    }

    private sealed class Parser(string caseName)
    {
        private readonly List<string> sections = [];
        private readonly List<string> phases = [];
        private readonly StringBuilder token = new();
        private readonly StringBuilder identifier = new();
        private readonly List<string> dimensTokens = [];
        private string? unitSystem;
        private bool quoted;
        private bool quotePending;
        private bool comment;
        private bool previousHyphen;
        private bool previousAsciiWord;
        private bool previousWasCarriageReturn;
        private bool lineHasContent;
        private bool firstIdentifierSeen;
        private bool identifierActive;
        private bool activeDimens;
        private bool dimensionsInvalid;
        private int tokenBytes;
        private int tokenCount;
        private int physicalLines;
        private int dimensBytes;
        private EclipseDimensions? dimensions;

        public EclipseDataInspection Read(TextReader reader)
        {
            int value;
            while ((value = reader.Read()) >= 0) Process((char)value);
            if (quotePending) { quoted = false; quotePending = false; }
            FinishLine(endOfFile: true);
            if (activeDimens) dimensionsInvalid = true;
            return new("eclipse-data-inspection/1", caseName, sections, unitSystem, phases, dimensionsInvalid ? null : dimensions);
        }

        private void Process(char value)
        {
            if (value == '\ufeff' && physicalLines == 0 && !lineHasContent && !quoted && !comment) return;
            if (value == '\0') Fail("ECLIPSE_DATA_NUL_BYTE", "The ECLIPSE .DATA file contains a NUL byte.");
            if (value is '\r' or '\n')
            {
                if (quotePending) { quoted = false; quotePending = false; }
                if (value == '\n' && previousWasCarriageReturn) { previousWasCarriageReturn = false; return; }
                FinishLine(endOfFile: false);
                previousWasCarriageReturn = value == '\r';
                return;
            }
            previousWasCarriageReturn = false;

            lineHasContent = true;
            if (comment) return;
            if (quoted)
            {
                if (quotePending)
                {
                    if (value == '\'') { quotePending = false; return; }
                    quoted = false;
                    quotePending = false;
                    Process(value);
                    return;
                }
                if (value == '\'') quotePending = true;
                return;
            }
            if (value == '\'')
            {
                FinishToken();
                FinishIdentifier();
                quoted = true;
                previousHyphen = false;
                previousAsciiWord = false;
                return;
            }
            if (value == '-' && previousHyphen)
            {
                RemovePreviousHyphen();
                FinishToken();
                FinishIdentifier();
                comment = true;
                previousHyphen = false;
                previousAsciiWord = false;
                return;
            }
            if (value == '/')
            {
                FinishToken();
                FinishIdentifier();
                FinishDimens();
                previousHyphen = false;
                previousAsciiWord = false;
                return;
            }
            if (char.IsWhiteSpace(value))
            {
                FinishToken();
                FinishIdentifier();
                previousHyphen = false;
                previousAsciiWord = false;
                return;
            }

            AddToken(value);
            if (IsAsciiWord(value))
            {
                if (!identifierActive && !previousAsciiWord && (value is >= 'A' and <= 'Z' or >= 'a' and <= 'z')) identifierActive = true;
                if (identifierActive) identifier.Append(value);
                previousAsciiWord = true;
            }
            else if (value == '-' && identifierActive && IsPvtPrefix(identifier))
            {
                identifier.Append('-');
                previousAsciiWord = false;
            }
            else
            {
                FinishIdentifier();
                previousAsciiWord = false;
            }
            previousHyphen = value == '-';
        }

        private void FinishLine(bool endOfFile)
        {
            FinishToken();
            FinishIdentifier();
            if (lineHasContent || !endOfFile)
            {
                physicalLines++;
                if (physicalLines > MaxLines) Fail("ECLIPSE_DATA_LINE_LIMIT", "The ECLIPSE .DATA file exceeds the physical line limit.");
            }
            quoted = false;
            quotePending = false;
            comment = false;
            previousHyphen = false;
            previousAsciiWord = false;
            lineHasContent = false;
            firstIdentifierSeen = false;
        }

        private void AddToken(char value)
        {
            token.Append(value);
            tokenBytes += Utf8Bytes(value);
            if (tokenBytes > MaxTokenBytes) Fail("ECLIPSE_DATA_TOKEN_TOO_LONG", "The ECLIPSE .DATA file contains an oversized unquoted token.");
        }

        private void RemovePreviousHyphen()
        {
            if (token.Length > 0)
            {
                token.Length--;
                tokenBytes--;
            }
            if (identifier.Length > 0 && identifier[^1] == '-') identifier.Length--;
        }

        private void FinishToken()
        {
            if (token.Length == 0) return;
            tokenCount++;
            if (tokenCount > MaxTokens) Fail("ECLIPSE_DATA_TOKEN_LIMIT", "The ECLIPSE .DATA file exceeds the lexical token limit.");
            if (activeDimens)
            {
                dimensBytes += tokenBytes;
                if (dimensBytes > MaxDimensBytes)
                {
                    dimensionsInvalid = true;
                    activeDimens = false;
                }
                else dimensTokens.Add(token.ToString());
            }
            token.Clear();
            tokenBytes = 0;
        }

        private void FinishIdentifier()
        {
            if (!identifierActive) return;
            identifierActive = false;
            var keyword = identifier.ToString().ToUpperInvariant();
            identifier.Clear();
            if (keyword == "INCLUDE") Fail("ECLIPSE_INCLUDE_UNSUPPORTED", "INCLUDE is unsupported for ECLIPSE 100 MVP.");
            if (firstIdentifierSeen) return;
            firstIdentifierSeen = true;
            AddOrdered(sections, SectionKeywords, keyword);
            if (unitSystem is null && UnitKeywords.Contains(keyword)) unitSystem = keyword;
            AddOrdered(phases, PhaseKeywords, keyword);
            if (keyword == "DIMENS")
            {
                if (activeDimens) dimensionsInvalid = true;
                activeDimens = true;
                dimensBytes = 0;
                dimensTokens.Clear();
            }
        }

        private void FinishDimens()
        {
            if (!activeDimens) return;
            activeDimens = false;
            if (dimensTokens.Count != 3 || dimensTokens.Any(value => !IsPositiveBase10Integer(value)))
            {
                dimensionsInvalid = true;
                return;
            }
            if (!int.TryParse(dimensTokens[0], out var nx) || !int.TryParse(dimensTokens[1], out var ny) || !int.TryParse(dimensTokens[2], out var nz))
            {
                dimensionsInvalid = true;
                return;
            }
            if (nx > MaxDimension || ny > MaxDimension || nz > MaxDimension)
            {
                dimensionsInvalid = true;
                return;
            }
            if (dimensions is not null) dimensionsInvalid = true;
            else dimensions = new(nx, ny, nz);
        }

        private static bool IsPositiveBase10Integer(string value) => value.Length > 0 && value[0] != '0' && value.All(character => character is >= '0' and <= '9');
        private static bool IsAsciiWord(char value) => value is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '_';
        private static bool IsPvtPrefix(StringBuilder value) => value.Length == 3 && value[0] is 'P' or 'p' && value[1] is 'V' or 'v' && value[2] is 'T' or 't';
        private static int Utf8Bytes(char value) => value <= 0x7f ? 1 : value <= 0x7ff ? 2 : 3;
        private static void AddOrdered(List<string> values, IReadOnlyList<string> order, string value)
        {
            var index = Rank(order, value);
            if (index < 0 || values.Contains(value)) return;
            var insertion = values.FindIndex(existing => Rank(order, existing) > index);
            if (insertion < 0) values.Add(value); else values.Insert(insertion, value);
        }
        private static int Rank(IReadOnlyList<string> values, string value)
        {
            for (var index = 0; index < values.Count; index++) if (values[index] == value) return index;
            return -1;
        }
        private static void Fail(string code, string message) => throw new EclipseDataInspectionException(code, message);
    }

    // This lexer intentionally retains only the current record and accepted output, never the deck.
    private sealed class ScheduleParser
    {
        private const int MaxWells = 1_000;
        private const int MaxEvents = 1_000;
        private const int MaxDates = 4_000;
        private const int MaxSteps = 8_000;
        private const int MaxNameScalars = 128;
        private readonly List<string> wellNames = [];
        private readonly HashSet<string> wellNameSet = new(StringComparer.Ordinal);
        private readonly List<EclipseScheduleEvent> events = [];
        private readonly List<LexToken> record = [];
        private readonly StringBuilder token = new();
        private static readonly Regex TStepDecimal = new("^[+]?[0-9]+(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?$", RegexOptions.CultureInvariant);
        private Candidate? candidate;
        private string? lineToken;
        private int lineUnquotedTokens;
        private int lineNonSlashTokens;
        private int lineSlashTokens;
        private bool lineHasSlash;
        private bool quoted;
        private bool quotePending;
        private bool currentQuoted;
        private bool currentClosedQuote;
        private bool tokenTooLong;
        private bool comment;
        private bool previousHyphen;
        private bool previousWasCarriageReturn;
        private int acceptedDates;
        private int acceptedSteps;

        public ScheduleResult Read(TextReader reader)
        {
            int value;
            while ((value = reader.Read()) >= 0) Process((char)value);
            if (quotePending) CloseQuotedToken(closed: false);
            FinishLine(endOfFile: true);
            // An open candidate is deliberately discarded: no slash means no completed block.
            return new(wellNames, events);
        }

        private void Process(char value)
        {
            if (value == '\ufeff' && !quoted && !comment && lineUnquotedTokens == 0 && token.Length == 0) return;
            if (value == '\0') Fail("ECLIPSE_DATA_NUL_BYTE", "The ECLIPSE .DATA file contains a NUL byte.");
            if (value is '\r' or '\n')
            {
                if (quotePending) CloseQuotedToken(closed: false);
                if (value == '\n' && previousWasCarriageReturn) { previousWasCarriageReturn = false; return; }
                FinishLine(endOfFile: false);
                previousWasCarriageReturn = value == '\r';
                return;
            }
            previousWasCarriageReturn = false;
            if (comment) return;
            if (quoted)
            {
                if (quotePending)
                {
                    if (value == '\'') { Append(value); quotePending = false; return; }
                    CloseQuotedToken(closed: true);
                    Process(value);
                    return;
                }
                if (value == '\'') quotePending = true; else Append(value);
                return;
            }
            if (value == '\'')
            {
                FinishToken();
                quoted = true;
                currentQuoted = true;
                currentClosedQuote = false;
                previousHyphen = false;
                return;
            }
            if (value == '-' && previousHyphen)
            {
                if (token.Length > 0) token.Length--;
                FinishToken();
                comment = true;
                previousHyphen = false;
                return;
            }
            if (value == '/')
            {
                FinishToken();
                lineSlashTokens++;
                lineHasSlash = true;
                if (candidate is not null)
                {
                    if (record.Count > 0) FinishRecord();
                }
                previousHyphen = false;
                return;
            }
            if (char.IsWhiteSpace(value))
            {
                FinishToken();
                previousHyphen = false;
                return;
            }
            Append(value);
            previousHyphen = value == '-';
        }

        private void FinishLine(bool endOfFile)
        {
            FinishToken();
            if (candidate is not null && lineSlashTokens == 1 && lineNonSlashTokens == 0) FinishCandidate();
            if (candidate is null && !lineHasSlash && lineUnquotedTokens == 1 && IsCandidateKeyword(lineToken))
            {
                if (lineToken!.Equals("SCHEDULE", StringComparison.OrdinalIgnoreCase))
                {
                    scheduleSeen = true;
                }
                else if (scheduleSeen)
                {
                    candidate = new Candidate(lineToken.ToUpperInvariant());
                }
            }
            quoted = false;
            quotePending = false;
            comment = false;
            previousHyphen = false;
            currentQuoted = false;
            currentClosedQuote = false;
            lineToken = null;
            lineUnquotedTokens = 0;
            lineNonSlashTokens = 0;
            lineSlashTokens = 0;
            lineHasSlash = false;
        }

        private bool scheduleSeen;

        private void CloseQuotedToken(bool closed)
        {
            quoted = false;
            quotePending = false;
            currentClosedQuote = closed;
            FinishToken();
        }

        private void Append(char value)
        {
            if (token.Length < 1025) token.Append(value); else tokenTooLong = true;
        }

        private void FinishToken()
        {
            if (token.Length == 0 && !currentQuoted) return;
            var value = token.ToString();
            var lex = new LexToken(value, currentQuoted, currentClosedQuote || !currentQuoted, tokenTooLong);
            lineNonSlashTokens++;
            if (candidate is null)
            {
                if (!lex.Quoted)
                {
                    lineUnquotedTokens++;
                    if (lineUnquotedTokens == 1) lineToken = value;
                }
            }
            else
            {
                record.Add(lex);
            }
            token.Clear();
            tokenTooLong = false;
            currentQuoted = false;
            currentClosedQuote = false;
        }

        private void FinishRecord()
        {
            candidate!.AddRecord(record);
            record.Clear();
        }

        private void FinishCandidate()
        {
            var completed = candidate!;
            candidate = null;
            if (completed.Keyword == "WELSPECS")
            {
                foreach (var name in completed.Wells)
                {
                    if (wellNameSet.Contains(name)) continue;
                    if (wellNames.Count == MaxWells) Limit();
                    wellNameSet.Add(name);
                    wellNames.Add(name);
                }
                return;
            }
            if (completed.Dates.Count == 0 && completed.Steps.Count == 0) return;
            if (events.Count == MaxEvents) Limit();
            if (acceptedDates + completed.Dates.Count > MaxDates || acceptedSteps + completed.Steps.Count > MaxSteps) Limit();
            acceptedDates += completed.Dates.Count;
            acceptedSteps += completed.Steps.Count;
            events.Add(completed.Keyword == "DATES"
                ? new EclipseScheduleEvent("DATES", completed.Dates)
                : new EclipseScheduleEvent("TSTEP", Steps: completed.Steps));
        }

        private static bool IsCandidateKeyword(string? value) => value is not null &&
            (value.Equals("SCHEDULE", StringComparison.OrdinalIgnoreCase)
             || value.Equals("WELSPECS", StringComparison.OrdinalIgnoreCase)
             || value.Equals("DATES", StringComparison.OrdinalIgnoreCase)
             || value.Equals("TSTEP", StringComparison.OrdinalIgnoreCase));

        private static void Limit() => Fail("ECLIPSE_DATA_SCHEDULE_LIMIT", "The ECLIPSE .DATA schedule inspection limit was exceeded.");
        private static void Fail(string code, string message) => throw new EclipseDataInspectionException(code, message);

        private sealed class Candidate(string keyword)
        {
            public string Keyword { get; } = keyword;
            public List<string> Wells { get; } = [];
            public HashSet<string> WellSet { get; } = new(StringComparer.Ordinal);
            public List<EclipseScheduleDate> Dates { get; } = [];
            public List<string> Steps { get; } = [];

            public void AddRecord(IReadOnlyList<LexToken> tokens)
            {
                if (Keyword == "WELSPECS")
                {
                    if (tokens.Count == 0 || !TryWellName(tokens[0], out var name) || !WellSet.Add(name)) return;
                    Wells.Add(name);
                    if (Wells.Count > MaxWells) Limit();
                }
                else if (Keyword == "DATES")
                {
                    if (TryDate(tokens, out var date)) Dates.Add(date);
                    if (Dates.Count > MaxDates) Limit();
                }
                else if (Keyword == "TSTEP")
                {
                    foreach (var step in tokens)
                    {
                        if (!step.Quoted && !step.TooLong && IsFiniteDecimal(step.Value)) Steps.Add(step.Value);
                        if (Steps.Count > MaxSteps) Limit();
                    }
                }
            }

            private static bool TryWellName(LexToken token, out string name)
            {
                name = token.Value;
                if (name.Length == 0 || token.TooLong || (token.Quoted && !token.ClosedQuote) || ScalarCount(name) > MaxNameScalars || name.EnumerateRunes().Any(Rune.IsControl)) return false;
                if (ContainsSensitiveWellNameContent(name)) return false;
                if (token.Quoted) return true;
                return name.Length is >= 1 and <= 128
                    && name[0] is >= 'A' and <= 'Z' or >= 'a' and <= 'z'
                    && name.Skip(1).All(value => value is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '_' or '.' or '-');
            }

            private static bool TryDate(IReadOnlyList<LexToken> tokens, out EclipseScheduleDate date)
            {
                date = default!;
                if (tokens.Count is < 3 or > 4 || tokens.Any(token => token.Quoted || token.TooLong)) return false;
                var day = tokens[0].Value;
                var month = tokens[1].Value.ToUpperInvariant();
                var year = tokens[2].Value;
                if (!IsDay(day) || !IsMonth(month) || year.Length != 4 || !year.All(char.IsAsciiDigit)
                    || (tokens.Count == 4 && !IsTime(tokens[3].Value))) return false;
                date = new(day, month, year, tokens.Count == 4 ? tokens[3].Value : null);
                return true;
            }

            private static int ScalarCount(string value) => value.EnumerateRunes().Count();
            private static bool IsDay(string value) => int.TryParse(value, out var day) && value == day.ToString() && day is >= 1 and <= 31;
            private static bool IsMonth(string value) => value is "JAN" or "FEB" or "MAR" or "APR" or "MAY" or "JUN" or "JUL" or "AUG" or "SEP" or "OCT" or "NOV" or "DEC";
            private static bool IsTime(string value)
            {
                if (value.Length is not (5 or 8) || value[2] != ':' || (value.Length == 8 && value[5] != ':')) return false;
                if (!int.TryParse(value[..2], out var hour) || !int.TryParse(value.Substring(3, 2), out var minute)) return false;
                return value.Length == 5
                    ? hour is >= 0 and <= 23 && minute is >= 0 and <= 59
                    : int.TryParse(value[6..], out var second) && hour is >= 0 and <= 23 && minute is >= 0 and <= 59 && second is >= 0 and <= 59;
            }
            private static bool IsFiniteDecimal(string value)
            {
                return TStepDecimal.IsMatch(value)
                    && double.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var number)
                    && double.IsFinite(number)
                    && number >= 0;
            }

            private static bool ContainsSensitiveWellNameContent(string value)
            {
                if (value.Contains('/') || value.Contains('\\') || value.Contains(':') || value.Contains("..", StringComparison.Ordinal)) return true;
                var normalized = value.ToLowerInvariant();
                return normalized.Contains("net.pipe", StringComparison.Ordinal)
                    || normalized.Contains("password", StringComparison.Ordinal)
                    || normalized.Contains("passwd", StringComparison.Ordinal)
                    || normalized.Contains("secret", StringComparison.Ordinal)
                    || normalized.Contains("token", StringComparison.Ordinal)
                    || normalized.Contains("credential", StringComparison.Ordinal)
                    || normalized.Contains("cookie", StringComparison.Ordinal)
                    || normalized.Contains("authorization", StringComparison.Ordinal)
                    || normalized.Contains("bearer", StringComparison.Ordinal)
                    || normalized.Contains("license", StringComparison.Ordinal)
                    || normalized.Contains("api_key", StringComparison.Ordinal)
                    || normalized.Contains("apikey", StringComparison.Ordinal)
                    || normalized.Contains("server", StringComparison.Ordinal)
                    || normalized.Contains("environment", StringComparison.Ordinal);
            }
        }

        private sealed record LexToken(string Value, bool Quoted, bool ClosedQuote, bool TooLong);
    }

    private sealed record ScheduleResult(IReadOnlyList<string> WellNames, IReadOnlyList<EclipseScheduleEvent> Events);
}
