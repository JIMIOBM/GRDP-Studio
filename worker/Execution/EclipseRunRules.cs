using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public static partial class EclipseRunRules
{
    public static bool IsDataStorageKey(string? storageKey) => !string.IsNullOrWhiteSpace(storageKey) &&
        string.Equals(Path.GetExtension(storageKey), ".DATA", StringComparison.OrdinalIgnoreCase);
    public static bool IsSupportedVersion(string output) => output.Split('\n').Any(line => line.Trim() == "2024.1");
    public static bool IsFresh((long Size, DateTime LastWrite)? before, long size, DateTime lastWrite) => before is null || before.Value.Size != size || before.Value.LastWrite != lastWrite;
    public static bool HasLicenseDiagnostic(string text) => License().IsMatch(text);
    public static bool HasFatalDiagnostic(string text) => Fatal().IsMatch(text);
    public static bool RequiresKill(AdapterStopReason reason) => reason == AdapterStopReason.Cancelled;
    public static bool RequiresCheck(AdapterStopReason reason) => reason is AdapterStopReason.None or AdapterStopReason.Cancelled or AdapterStopReason.TimedOut;
    [GeneratedRegex(@"LICENSE\s+(?:FAILURE|ERROR|(?:IS\s+)?UNAVAILABLE)|LICENSE.{0,80}(?:CHECKOUT|CHECK OUT).{0,80}(?:FAIL|ERROR)|(?:CHECKOUT|CHECK OUT).{0,80}LICENSE.{0,80}(?:FAIL|ERROR)|FLEX(?:LM|NET)?.{0,80}ERROR", RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.CultureInvariant)] private static partial Regex License();
    [GeneratedRegex(@"\bFATAL\b|\bABORT(?:ED|ING)?\b|TERMINATING\s+WITH\s+ERROR", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)] private static partial Regex Fatal();
}
