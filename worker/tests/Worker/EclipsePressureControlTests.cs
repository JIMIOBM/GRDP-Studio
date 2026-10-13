using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipsePressureControlTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-pressure-control", Guid.NewGuid().ToString("N"));
    public EclipsePressureControlTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private const string Deck = "RUNSPEC\nFIELD\nSCHEDULE\nWCONPROD\n 'P1' 'OPEN' 'ORAT' 20000 4* 3000 / -- retain limits\n/\nWCONINJE\n 'I1' WATER OPEN RESV 1* 1000 6000 /\n/\nTSTEP\n10 /\nEND\n";
    private static string Request(bool injection = false) => JsonSerializer.Serialize(new {
        schemaVersion = "eclipse-pressure-control-parameters/1", baselineRunId = 1, well = injection ? "I1" : "P1", phase = "INITIAL",
        sourceFile = "CASE.DATA", lineNumber = injection ? 8 : 5, keyword = injection ? "WCONINJE" : "WCONPROD",
        originalControlMode = injection ? "RESV" : "ORAT", originalBhp = injection ? 6000 : 3000,
        targetBhp = injection ? 5000 : 3500, unit = "psia", injectionType = injection ? "WATER" : "NONE"
    });
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ExpandedDefaultsLocateTheCorrectPressureAndPreserveAllOtherBytes(bool injection)
    {
        using var doc = JsonDocument.Parse(Request(injection));
        Assert.True(EclipsePressureControlParameters.TryParse(doc.RootElement, out var p));
        var path = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(path, Deck, TestContext.Current.CancellationToken);
        await EclipsePressureControlEditor.ApplyAsync(new(path, root, [path]), p!, TestContext.Current.CancellationToken);
        var expected = injection ? Deck.Replace("OPEN RESV 1* 1000 6000", "OPEN 'BHP' 1* 1000 5000") : Deck.Replace("'ORAT' 20000 4* 3000", "'BHP' 20000 4* 3500");
        Assert.Equal(expected, await File.ReadAllTextAsync(path, TestContext.Current.CancellationToken));
    }
    [Theory]
    [InlineData("unit", "\"psia\"", "\"barsa\"")]
    [InlineData("path", "CASE.DATA", "../CASE.DATA")]
    [InlineData("fluid", "\"injectionType\":\"NONE\"", "\"injectionType\":\"WATER\"")]
    [InlineData("negative", "\"targetBhp\":3500", "\"targetBhp\":-1")]
    [InlineData("extra", "\"baselineRunId\":1", "\"baselineRunId\":1,\"status\":\"SHUT\"")]
    public void BadContractsReject(string name, string from, string to)
    {
        using var doc = JsonDocument.Parse(Request().Replace(from, to));
        Assert.False(EclipsePressureControlParameters.TryParse(doc.RootElement, out _), name);
    }
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ReportMnemonicIsNotARealRestartKeyword(bool restart)
    {
        var report = restart ? "SOLUTION\nRESTART\n 'BASE' 10 /\n" : "SOLUTION\nRPTSOL\n RESTART=2 /\n";
        var text = Deck.Replace("SCHEDULE\n", report + "SCHEDULE\n");
        using var doc = JsonDocument.Parse(Request().Replace("\"lineNumber\":5", "\"lineNumber\":8"));
        Assert.True(EclipsePressureControlParameters.TryParse(doc.RootElement, out var p));
        var path = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(path, text, TestContext.Current.CancellationToken);
        var package = new EclipseDeckPackage(path, root, [path]);
        if (restart)
        {
            await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipsePressureControlEditor.ApplyAsync(package, p!, TestContext.Current.CancellationToken));
            Assert.Equal(text, await File.ReadAllTextAsync(path, TestContext.Current.CancellationToken));
        }
        else
        {
            await EclipsePressureControlEditor.ApplyAsync(package, p!, TestContext.Current.CancellationToken);
            Assert.Equal(text.Replace("'ORAT' 20000 4* 3000", "'BHP' 20000 4* 3500"), await File.ReadAllTextAsync(path, TestContext.Current.CancellationToken));
        }
    }
    [Theory]
    [InlineData("original")]
    [InlineData("line")]
    [InlineData("default")]
    [InlineData("repeated")]
    [InlineData("duplicate")]
    [InlineData("template")]
    [InlineData("include")]
    [InlineData("shut")]
    [InlineData("override")]
    public async Task StaleAmbiguousOrDefaultedPressureRejectsBeforeWriting(string fault)
    {
        var text = fault switch {
            "default" => Deck.Replace("4* 3000", "5*"),
            "repeated" => Deck.Replace("4* 3000", "3* 2*3000"),
            "duplicate" => Deck.Replace("/ -- retain limits", "/\n 'P1' 'OPEN' 'ORAT' 20000 4* 3000 / -- retain limits"),
            "template" => Deck.Replace("'P1'", "'P*'"),
            "include" => Deck + "INCLUDE\n 'OTHER.INC' /\n",
            "shut" => Deck.Replace("'OPEN'", "'SHUT'"),
            "override" => Deck.Replace("TSTEP", "WELTARG\n 'P1' BHP 4000 /\n/\nTSTEP"),
            _ => Deck
        };
        var request = Request();
        if (fault == "original") request = request.Replace("\"originalBhp\":3000", "\"originalBhp\":2900");
        if (fault == "line") request = request.Replace("\"lineNumber\":5", "\"lineNumber\":4");
        using var doc = JsonDocument.Parse(request);
        Assert.True(EclipsePressureControlParameters.TryParse(doc.RootElement, out var p));
        var path = Path.Combine(root, "CASE.DATA");
        await File.WriteAllTextAsync(path, text, TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipsePressureControlEditor.ApplyAsync(new(path, root, [path]), p!, TestContext.Current.CancellationToken));
        Assert.Equal(text, await File.ReadAllTextAsync(path, TestContext.Current.CancellationToken));
    }
}
