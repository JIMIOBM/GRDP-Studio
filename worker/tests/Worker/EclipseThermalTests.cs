using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseThermalTests
{
    private const string Deck = "RUNSPEC\nFIELD\nOIL\nWATER\nGAS\nCOMPS\n3 /\nTHERMAL\nGRID\nPROPS\nCNAMES\nC1 C2 HEAVY /\nSOLUTION\nSUMMARY\nSCHEDULE\n";

    [Fact]
    public void RecognizesExplicitThermalWithDefaultLiveOilAndSeparateNativeIdentifier()
    {
        var model = EclipseThermalReader.ReadText(Deck)!;
        Assert.Equal(300, model.Solver); Assert.Equal(500, model.SummaryProgramIdentifier);
        Assert.Equal("THERMAL", model.ModelType); Assert.Equal("LIVEOIL", model.FluidModel);
        Assert.Equal(3, model.ComponentCount); Assert.Equal(new[] { "C1", "C2", "HEAVY" }, model.ComponentNames);
        Assert.Null(EclipseThermalReader.ReadText("RUNSPEC\n-- THERMAL\nFIELD\nOIL\n"));
    }

    [Theory]
    [InlineData("FIELD", "METRIC", true)]
    [InlineData("PROPS", "PROPS\nNCOMPS\n3 /", true)]
    [InlineData("THERMAL", "LIVEOIL\nTHERMAL", true)]
    [InlineData("COMPS\n3 /", "COMPS\n2 /", false)]
    [InlineData("THERMAL", "THERMAL\nTHERMAL", false)]
    [InlineData("FIELD", "LAB", false)]
    [InlineData("THERMAL", "DEADOIL\nTHERMAL", false)]
    [InlineData("THERMAL", "RADIAL\nTHERMAL", false)]
    [InlineData("THERMAL", "BLACKOIL\nTHERMAL", false)]
    [InlineData("THERMAL", "REACTION\n2 /\nTHERMAL", false)]
    [InlineData("PROPS", "PROPS\nNCOMPS\n2 /", false)]
    [InlineData("C1 C2 HEAVY", "C1 C1 HEAVY", false)]
    [InlineData("WATER\n", "", false)]
    [InlineData("THERMAL", "THERMAL\nEOS\nPR /", false)]
    [InlineData("THERMAL", "THERMAL\nLGR", false)]
    [InlineData("THERMAL\nGRID", "GRID\nTHERMAL", false)]
    public void RejectsWrongModeGeometryPhasesOrIncompleteComponentMetadata(string before, string after, bool valid)
    {
        var source = Deck.Replace(before, after, StringComparison.Ordinal);
        if (valid) Assert.NotNull(EclipseThermalReader.ReadText(source));
        else Assert.Throws<EclipseDeckPackageException>(() => EclipseThermalReader.ReadText(source));
    }
}
