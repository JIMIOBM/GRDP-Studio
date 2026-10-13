using System.Buffers.Binary;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseGridRegionTests
{
    private static string Request => $$"""{"schemaVersion":"eclipse-grid-region-parameters/1","baselineRunId":74,"fieldFile":"CASE.INIT","fieldArtifactSha256":"{{new string('a',64)}}","gridFile":"CASE.EGRID","gridArtifactSha256":"{{new string('b',64)}}","keyword":"PERMX","nx":3,"ny":2,"nz":1,"i1":2,"i2":3,"j1":1,"j2":2,"k1":1,"k2":1,"originalValues":[20,30,50,60],"multiplier":0.5}""";
    [Theory]
    [InlineData("\"i2\":3", "\"i2\":4")]
    [InlineData("\"i1\":2", "\"i1\":4")]
    [InlineData("\"i1\":2", "\"i1\":1.5")]
    [InlineData("\"baselineRunId\":74", "\"baselineRunId\":\"74\"")]
    [InlineData("[20,30,50,60]", "[20,30,50]")]
    [InlineData("[20,30,50,60]", "[20,30,50,\"60\"]")]
    [InlineData("\"multiplier\":0.5", "\"multiplier\":1")]
    [InlineData("\"keyword\":\"PERMX\"", "\"keyword\":\"PRESSURE\"")]
    public void InvalidRequestsDoNotParse(string before, string after)
    {
        using var json = JsonDocument.Parse(Request.Replace(before, after));
        Assert.False(EclipseGridRegionParameters.TryParse(json.RootElement, out _));
    }
    [Theory]
    [InlineData("PERMX", "[20,30,50,60]", true)]
    [InlineData("PORO", "[0.2,0.4,0.6,1]", true)]
    [InlineData("PORO", "[0.2,0.4,0.6,1.1]", false)]
    [InlineData("PORO", "[0.2,0.4,0.6,0]", false)]
    public void AllOriginalValuesRespectPhysicalBounds(string keyword, string values, bool expected)
    {
        using var json = JsonDocument.Parse(Request.Replace("PERMX", keyword).Replace("[20,30,50,60]", values));
        Assert.Equal(expected, EclipseGridRegionParameters.TryParse(json.RootElement, out _));
    }
    [Theory]
    [InlineData("valid")]
    [InlineData("inactive")]
    [InlineData("stale")]
    [InlineData("complex")]
    public async Task ValidatesAllSelectedCellsBeforeWritingExactRegion(string fault)
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-region", Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        try
        {
            var data = Path.Combine(root, "CASE.DATA"); var init = Path.Combine(root, "CASE.INIT"); var grid = Path.Combine(root, "CASE.EGRID");
            var source = "RUNSPEC\nGRID ====\nPERMX\n 6*100 /\n" + (fault == "complex" ? "OPERATE\n /\n" : "") + "PROPS ====\nEND\n";
            await File.WriteAllTextAsync(data, source, TestContext.Current.CancellationToken);
            await File.WriteAllBytesAsync(init, Section("PERMX", "REAL", new float[] {10,20,30,40,50,fault == "stale" ? 61 : 60}.Select(v => BitConverter.SingleToInt32Bits(v))), TestContext.Current.CancellationToken);
            await File.WriteAllBytesAsync(grid, Section("GRIDHEAD", "INTE", [1,3,2,1]).Concat(Section("ACTNUM", "INTE", [1,1,1,1,1,fault == "inactive" ? 0 : 1])).ToArray(), TestContext.Current.CancellationToken);
            using var document = JsonDocument.Parse(Request); Assert.True(EclipseGridRegionParameters.TryParse(document.RootElement, out var p));
            var package = new EclipseDeckPackage(data, root, [data]);
            if (fault == "valid")
            {
                await EclipseGridRegionEditor.ApplyAsync(package, p!, init, grid, TestContext.Current.CancellationToken);
                var text = await File.ReadAllTextAsync(data, TestContext.Current.CancellationToken);
                Assert.Contains("PERMX 0.5 2 3 1 2 1 1 /", text);
                var block = "-- GRDP controlled grid-region multiplier; generated in the isolated run copy.\nMULTIPLY\n PERMX 0.5 2 3 1 2 1 1 /\n/\n";
                Assert.Equal(source, text.Replace(block, ""));
            }
            else
            {
                var error = await Assert.ThrowsAsync<EclipseDeckPackageException>(() => EclipseGridRegionEditor.ApplyAsync(package, p!, init, grid, TestContext.Current.CancellationToken));
                Assert.Equal("ECLIPSE_GRID_REGION_" + (fault == "inactive" ? "INACTIVE_CELL" : fault == "stale" ? "STALE_VALUE" : "COMPLEX_GRID"), error.Code);
                Assert.Equal(source, await File.ReadAllTextAsync(data, TestContext.Current.CancellationToken));
            }
        }
        finally { Directory.Delete(root, true); }
    }
    private static byte[] Section(string name, string type, IEnumerable<int> values)
    {
        var array = values.ToArray();
        byte[] Int(int v) { var b = new byte[4]; BinaryPrimitives.WriteInt32LittleEndian(b, v); return b; }
        byte[] Record(byte[] bytes) => Int(bytes.Length).Concat(bytes).Concat(Int(bytes.Length)).ToArray();
        return Record(Encoding.ASCII.GetBytes(name.PadRight(8)).Concat(Int(array.Length)).Concat(Encoding.ASCII.GetBytes(type)).ToArray())
            .Concat(Record(array.SelectMany(Int).ToArray())).ToArray();
    }
}
