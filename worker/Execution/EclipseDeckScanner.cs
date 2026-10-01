namespace Grdp.SoftwareIntegration.Worker.Execution;

public static class EclipseDeckScanner
{
    public static bool ContainsInclude(TextReader reader)
    {
        var quoted = false;
        var commented = false;
        var token = new Queue<char>(9);
        var previous = '\0';
        int current;
        while ((current = reader.Read()) >= 0)
        {
            var value = (char)current;
            if (commented)
            {
                if (value is '\r' or '\n') commented = false;
                previous = value;
                continue;
            }
            if (quoted)
            {
                if (value == '\'')
                {
                    if (reader.Peek() == '\'') reader.Read();
                    else quoted = false;
                }
                continue;
            }
            if (value == '\'')
            {
                quoted = true;
                token.Clear();
                previous = value;
                continue;
            }
            if (value == '-' && previous == '-')
            {
                commented = true;
                token.Clear();
                previous = value;
                continue;
            }
            token.Enqueue(value);
            if (token.Count > 9) token.Dequeue();
            if (!IsAsciiWord(value) && IsIncludeToken(token, false)) return true;
            previous = value;
        }
        return IsIncludeToken(token, true);
    }

    private static bool IsIncludeToken(IEnumerable<char> value, bool endOfFile)
    {
        var characters = value.ToArray();
        var length = endOfFile ? 7 : 8;
        if (characters.Length < length) return false;
        var start = characters.Length - length;
        if (start > 0 && IsAsciiWord(characters[start - 1])) return false;
        for (var index = 0; index < 7; index++)
        {
            if (char.ToUpperInvariant(characters[start + index]) != "INCLUDE"[index]) return false;
        }
        return endOfFile || !IsAsciiWord(characters[start + 7]);
    }

    private static bool IsAsciiWord(char value) => value is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '_';
}
