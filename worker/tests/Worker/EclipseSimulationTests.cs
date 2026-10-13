using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseSimulationTests
{
    private const string Deck = "RUNSPEC\nFIELD\nCOMPS\n2 /\nEOS\nPR /\nGRID\nPROPS\nNCOMPS\n2 /\nCNAMES\n'C1' 'C7+' /\nEOS\nPR /\nSOLUTION\nSUMMARY =====\nSCHEDULE\nEND\n";
    [Fact]
    public void ExplicitMetadataPreservesComponentNumberOrder()
    {
        var actual = EclipseSimulationReader.ReadText(Deck)!;
        Assert.Equal(300, actual.Solver);
        Assert.Equal("COMPOSITIONAL", actual.ModelType);
        Assert.Equal(2, actual.ComponentCount);
        Assert.Equal(new[] { "C1", "C7+" }, actual.ComponentNames);
        Assert.Equal("PR", actual.EquationOfState);
    }
    [Fact]
    public void E100AndCommentsDoNotBecomeCompositional()
    {
        Assert.Null(EclipseSimulationReader.ReadText("RUNSPEC\nFIELD\n-- COMPS\n-- 9 /\nOIL\nGRID\nPROPS\nSOLUTION\nSCHEDULE\n"));
    }
    [Theory]
    [InlineData("COMPS\n2 /", "COMPS\n2* /", false)]
    [InlineData("COMPS\n2 /", "COMPS\n101 /", false)]
    [InlineData("NCOMPS\n2 /", "NCOMPS\n3 /", false)]
    [InlineData("CNAMES\n'C1' 'C7+' /", "CNAMES\n'C1' /", false)]
    [InlineData("CNAMES\n'C1' 'C7+' /", "CNAMES\n'C1' 'C1' /", false)]
    [InlineData("CNAMES\n'C1' 'C7+' /", "CNAMES\n'C1' 'TOOLONG99' /", false)]
    [InlineData("EOS\nPR /", "EOS\nSRK /", false)]
    [InlineData("FIELD", "LAB", false)]
    [InlineData("GRID", "BLACKOIL\nGRID", false)]
    [InlineData("GRID", "THERMAL\nGRID", false)]
    [InlineData("GRID", "LGR\nGRID", false)]
    [InlineData("COMPS\n2 /", "COMPS\n2 /\nCOMPS\n2 /", false)]
    [InlineData("FIELD", "FIELD\nMETRIC", false)]
    [InlineData("FIELD", "METRIC", true)]
    [InlineData("NCOMPS\n2 /\n", "", true)]
    public void RejectsInconsistentOrOutOfScopeDeclarations(string original, string changed, bool valid)
    {
        var text = Deck.Replace(original, changed);
        if (valid) Assert.NotNull(EclipseSimulationReader.ReadText(text));
        else Assert.Throws<EclipseDeckPackageException>(() => EclipseSimulationReader.ReadText(text));
    }
}
