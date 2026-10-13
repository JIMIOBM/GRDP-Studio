using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseAdvancedInjectionTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-advanced-injection", Guid.NewGuid().ToString("N"));
    public EclipseAdvancedInjectionTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private static string Sample(string name)
    {
        return File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", $"ECLIPSE_GROUP_{name}.DATA"));
    }
    [Theory][InlineData("OIL_INJECTION", "OIL", "RATE", "STB/day", 2500)]
    [InlineData("GAS_RESV", "GAS", "RESV", "RB/day", 5000)]
    [InlineData("GAS_REIN", "GAS", "REIN", "fraction", 0.1)]
    [InlineData("GAS_VREP", "GAS", "VREP", "fraction", 0.1)]
    public async Task ActualReferenceSamplesExposeIndependentUnitsAndOnlyTargetTokenChanges(string name, string phase, string mode, string unit, double target)
    {
        var text = Sample(name); var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        var inspected = EclipseDataInspector.InspectPackage([file], "CASE.DATA");
        Assert.Equal("eclipse-data-inspection/11", inspected.SchemaVersion); Assert.Null(inspected.GasInjectionGroupControls);
        var found = Assert.Single(EclipseAdvancedInjectionReader.Read(text, "CASE.DATA")); var c = found.Control;
        Assert.Equal(unit, c.Unit); Assert.Equal(phase, c.InjectionPhase); Assert.Equal(mode, c.ControlMode);
        Assert.Equal(4, c.Members.Count); Assert.All(c.Members, m => { Assert.Equal(4900, m.BhpLimit); Assert.Equal(50000, m.SurfaceRateLimit); });
        Assert.Equal(mode is "REIN" or "VREP" ? "G" : null, c.ReferenceGroup);
        Assert.Equal(mode is "REIN" or "VREP" ? 4 : 0, c.ReferenceMembers.Count);
        var p = new EclipseAdvancedInjectionParameters(1, c.Group, c.SourceFile, c.LineNumber, mode, phase, unit, c.OriginalTargetRate, target, c.Members.Select(m => m.Well).ToArray(), c.ReferenceGroup, c.ReferenceMembers);
        await EclipseAdvancedInjectionEditor.ApplyAsync(new(file, root, [file]), p, TestContext.Current.CancellationToken);
        var expected = text[..found.Start] + target.ToString("G17", System.Globalization.CultureInfo.InvariantCulture) + text[(found.Start + found.Length)..];
        Assert.Equal(expected, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Theory][InlineData("wrongReference")][InlineData("missingSourceWell")][InlineData("sourceInjector")][InlineData("sourceShut")][InlineData("extraGroupLimit")][InlineData("wellResvLimit")][InlineData("consumption")][InlineData("tree")][InlineData("dynamic")]
    public void UnsupportedProductionReferencesAndExtraConstraintsAreNotOffered(string fault)
    {
        var text = Sample("GAS_REIN");
        text = fault switch {
            "wrongReference" => text.Replace("4* 'G' /", "4* 'OTHER' /"),
            "missingSourceWell" => text.Replace("'PRODUCR1' 'OPEN'", "'UNKNOWN' 'OPEN'"),
            "sourceInjector" => text.Replace("'INJECTR1' 'I'", "'INJECTR1' 'G'"),
            "sourceShut" => text.Replace("'PRODUCR1' 'OPEN'", "'PRODUCR1' 'SHUT'"),
            "extraGroupLimit" => text.Replace("'REIN' 2* 0.2", "'REIN' 100 1* 0.2"),
            "wellResvLimit" => text.Replace("'GRUP' 50000 1* 4900", "'GRUP' 50000 1000 4900"),
            "consumption" => text.Replace("TSTEP", "GCONSUMP\n G 100 /\n/\nTSTEP"),
            "tree" => text.Replace("TSTEP", "GRUPTREE\n I FIELD /\n/\nTSTEP"),
            _ => text.Replace("TSTEP", "UDQ\n DEFINE GUX 1 /\n/\nTSTEP")
        };
        Assert.Empty(EclipseAdvancedInjectionReader.Read(text, "CASE.DATA"));
    }
    [Theory][InlineData("source")][InlineData("referenceGroup")][InlineData("referenceMembers")][InlineData("unit")][InlineData("mode")][InlineData("phase")]
    public async Task StaleReferenceNeverChangesInput(string fault)
    {
        var text = Sample("GAS_REIN"); var file = Path.Combine(root, "CASE.DATA"); await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        var c = Assert.Single(EclipseAdvancedInjectionReader.Read(text, "CASE.DATA")).Control;
        var p = new EclipseAdvancedInjectionParameters(1, c.Group, c.SourceFile, c.LineNumber, c.ControlMode, c.InjectionPhase, c.Unit, c.OriginalTargetRate, 0.1, c.Members.Select(m => m.Well).ToArray(), c.ReferenceGroup, c.ReferenceMembers);
        p = fault switch { "source" => p with { SourceFile = "OTHER.DATA" }, "referenceGroup" => p with { ReferenceGroup = "OTHER" }, "referenceMembers" => p with { ReferenceMembers = ["PRODUCR1"] }, "unit" => p with { Unit = "Mscf/day" }, "mode" => p with { ControlMode = "VREP" }, _ => p with { InjectionPhase = "OIL" } };
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseAdvancedInjectionEditor.ApplyAsync(new(file, root, [file]), p, TestContext.Current.CancellationToken));
        Assert.Equal(text, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Fact] public void StrictReferenceShapeRequiresImmutableSourceAndCannotUseLegacyGasRateSchema()
    {
        const string valid = """{"schemaVersion":"eclipse-advanced-injection-parameters/1","baselineRunId":1,"group":"I","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":20,"controlMode":"REIN","injectionPhase":"GAS","unit":"fraction","originalTargetRate":0.2,"targetRate":0.1,"members":["I1","I2"],"referenceGroup":"G","referenceMembers":["P1","P2"]}""";
        using var good = JsonDocument.Parse(valid); Assert.True(EclipseAdvancedInjectionParameters.TryParse(good.RootElement, out _));
        Assert.False(EclipseGroupGasInjectionParameters.TryParse(good.RootElement, out _));
        foreach (var bad in new[] { valid.Replace("fraction", "RB/day"), valid.Replace("\"referenceGroup\":\"G\"", "\"referenceGroup\":null"), valid.Replace("\"P2\"", "\"P1\""), valid.Replace("\"targetRate\":0.1", "\"targetRate\":0.1,\"extra\":0") })
        { using var d = JsonDocument.Parse(bad); Assert.False(EclipseAdvancedInjectionParameters.TryParse(d.RootElement, out _)); }
    }
}
