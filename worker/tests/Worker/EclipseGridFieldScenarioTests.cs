using System.Buffers.Binary;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseGridFieldScenarioTests
{
    [Theory]
    [InlineData("")]
    [InlineData("      =================")]
    public async Task AppliesSingleCellPermxMultiplierOnlyToIsolatedDeckCopy(string decoration)
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-grid-edit", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var source = Path.Combine(root, "source.DATA");
            var work = Path.Combine(root, "work.DATA");
            var init = Path.Combine(root, "BASE.INIT");
            var originalDeck = $"RUNSPEC\nDIMENS\n 2 2 1 /\nGRID{decoration}\nPERMX\n 1 2 3 4 /\nPROPS{decoration}\nEND\n";
            await File.WriteAllTextAsync(source, originalDeck, TestContext.Current.CancellationToken);
            File.Copy(source, work);
            await File.WriteAllBytesAsync(init, KeywordSection("PERMX", "REAL", Floats(1, 2.5f, 3, 4)), TestContext.Current.CancellationToken);
            var parameters = Parameters("BASE.INIT", "2", "2.5", "0.5");
            var package = new EclipseDeckPackage(work, root, [work]);

            var message = await EclipseGridFieldEditor.ApplyAsync(package, parameters, init, TestContext.Current.CancellationToken);

            var edited = await File.ReadAllTextAsync(work, TestContext.Current.CancellationToken);
            Assert.Contains("MULTIPLY\n PERMX 0.5 2 2 1 1 1 1 /\n/\nPROPS", edited);
            Assert.Equal(originalDeck, await File.ReadAllTextAsync(source, TestContext.Current.CancellationToken));
            Assert.Contains("I=2, J=1, K=1", message);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task RefusesStaleBaselineValueAndComplexDeckBeforeWriting()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-grid-edit", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var deck = Path.Combine(root, "CASE.DATA");
            var init = Path.Combine(root, "BASE.INIT");
            await File.WriteAllTextAsync(deck, "RUNSPEC\nGRID\nPERMX\n 1 2 3 4 /\nPROPS\n", TestContext.Current.CancellationToken);
            await File.WriteAllBytesAsync(init, KeywordSection("PERMX", "REAL", Floats(1, 2.5f, 3, 4)), TestContext.Current.CancellationToken);
            var parameters = Parameters("BASE.INIT", "2", "2.4", "0.5");
            var package = new EclipseDeckPackage(deck, root, [deck]);

            var stale = await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseGridFieldEditor.ApplyAsync(package, parameters, init, TestContext.Current.CancellationToken));
            Assert.Equal("ECLIPSE_GRID_FIELD_STALE_VALUE", stale.Code);
            Assert.DoesNotContain("MULTIPLY", await File.ReadAllTextAsync(deck, TestContext.Current.CancellationToken));

            var complex = new EclipseDeckPackage(deck, root, [deck, init]);
            var unsupported = await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseGridFieldEditor.ApplyAsync(complex, Parameters("BASE.INIT", "2", "2.5", "0.5"), init, TestContext.Current.CancellationToken));
            Assert.Equal("ECLIPSE_GRID_FIELD_UNSUPPORTED_PACKAGE", unsupported.Code);
        }
        finally { Directory.Delete(root, true); }
    }

    [Theory]
    [InlineData("MULTIPLY")]
    [InlineData("BOX")]
    [InlineData("LGR")]
    public async Task DecoratedUnsupportedGridOperationsStillRejectWithoutWriting(string keyword)
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-grid-guard", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var deck = Path.Combine(root, "CASE.DATA");
            var init = Path.Combine(root, "BASE.INIT");
            var original = $"RUNSPEC\nGRID ====\nPERMX\n 1 2 3 4 /\n{keyword} ====\n /\nPROPS ====\n";
            await File.WriteAllTextAsync(deck, original, TestContext.Current.CancellationToken);
            await File.WriteAllBytesAsync(init, KeywordSection("PERMX", "REAL", Floats(1, 2.5f, 3, 4)), TestContext.Current.CancellationToken);
            var package = new EclipseDeckPackage(deck, root, [deck]);
            var error = await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseGridFieldEditor.ApplyAsync(package,
                Parameters("BASE.INIT", "2", "2.5", "0.5"), init, TestContext.Current.CancellationToken));
            Assert.Equal("ECLIPSE_GRID_FIELD_COMPLEX_GRID", error.Code);
            Assert.Equal(original, await File.ReadAllTextAsync(deck, TestContext.Current.CancellationToken));
        }
        finally { Directory.Delete(root, true); }
    }

    [Theory]
    [InlineData("2", "2", "1", "2", "1", "1", "2.5", "0.5", true)]
    [InlineData("2", "2", "1", "3", "1", "1", "2.5", "1", false)]
    [InlineData("2", "2", "1", "3", "1", "1", "0", "0.5", false)]
    [InlineData("2", "2", "1", "3", "1", "1", "2.5", "-1", false)]
    public void ParsesStrictGridFieldContract(string nx, string ny, string nz, string i, string j, string k,
        string original, string multiplier, bool expected)
    {
        var json = $$"""{"schemaVersion":"eclipse-grid-field-parameters/1","baselineRunId":74,"fieldFile":"BASE.INIT","fieldArtifactSha256":"{{new string('a', 64)}}","nx":{{nx}},"ny":{{ny}},"nz":{{nz}},"i":{{i}},"j":{{j}},"k":{{k}},"originalValue":{{original}},"multiplier":{{multiplier}}}""";
        using var document = JsonDocument.Parse(json);
        Assert.Equal(expected, EclipseGridFieldParameters.TryParse(document.RootElement, out _));
    }

    private static EclipseGridFieldParameters Parameters(string file, string i, string original, string multiplier)
    {
        using var document = JsonDocument.Parse($"{{\"schemaVersion\":\"{EclipseGridFieldParameters.Schema}\",\"baselineRunId\":74,\"fieldFile\":\"{file}\",\"fieldArtifactSha256\":\"{new string('a', 64)}\",\"nx\":2,\"ny\":2,\"nz\":1,\"i\":{i},\"j\":1,\"k\":1,\"originalValue\":{original},\"multiplier\":{multiplier}}}");
        Assert.True(EclipseGridFieldParameters.TryParse(document.RootElement, out var parameters));
        return parameters!;
    }

    private static byte[] KeywordSection(string keyword, string dataType, byte[] values)
    {
        var header = Encoding.ASCII.GetBytes(keyword.PadRight(8)[..8])
            .Concat(Int32(values.Length / 4)).Concat(Encoding.ASCII.GetBytes(dataType.PadRight(4)[..4])).ToArray();
        return Record(header).Concat(Record(values)).ToArray();
    }

    private static byte[] Floats(params float[] values) => values.SelectMany(value =>
    {
        var bytes = new byte[4];
        BinaryPrimitives.WriteInt32LittleEndian(bytes, BitConverter.SingleToInt32Bits(value));
        return bytes;
    }).ToArray();

    private static byte[] Int32(int value)
    {
        var bytes = new byte[4];
        BinaryPrimitives.WriteInt32LittleEndian(bytes, value);
        return bytes;
    }

    private static byte[] Record(byte[] value) => Int32(value.Length).Concat(value).Concat(Int32(value.Length)).ToArray();
}
