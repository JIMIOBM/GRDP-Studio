using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Xunit;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class WellInspectionTests
{
    private static WellDataInspection? Read(string pressure, string status = "READY", string kind = "basic_gas")
    {
        using var document = JsonDocument.Parse("{\"inspection\":{\"schemaVersion\":\"pipesim-well-inspection/1\",\"reservoirPressure\":" + pressure + "}}");
        return WellInspectionReader.Read(document.RootElement, status, kind);
    }

    [Fact]
    public void AcceptsVerifiedPressureWithoutScenarioLimitAndPreservesEclipseSerialization()
    {
        var inspection = Read("{\"value\":100001,\"unit\":\"psia\"}");
        Assert.Equal(100001, inspection!.ReservoirPressure!.Value);
        Assert.Null(Read("null")!.ReservoirPressure);
        var eclipse = new EclipseDataInspection("eclipse-data-inspection/1", "CASE.DATA", [], null, [], null);
        var response = new ModelValidationResponse("READY", [], "", Inspection: eclipse);
        var json = JsonSerializer.SerializeToElement(response, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        Assert.Equal("CASE.DATA", json.GetProperty("inspection").GetProperty("caseName").GetString());
        Assert.False(json.GetProperty("inspection").TryGetProperty("reservoirPressure", out _));
    }

    [Theory]
    [InlineData("true")]
    [InlineData("{\"value\":true,\"unit\":\"psia\"}")]
    [InlineData("{\"value\":\"4000\",\"unit\":\"psia\"}")]
    [InlineData("{\"value\":0,\"unit\":\"psia\"}")]
    [InlineData("{\"value\":1e999,\"unit\":\"psia\"}")]
    [InlineData("{\"value\":1.2345e25,\"unit\":\"psia\"}")]
    [InlineData("{\"value\":4000,\"unit\":\"bara\"}")]
    [InlineData("{\"value\":4000,\"unit\":\"psia\",\"path\":\"private\"}")]
    public void DiscardsMalformedOptionalMetadata(string pressure) => Assert.Null(Read(pressure));

    [Fact]
    public void OnlyReadySupportedWellsCanPublishInspection()
    {
        Assert.Null(Read("null", "INVALID"));
        Assert.Null(Read("null", kind: "network"));
        Assert.Null(Read("null", kind: "eclipse_100"));
        Assert.NotNull(Read("null", kind: "black_oil_liquid"));
        Assert.NotNull(Read("null", kind: "legacy_well"));
    }

    [Fact]
    public void AcceptsV2PackageManifest()
    {
        using var document = JsonDocument.Parse("{\"inspection\":{\"schemaVersion\":\"pipesim-well-inspection/2\",\"reservoirPressure\":null,\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + new string('a', 64) + "\"}]}}");
        var inspection = WellInspectionReader.Read(document.RootElement, "READY", "basic_gas");

        Assert.Equal("pipesim-well-inspection/2", inspection!.SchemaVersion);
        Assert.Single(inspection.PackageFiles!);
    }

    [Fact]
    public void PreservesValidatedWellNameInV3Inspection()
    {
        using var document = JsonDocument.Parse("{\"well\":\"Well_1\",\"inspection\":{\"schemaVersion\":\"pipesim-well-inspection/1\",\"reservoirPressure\":null}}");
        var inspection = WellInspectionReader.Read(document.RootElement, "READY", "black_oil_liquid");
        var persisted = inspection! with
        {
            SchemaVersion = "pipesim-well-inspection/3",
            PackageFiles = [new EclipsePackageFile("model.pips", 12, new string('a', 64))],
            Well = document.RootElement.GetProperty("well").GetString()
        };

        Assert.Equal("Well_1", persisted.Well);
        Assert.Equal("pipesim-well-inspection/3", persisted.SchemaVersion);
    }
}
