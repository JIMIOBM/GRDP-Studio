using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public static partial class EclipseRunRules
{
    public static bool IsSupportedVersion(string output) => output.Split('\n').Any(line => line.Trim() == "2024.1");
    public static bool IsFresh((long Size, DateTime LastWrite)? before, long size, DateTime lastWrite) => before is null || before.Value.Size != size || before.Value.LastWrite != lastWrite;
    public static bool HasLicenseDiagnostic(string text) => License().IsMatch(text);
    public static bool HasFatalDiagnostic(string text) => Fatal().IsMatch(text);
    public static bool RequiresKill(AdapterStopReason reason) => reason == AdapterStopReason.Cancelled;
    public static bool RequiresCheck(AdapterStopReason reason) => reason is AdapterStopReason.None or AdapterStopReason.Cancelled or AdapterStopReason.TimedOut;
    [GeneratedRegex("license", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)] private static partial Regex License();
    [GeneratedRegex("fatal", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)] private static partial Regex Fatal();
}
