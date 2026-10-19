using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseCompositionalSaveCatalogTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-save-catalog", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalSaveCatalogTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private (EclipseDeckPackage Package, string[] Files) Inputs()
    {
        var model = Path.Combine(root, "model"); var outputs = Path.Combine(root, "outputs");
        Directory.CreateDirectory(model); Directory.CreateDirectory(outputs);
        var main = Path.Combine(model, "SPE3_GASCOND.DATA");
        File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"), main);
        var files = new[] { "SPE3_GASCOND.V0000", "SPE3_GASCOND.V0010", "SPE3_GASCOND.X0010" }.Select(name => {
            var path = Path.Combine(outputs, name); File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures/SAVE-HISTORY", name), path); return path;
        }).ToArray();
        return (new EclipseDeckPackageResolver().Resolve(main, model), files);
    }

    [Fact]
    public void ActualNativeSaveCatalogBindsOriginalClockGridComponentsAndEveryOpaqueFileHash()
    {
        var input = Inputs(); var catalog = EclipseCompositionalSaveCatalogReader.Read(input.Package, input.Files, "2024.1");
        Assert.Equal("eclipse-compositional-save-catalog/1", catalog.SchemaVersion); Assert.Equal(300, catalog.Solver);
        Assert.Equal("1990-01-01", catalog.StartDate); Assert.Equal("1998-12-30", catalog.Date);
        Assert.Equal(10, catalog.ReportNumber); Assert.Equal(3285, catalog.TimeDays); Assert.Equal(5475, catalog.EndTimeDays);
        Assert.Equal(new(9, 9, 4), catalog.Dimensions); Assert.Equal(324, catalog.ActiveCells); Assert.Equal("FIELD", catalog.UnitSystem);
        Assert.Equal(9, catalog.ComponentCount); Assert.Equal("C1", catalog.ComponentNames[2]); Assert.Equal("PR", catalog.EquationOfState);
        Assert.Equal(3, catalog.Files.Count);
        foreach (var file in catalog.Files)
        {
            var path = input.Files.Single(p => Path.GetFileName(p) == file.Name);
            Assert.Equal(new FileInfo(path).Length, file.SizeBytes);
            Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(path))), file.Sha256);
            Assert.Equal("eclipse-output-" + file.Name, file.ArtifactName);
        }
        Assert.DoesNotContain(root, JsonSerializer.Serialize(catalog));
        File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "eclipse-compositional-save-catalog.json"),
            JsonSerializer.Serialize(catalog, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
    }

    [Theory]
    [InlineData(94, 100)] [InlineData(2, 1)] [InlineData(8, 8)] [InlineData(67, 11)] [InlineData(64, 29)]
    public void WrongNativeSolverUnitsGridReportOrDateCannotBeOfferedAsThisSavePoint(int index, int value)
    {
        var input = Inputs(); var path = input.Files[2]; var fields = EclipseBinaryFieldIndexParser.TryParse(path); Assert.NotNull(fields);
        var header = fields.Sections.Single(s => s.Keyword == "INTEHEAD"); Assert.Single(header.Segments);
        var bytes = File.ReadAllBytes(path); BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(checked((int)header.Segments[0].Offset + index * 4), 4), value);
        File.WriteAllBytes(path, bytes);
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveCatalogReader.Read(input.Package, input.Files, "2024.1"));
    }

    [Theory]
    [InlineData(0)] [InlineData(1)] [InlineData(2)]
    public void MissingNonrecurrentSaveStateSaveOrIdentityReportCannotCreateCatalog(int missing)
    {
        var input = Inputs();
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveCatalogReader.Read(input.Package, input.Files.Where((p, j) => j != missing).ToArray(), "2024.1"));
    }

    [Fact]
    public void DuplicateOrAmbiguousFreshSaveAndOtherReleaseAreRejected()
    {
        var input = Inputs();
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveCatalogReader.Read(input.Package, input.Files, "2025.1"));
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveCatalogReader.Read(input.Package, [.. input.Files, input.Files[1]], "2024.1"));
        var extra = Path.Combine(Path.GetDirectoryName(input.Files[1])!, "SPE3_GASCOND.V0020"); File.Copy(input.Files[1], extra);
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveCatalogReader.Read(input.Package, [.. input.Files, extra], "2024.1"));
    }

    [Fact]
    public void ElapsedClockAndComponentIdentityMustMatchEvenWhenOtherHeadersAreValid()
    {
        var input = Inputs(); var path = input.Files[2]; var fields = EclipseBinaryFieldIndexParser.TryParse(path); Assert.NotNull(fields);
        var original = File.ReadAllBytes(path); var changed = original.ToArray();
        var header = fields.Sections.Single(s => s.Keyword == "DOUBHEAD");
        BinaryPrimitives.WriteInt64BigEndian(changed.AsSpan(checked((int)header.Segments[0].Offset), 8), BitConverter.DoubleToInt64Bits(3284));
        File.WriteAllBytes(path, changed);
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveCatalogReader.Read(input.Package, input.Files, "2024.1"));
        changed = original.ToArray(); var names = fields.Sections.Single(s => s.Keyword == "ZCOMPS");
        changed[checked((int)names.Segments[0].Offset)] = (byte)'X'; File.WriteAllBytes(path, changed);
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveCatalogReader.Read(input.Package, input.Files, "2024.1"));
    }

    [Fact]
    public void ForeignWellNamesCannotBeBoundToTheSameGridAndComponentHistory()
    {
        var input = Inputs(); var path = input.Files[2]; var fields = EclipseBinaryFieldIndexParser.TryParse(path); Assert.NotNull(fields);
        var original = File.ReadAllBytes(path); var wells = fields.Sections.Single(s => s.Keyword == "ZWEL");
        original[checked((int)wells.Segments[0].Offset)] = (byte)'X'; File.WriteAllBytes(path, original);
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveCatalogReader.Read(input.Package, input.Files, "2024.1"));
    }

    [Fact]
    public void SolverOutputsDoNotInvalidateThePrelaunchSourceSnapshotButInputChangesStillDo()
    {
        var input = Inputs(); var snapshot = EclipseCompositionalSaveSourceReader.Read(input.Package); Assert.NotNull(snapshot);
        var outputs = input.Files.Select(p => {
            var destination = Path.Combine(input.Package.Root, Path.GetFileName(p)); File.Copy(p, destination); return destination;
        }).ToArray();
        Assert.Null(EclipseCompositionalSaveSourceReader.Read(input.Package));
        var catalog = EclipseCompositionalSaveCatalogReader.Read(input.Package, outputs, "2024.1", verifiedSource: snapshot);
        Assert.Equal(snapshot.Production.SourceSha256, catalog.SourceSha256); Assert.Equal(10, catalog.ReportNumber);
        File.AppendAllText(input.Package.MainFile, "\n-- changed after qualification\n");
        Assert.Throws<InvalidDataException>(() => EclipseCompositionalSaveCatalogReader.Read(input.Package, outputs, "2024.1", verifiedSource: snapshot));
    }
}
