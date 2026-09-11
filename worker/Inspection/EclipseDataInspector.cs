using System.Text;
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
            return new Parser(caseName).Read(reader);
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
}
