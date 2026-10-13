using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseNewDatesTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-newdates", Guid.NewGuid().ToString("N"));
    public EclipseNewDatesTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Deck = "RUNSPEC\nFIELD\nSTART\n1 JAN 1996 /\nSCHEDULE\nWELSPECS\n P1 G 1 1 1* OIL /\n/\nCOMPDAT\n P1 1 1 1 1 OPEN /\n/\nWCONPROD\n P1 OPEN BHP 5* 3000 /\n/\nDATES\n6 JAN 1996 /\n/\nWCONPROD\n P1 OPEN BHP 5* 3200 /\n/\nDATES\n26 JAN 1996 /\n/\nEND\n";
    private static EclipseNewDatesParameters P => new(1, "P1", "CASE.DATA", 15, "1996-01-06", 21, "1996-01-26", "1996-01-11", "1996-01-21");
    [Fact] public async Task InsertsAfterSameDateControlsBeforeNextAdvanceAndPreservesEveryOriginalByte()
    {
        var file = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(file, Deck, TestContext.Current.CancellationToken);
        await EclipseNewDatesEditor.ApplyAsync(new(file, root, [file]), P, TestContext.Current.CancellationToken);
        var updated = await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken);
        var insertion = updated.IndexOf("-- GRDP-Studio new DATES", StringComparison.Ordinal);
        Assert.True(insertion > updated.IndexOf("3200", StringComparison.Ordinal));
        Assert.Contains("11 'JAN' 1996 /\n/\nWELOPEN\n 'P1' 'SHUT' /\n/", updated);
        Assert.Contains("21 'JAN' 1996 /\n/\nWELOPEN\n 'P1' 'OPEN' /\n/", updated);
        Assert.EndsWith("DATES\n26 JAN 1996 /\n/\nEND\n", updated);
        Assert.Equal(Deck, updated.Remove(insertion, updated.IndexOf("DATES\n26 JAN", insertion, StringComparison.Ordinal) - insertion));
    }
    [Theory]
    [InlineData("line")][InlineData("date")][InlineData("tstep")][InlineData("multiple")][InlineData("backwards")]
    [InlineData("restart")][InlineData("include")][InlineData("action")][InlineData("wildcard")][InlineData("shut")][InlineData("no-connection")]
    public async Task AmbiguousOrStaleSourcesNeverWrite(string fault)
    {
        var text = fault switch {
            "tstep" => Deck.Replace("DATES\n26", "TSTEP\n1 /\nDATES\n26"),
            "multiple" => Deck.Replace("6 JAN 1996 /", "6 JAN 1996 /\n7 JAN 1996 /"),
            "backwards" => Deck.Replace("26 JAN", "2 JAN"),
            "restart" => Deck.Replace("FIELD", "FIELD\nRESTART\nOLD 1 /"),
            "include" => Deck.Replace("SCHEDULE", "SCHEDULE\nINCLUDE\n'X.SCH' /"),
            "action" => Deck.Replace("SCHEDULE", "SCHEDULE\nACTIONX\nX /\n/"),
            "wildcard" => Deck.Replace("P1 OPEN BHP", "'*' OPEN BHP"),
            "shut" => Deck.Replace("P1 OPEN BHP", "P1 SHUT BHP"),
            "no-connection" => Deck.Replace("1 OPEN /", "1 SHUT /"), _ => Deck };
        var p = fault == "line" ? P with { BeforeLineNumber = 17 } : fault == "date" ? P with { BeforeDate = "1996-01-07" } : P;
        var file = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(file, text, TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseNewDatesEditor.ApplyAsync(new(file, root, [file]), p, TestContext.Current.CancellationToken));
        Assert.Equal(text, await File.ReadAllTextAsync(file, TestContext.Current.CancellationToken));
    }
    [Theory]
    [InlineData("1996-02-30", "1996-01-21")][InlineData("1996-01-06", "1996-01-21")]
    [InlineData("1996-01-21", "1996-01-11")][InlineData("1996-01-11", "1996-01-26")]
    public void ContractRejectsInvalidCalendarOrderAndBoundaries(string shut, string open)
    {
        var json = JsonSerializer.Serialize(new { schemaVersion = "eclipse-new-dates-parameters/1", baselineRunId = 1, well = "P1", sourceFile = "CASE.DATA", beforeLineNumber = 16, beforeDate = "1996-01-06", afterLineNumber = 22, afterDate = "1996-01-26", shutDate = shut, openDate = open });
        using var doc = JsonDocument.Parse(json);
        Assert.False(EclipseNewDatesParameters.TryParse(doc.RootElement, out _));
    }
}
