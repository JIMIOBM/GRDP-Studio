using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Xunit;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class NetworkInspectionTests
{
    private static NetworkDataInspection? Read(string inspection, string status = "READY", string kind = "network")
    {
        using var document = JsonDocument.Parse("{\"inspection\":" + inspection + "}");
        return NetworkInspectionReader.Read(document.RootElement, status, kind);
    }

    [Fact]
    public void ReadsNetworkCapabilityEmbeddedInAWellInspection()
    {
        var json = "{\"networkCapability\":{\"schemaVersion\":\"pipesim-network-capability/1\",\"studies\":[\"Study 1\"],\"networkInspection\":{\"schemaVersion\":\"pipesim-network-inspection/3\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Source_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":\"GasFlowRate\",\"pressure\":120,\"temperature\":80,\"gasFlowRate\":5,\"liquidFlowRate\":null,\"massFlowRate\":null}]}],\"chokes\":[]}}}";
        using var document = JsonDocument.Parse(json);
        var capability = NetworkInspectionReader.ReadCapability(document.RootElement, "READY", "basic_gas", ["Study 1", "Study 2"],
            System.Text.Json.JsonSerializer.Deserialize<IReadOnlyList<EclipsePackageFile>>(
                "[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}]",
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!);

        Assert.NotNull(capability);
        Assert.Equal("pipesim-network-capability/1", capability.SchemaVersion);
        Assert.Equal(new[] { "Study 1" }, capability.Studies);
        Assert.Single(capability.NetworkInspection.Studies);
        Assert.Single(capability.NetworkInspection.PackageFiles!);
    }

    [Fact]
    public void DoesNotAdvertiseMalformedOrUnvalidatedNetworkCapability()
    {
        const string manifestJson = "[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}]";
        var manifest = System.Text.Json.JsonSerializer.Deserialize<IReadOnlyList<EclipsePackageFile>>(
            manifestJson, new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!;
        const string inspection = "{\"schemaVersion\":\"pipesim-network-inspection/3\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Source_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":null,\"pressure\":120,\"temperature\":80,\"gasFlowRate\":null,\"liquidFlowRate\":null,\"massFlowRate\":null}]}],\"chokes\":[]}";
        foreach (var studies in new[] { "[\"Study 2\"]", "[\"Study 1\",\"Study 1\"]", "[\"../Study\"]" })
        {
            using var document = JsonDocument.Parse("{\"networkCapability\":{\"schemaVersion\":\"pipesim-network-capability/1\",\"studies\":" + studies + ",\"networkInspection\":" + inspection + "}}");
            Assert.Null(NetworkInspectionReader.ReadCapability(document.RootElement, "READY", "legacy_well", ["Study 1", "Study 2"], manifest));
        }
    }

    [Fact]
    public void AcceptsDisplaySafeBoundaryMetadata()
    {
        var inspection = Read("{\"schemaVersion\":\"pipesim-network-inspection/1\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Supply_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":\"GasFlowRate\",\"pressure\":null,\"temperature\":131,\"gasFlowRate\":1306.84,\"liquidFlowRate\":null,\"massFlowRate\":null}]}]}");
        Assert.Equal("Supply_1", inspection!.Studies[0].Boundaries[0].Node);
        Assert.Equal(1306.84, inspection.Studies[0].Boundaries[0].GasFlowRate);
    }

    [Theory]
    [InlineData("{\"schemaVersion\":\"pipesim-network-inspection/1\",\"studies\":[]}")]
    [InlineData("{\"schemaVersion\":\"pipesim-network-inspection/1\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[]}]}")]
    [InlineData("{\"schemaVersion\":\"pipesim-network-inspection/1\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Supply_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":\"Bad\",\"pressure\":null,\"temperature\":131,\"gasFlowRate\":1306.84,\"liquidFlowRate\":null,\"massFlowRate\":null}]}]}")]
    public void DiscardsMalformedMetadata(string inspection) => Assert.Null(Read(inspection));

    [Fact]
    public void OnlyReadyNetworkResponsesCanPublishInspection()
    {
        const string inspection = "{\"schemaVersion\":\"pipesim-network-inspection/1\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Supply_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":null,\"pressure\":100,\"temperature\":131,\"gasFlowRate\":null,\"liquidFlowRate\":null,\"massFlowRate\":null}]}]}";
        Assert.Null(Read(inspection, "INVALID"));
        Assert.Null(Read(inspection, kind: "basic_gas"));
        Assert.NotNull(Read(inspection));
    }

    [Fact]
    public void AcceptsV2PackageManifest()
    {
        var inspection = Read("{\"schemaVersion\":\"pipesim-network-inspection/2\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Supply_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":null,\"pressure\":100,\"temperature\":131,\"gasFlowRate\":null,\"liquidFlowRate\":null,\"massFlowRate\":null}]}],\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + new string('a', 64) + "\"}]}");

        Assert.Equal("pipesim-network-inspection/2", inspection!.SchemaVersion);
        Assert.Single(inspection.PackageFiles!);
    }

    [Fact]
    public void AcceptsV3ChokeMetadata()
    {
        var inspection = Read("{\"schemaVersion\":\"pipesim-network-inspection/3\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Supply_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":null,\"pressure\":100,\"temperature\":131,\"gasFlowRate\":null,\"liquidFlowRate\":null,\"massFlowRate\":null}]}],\"chokes\":[{\"name\":\"Choke\",\"beanSize\":2.0,\"unit\":\"in\"}],\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + new string('a', 64) + "\"}]}" );

        Assert.Equal("pipesim-network-inspection/3", inspection!.SchemaVersion);
        Assert.Equal(2.0, inspection.Chokes![0].BeanSize);
        Assert.Equal("in", inspection.Chokes[0].Unit);
    }

    [Fact]
    public void AcceptsV5UnitBoundFlowlineInventory()
    {
        var inspection = Read("{\"schemaVersion\":\"pipesim-network-inspection/5\",\"studies\":[{\"study\":\"Study 1\",\"boundaries\":[{\"node\":\"Source_1\",\"boundaryNodeType\":\"Source\",\"isActive\":true,\"isSurfaceCondition\":true,\"flowRateType\":null,\"pressure\":100,\"temperature\":131,\"gasFlowRate\":null,\"liquidFlowRate\":null,\"massFlowRate\":null}]}],\"chokes\":[],\"packageFiles\":[{\"relativePath\":\"model.pips\",\"sizeBytes\":12,\"sha256\":\"" + new string('a', 64) + "\"}],\"wellContexts\":[],\"flowlineContexts\":[\"FL-2\"],\"flowlines\":[{\"name\":\"FL-2\",\"innerDiameter\":4.0,\"unit\":\"in\"}]}");

        Assert.NotNull(inspection);
        Assert.Equal("pipesim-network-inspection/5", inspection.SchemaVersion);
        Assert.Equal("FL-2", inspection.Flowlines![0].Name);
        Assert.Equal(4.0, inspection.Flowlines[0].InnerDiameter);
        Assert.Equal("in", inspection.Flowlines[0].Unit);
    }
}
