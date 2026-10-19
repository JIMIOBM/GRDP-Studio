using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalSaveFreshOutputTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-save-fresh", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalSaveFreshOutputTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    [Fact]
    public void SaveInventoryIsOptInAndDoesNotBroadenLegacyOutputCapture()
    {
        foreach (var name in new[] { "CASE.ECLEND", "CASE.V0000", "CASE.V0010", "CASE.X0010", "CASE.V00100" }) File.WriteAllText(Path.Combine(root, name), "fresh");
        var before = new Dictionary<string, (long Size, DateTime LastWrite)>();
        Assert.Equal(["CASE.ECLEND"], EclipseRunService.Fresh(root, before).Select(Path.GetFileName));
        Assert.Equal(new[] { "CASE.ECLEND", "CASE.V0000", "CASE.V0010", "CASE.X0010" }.Order(),
            EclipseRunService.Fresh(root, before, true).Select(Path.GetFileName).Order());
    }
    [Fact]
    public void UnchangedCopiedSaveInputsAreNeverRelabelledAsFreshSolverOutputs()
    {
        var state = Path.Combine(root, "CASE.V0010"); File.WriteAllText(state, "copied-input");
        var before = new Dictionary<string, (long Size, DateTime LastWrite)> {
            [state] = (new FileInfo(state).Length, File.GetLastWriteTimeUtc(state))
        };
        Assert.Empty(EclipseRunService.Fresh(root, before, true));
        File.AppendAllText(state, "-solver-updated");
        Assert.Equal([state], EclipseRunService.Fresh(root, before, true));
    }

    [Fact]
    public void QualifiedHistoryCapturesOnlyItsSelectedSavePairAndIdentityReport()
    {
        foreach (var name in new[] { "CASE.V0000", "CASE.V0010", "CASE.X0010", "CASE.X0001", "OTHER.V0010" })
            File.WriteAllText(Path.Combine(root, name), "fresh");
        var selected = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "CASE.V0000", "CASE.V0010", "CASE.X0010" };
        var paths = EclipseRunService.Fresh(root, new(), true, selected).Select(Path.GetFileName).ToArray();
        Assert.Equal(selected.Order(), paths.Order());
    }
}
