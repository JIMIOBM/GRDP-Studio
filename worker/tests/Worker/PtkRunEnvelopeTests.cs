using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class PtkRunEnvelopeTests
{
    [Fact]
    public void AcceptsCompletedNetworkPartialWithControlledWarning()
    {
        using var document = JsonDocument.Parse("""
            {
              "status": "partial",
              "result": {
                "schemaVersion": "pipesim-network-result/1",
                "model_kind": "network",
                "runTask": "network",
                "resultContract": "VALID_PARTIAL",
                "study": "Study 1",
                "simulationState": "Completed",
                "topology": {
                  "nodes": [{ "id": "Source 1", "componentType": "SOURCE" }, { "id": "Sink 1", "componentType": "SINK" }],
                  "edges": [{ "source": "Source 1", "destination": "Sink 1", "sourcePort": "OUTLET" }],
                  "counts": { "nodes": 2, "edges": 1, "sources": 1, "sinks": 1, "flowlines": 0 }
                }
              },
              "warnings": [{
                "category": "PROTOCOL",
                "code": "NETWORK_RESULT_LIMITED",
                "message": "PIPESIM Network returned partial display data; full result validation did not pass.",
                "retryable": false
              }]
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(
            document.RootElement, "network", out var status, out var result, out var error, out var warning);

        Assert.True(accepted);
        Assert.Equal("partial", status);
        Assert.Equal("VALID_PARTIAL", result?.GetProperty("resultContract").GetString());
        Assert.Null(error);
        Assert.Equal("NETWORK_RESULT_LIMITED", warning?.Code);
    }

    [Theory]
    [InlineData("partial", "VALID_FULL", "network")]
    [InlineData("ok", "VALID_PARTIAL", "network")]
    [InlineData("partial", "VALID_PARTIAL", "combined")]
    public void RejectsNetworkStatusContractOrRunTaskMismatch(string status, string contract, string runTask)
    {
        using var document = JsonDocument.Parse($$"""
            {
              "status": "{{status}}",
              "result": {
                "schemaVersion": "pipesim-network-result/1",
                "model_kind": "network",
                "runTask": "{{runTask}}",
                "resultContract": "{{contract}}",
                "study": "Study 1",
                "simulationState": "Completed",
                "topology": {
                  "nodes": [{ "id": "Source 1", "componentType": "SOURCE" }, { "id": "Sink 1", "componentType": "SINK" }],
                  "edges": [{ "source": "Source 1", "destination": "Sink 1", "sourcePort": "OUTLET" }],
                  "counts": { "nodes": 2, "edges": 1, "sources": 1, "sinks": 1, "flowlines": 0 }
                },
                "system": [], "node": [], "profiles": [{}], "summary": {}, "messages": [], "quality": []
              },
              "warnings": [{ "category": "PROTOCOL", "code": "NETWORK_RESULT_LIMITED", "message": "limited", "retryable": false }]
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(
            document.RootElement, "network", out _, out _, out _, out _);

        Assert.False(accepted);
    }

    [Theory]
    [InlineData("partial", "VALID_PARTIAL", "{ \"nodes\": [{}], \"edges\": [{ \"source\": \"Source 1\", \"destination\": \"Sink 1\", \"sourcePort\": \"OUTLET\" }], \"counts\": { \"nodes\": 1, \"edges\": 1, \"sources\": 1, \"sinks\": 1, \"flowlines\": 0 } }")]
    [InlineData("ok", "VALID_FULL", "{ \"nodes\": [{ \"id\": \"Source 1\", \"componentType\": \"SOURCE\" }, { \"id\": \"Sink 1\", \"componentType\": \"SINK\" }], \"edges\": [{}], \"counts\": { \"nodes\": 2, \"edges\": 1, \"sources\": 1, \"sinks\": 1, \"flowlines\": 0 } }")]
    [InlineData("partial", "VALID_PARTIAL", "{ \"nodes\": [{ \"id\": \"Source 1\", \"componentType\": \"SOURCE\" }, { \"id\": \"Source 1\", \"componentType\": \"SINK\" }], \"edges\": [{ \"source\": \"Source 1\", \"destination\": \"Source 1\", \"sourcePort\": \"OUTLET\" }], \"counts\": { \"nodes\": 2, \"edges\": 1, \"sources\": 1, \"sinks\": 1, \"flowlines\": 0 } }")]
    [InlineData("ok", "VALID_FULL", "{ \"nodes\": [{ \"id\": \"Source 1\", \"componentType\": \"SOURCE\" }, { \"id\": \"Sink 1\", \"componentType\": \"SINK\" }], \"edges\": [{ \"source\": \"Source 1\", \"destination\": \"Unknown\", \"sourcePort\": \"OUTLET\" }], \"counts\": { \"nodes\": 2, \"edges\": 1, \"sources\": 1, \"sinks\": 1, \"flowlines\": 0 } }")]
    [InlineData("partial", "VALID_PARTIAL", "{ \"nodes\": [{ \"id\": \"Source 1\", \"componentType\": \"SOURCE\" }, { \"id\": \"Sink 1\", \"componentType\": \"SINK\" }], \"edges\": [{ \"source\": \"Source 1\", \"destination\": \"Sink 1\", \"sourcePort\": \"OUTLET\" }], \"counts\": { \"nodes\": 1, \"edges\": 0, \"sources\": 1, \"sinks\": 1, \"flowlines\": 0 } }")]
    public void RejectsInvalidNetworkTopology(string status, string contract, string topology)
    {
        using var document = JsonDocument.Parse($$"""
            {
              "status": "{{status}}",
              "result": {
                "schemaVersion": "pipesim-network-result/1",
                "model_kind": "network",
                "runTask": "network",
                "resultContract": "{{contract}}",
                "study": "Study 1",
                "simulationState": "Completed",
                "topology": {{topology}},
                "system": [], "node": [], "profiles": [{}], "summary": {}, "messages": [], "quality": []
              },
              "warnings": [{ "category": "PROTOCOL", "code": "NETWORK_RESULT_LIMITED", "message": "limited", "retryable": false }]
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(
            document.RootElement, "network", out _, out _, out _, out _);

        Assert.False(accepted);
    }

    [Theory]
    [InlineData("{ \"id\": \"Source 1\", \"componentType\": \"SOURCE\", \"controlId\": \"hidden\" }")]
    [InlineData("{ \"id\": \"Source\\n1\", \"componentType\": \"SOURCE\" }")]
    public void RejectsNetworkNodesWithExtraOrControlFields(string sourceNode)
    {
        using var document = JsonDocument.Parse($$"""
            { "status": "partial", "result": { "schemaVersion": "pipesim-network-result/1", "model_kind": "network", "runTask": "network", "resultContract": "VALID_PARTIAL", "study": "Study 1", "simulationState": "Completed", "topology": { "nodes": [{{sourceNode}}, { "id": "Sink 1", "componentType": "SINK" }], "edges": [{ "source": "Source 1", "destination": "Sink 1", "sourcePort": "" }], "counts": { "nodes": 2, "edges": 1, "sources": 1, "sinks": 1, "flowlines": 0 } } }, "warnings": [{ "category": "PROTOCOL", "code": "NETWORK_RESULT_LIMITED", "message": "limited", "retryable": false }] }
            """);

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Fact]
    public void AcceptsNetworkTopologyWithEmptySourcePort()
    {
        using var document = JsonDocument.Parse("""
            { "status": "partial", "result": { "schemaVersion": "pipesim-network-result/1", "model_kind": "network", "runTask": "network", "resultContract": "VALID_PARTIAL", "study": "Study 1", "simulationState": "Completed", "topology": { "nodes": [{ "id": "Source 1", "componentType": "SOURCE" }, { "id": "Sink 1", "componentType": "SINK" }], "edges": [{ "source": "Source 1", "destination": "Sink 1", "sourcePort": "" }], "counts": { "nodes": 2, "edges": 1, "sources": 1, "sinks": 1, "flowlines": 0 } } }, "warnings": [{ "category": "PROTOCOL", "code": "NETWORK_RESULT_LIMITED", "message": "limited", "retryable": false }] }
            """);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Fact]
    public void UsesNetworkSpecificPartialCompletionMessage()
    {
        Assert.Equal(
            "PIPESIM Network calculation completed with a limited display result.",
            PtkRunService.CompletionMessage("PARTIAL_SUCCEEDED", "network"));
    }

    [Fact]
    public void DropsNonNumericDisplayedLeafInPartialPayload()
    {
        using var document = NetworkEnvelope("partial", """
            "system": [{ "variable": "Pressure", "unit": "psi", "values": [{ "name": "Line 1", "value": { "nested": "invalid" } }] }]
            """);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out var result, out _, out _));
        Assert.Empty(result?.GetProperty("system").EnumerateArray() ?? []);
    }

    [Fact]
    public void RejectsNonNumericDisplayedLeafInFullPayload()
    {
        using var document = NetworkEnvelope("ok", FullPayload("""
            [{ "variable": "Pressure", "unit": "psi", "values": [{ "name": "Line 1", "value": { "nested": "invalid" } }] }]
            """));

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Theory]
    [InlineData("[]")]
    [InlineData("{}")]
    public void RejectsEmptyDisplayedNumericContainer(string value)
    {
        using var document = NetworkEnvelope("ok", FullPayload($$"""
            [{ "variable": "Pressure", "unit": "psi", "values": [{ "name": "Line 1", "value": {{value}} }] }]
            """));

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Theory]
    [InlineData("[]")]
    [InlineData("[{ \"variable\": \"TotalDistance\", \"unit\": \"ft\", \"values\": [] }, { \"variable\": \"Pressure\", \"unit\": \"psi\", \"values\": [1] }]")]
    [InlineData("[{ \"variable\": \"TotalDistance\", \"unit\": \"ft\", \"values\": [1] }, { \"variable\": \"Pressure\", \"unit\": \"psi\", \"values\": [1, 2] }]")]
    public void RejectsInvalidRetainedProfile(string profiles)
    {
        using var document = NetworkEnvelope("ok", FullPayload("[]", profiles));

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Theory]
    [InlineData("{ \"nodes\": 4, \"edges\": 2, \"sources\": 1, \"sinks\": 1, \"flowlines\": 1 }")]
    [InlineData("{ \"nodes\": 3, \"edges\": 1, \"sources\": 0, \"sinks\": 1, \"flowlines\": 1 }")]
    [InlineData("{ \"nodes\": 3, \"edges\": 1, \"sources\": 1, \"sinks\": 2, \"flowlines\": 1 }")]
    [InlineData("{ \"nodes\": 3, \"edges\": 1, \"sources\": 1, \"sinks\": 1, \"flowlines\": 0 }")]
    public void RejectsTopologyCountsThatDoNotMatchComponents(string counts)
    {
        var topology = $$"""
            { "nodes": [{ "id": "Source 1", "componentType": "source" }, { "id": "Sink 1", "componentType": "sInK" }, { "id": "Flowline 1", "componentType": "flowline" }], "edges": [{ "source": "Source 1", "destination": "Sink 1", "sourcePort": "" }], "counts": {{counts}} }
            """;
        using var document = NetworkEnvelope("partial", "\"system\": []", topology);

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Fact]
    public void AcceptsCaseInsensitiveDerivedTopologyCounts()
    {
        const string topology = "{ \"nodes\": [{ \"id\": \"Source 1\", \"componentType\": \"source\" }, { \"id\": \"Sink 1\", \"componentType\": \"sInK\" }, { \"id\": \"Flowline 1\", \"componentType\": \"flowline\" }], \"edges\": [{ \"source\": \"Source 1\", \"destination\": \"Sink 1\", \"sourcePort\": \"\" }], \"counts\": { \"nodes\": 3, \"edges\": 1, \"sources\": 1, \"sinks\": 1, \"flowlines\": 1 } }";
        using var document = NetworkEnvelope("partial", "\"system\": []", topology);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Fact]
    public void AcceptsSourceAndWellTopologyCountForFullResult()
    {
        const string topology = "{ \"nodes\": [{ \"id\": \"Platform\", \"componentType\": \"Source\" }, { \"id\": \"Prod Well\", \"componentType\": \"Well\" }, { \"id\": \"Oil\", \"componentType\": \"Sink\" }], \"edges\": [{ \"source\": \"Platform\", \"destination\": \"Prod Well\", \"sourcePort\": \"\" }], \"counts\": { \"nodes\": 3, \"edges\": 1, \"sources\": 2, \"sinks\": 1, \"flowlines\": 0 } }";
        using var document = NetworkEnvelope("ok", FullPayload("[]"), topology);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Fact]
    public void SanitizesRealisticPartialNetworkPayloadAndPreservesPartialCompletion()
    {
        using var document = JsonDocument.Parse("""
            {
              "status": "partial",
              "result": {
                "schemaVersion": "pipesim-network-result/1", "model_kind": "network", "runTask": "network",
                "resultContract": "VALID_PARTIAL", "study": "Study 1", "simulationState": "Completed",
                "topology": {
                  "nodes": [{ "id": "Platform", "componentType": "Source" }, { "id": "Prod Well", "componentType": "Well" }, { "id": "Oil", "componentType": "Sink" }],
                  "edges": [{ "source": "Platform", "destination": "Prod Well", "sourcePort": "" }],
                  "counts": { "nodes": 3, "edges": 1, "sources": 2, "sinks": 1, "flowlines": 0 }
                },
                "system": [
                  { "variable": "Pressure", "unit": "psi", "values": [{ "name": "Platform", "value": 100.0 }] },
                  { "variable": "Unsafe", "unit": "", "values": [{ "name": "Platform", "value": "not numeric" }] }
                ],
                "profiles": [
                  { "branch": "Safe", "pointCount": 1, "variables": [{ "variable": "TotalDistance", "unit": "ft", "values": [0] }, { "variable": "Pressure", "unit": "psi", "values": [100] }] },
                  { "branch": "Incomplete", "pointCount": 2, "variables": [{ "variable": "TotalDistance", "unit": "ft", "values": [0, 1] }, { "variable": "Pressure", "unit": "psi", "values": [100, 99] }, { "variable": "Temperature", "unit": "F", "values": [] }] }
                ]
              },
              "warnings": [{ "category": "PROTOCOL", "code": "NETWORK_RESULT_LIMITED", "message": "limited", "retryable": false }]
            }
            """);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "network", out var status, out var result, out _, out var warning));
        Assert.Equal("partial", status);
        Assert.Equal("NETWORK_RESULT_LIMITED", warning?.Code);
        Assert.Equal("VALID_PARTIAL", result?.GetProperty("resultContract").GetString());
        Assert.Single(result?.GetProperty("system").EnumerateArray() ?? []);
        Assert.Single(result?.GetProperty("profiles").EnumerateArray() ?? []);
    }

    [Fact]
    public void PartialNetworkPayloadWhitelistsSafeFieldsAndDropsRawDiagnostics()
    {
        using var document = JsonDocument.Parse("""
            {
              "status": "partial",
              "result": {
                "schemaVersion": "pipesim-network-result/1", "model_kind": "network", "runTask": "network",
                "resultContract": "VALID_PARTIAL", "study": "Study 1", "simulationState": "Completed",
                "topology": {
                  "nodes": [{ "id": "Source 1", "componentType": "SOURCE" }, { "id": "Sink 1", "componentType": "SINK" }],
                  "edges": [{ "source": "Source 1", "destination": "Sink 1", "sourcePort": "OUTLET" }],
                  "counts": { "nodes": 2, "edges": 1, "sources": 1, "sinks": 1, "flowlines": 0 }
                },
                "system": [{ "variable": "Pressure", "unit": "psi", "values": [{ "name": "Source 1", "value": 100 }] }],
                "summary": { "diagnostic": "\\\\server\\share\\result.out" },
                "messages": ["C:\\GRDP-Data\\jobs\\18\\output", "net.pipe://localhost/PIPESIM-private"],
                "quality": [{ "message": "\\\\server\\share\\quality.log" }],
                "adapterDiagnostic": "net.pipe://localhost/raw-adapter"
              },
              "warnings": [{ "category": "PROTOCOL", "code": "NETWORK_RESULT_LIMITED", "message": "limited", "retryable": false }]
            }
            """);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out var result, out _, out _));
        Assert.NotNull(result);
        Assert.Equal(8, result.Value.EnumerateObject().Count());
        Assert.True(result.Value.TryGetProperty("system", out _));
        Assert.False(result.Value.TryGetProperty("summary", out _));
        Assert.False(result.Value.TryGetProperty("messages", out _));
        Assert.False(result.Value.TryGetProperty("quality", out _));
        Assert.False(result.Value.TryGetProperty("adapterDiagnostic", out _));
        Assert.DoesNotContain("server\\share", result.Value.GetRawText(), StringComparison.Ordinal);
        Assert.DoesNotContain("C:\\GRDP-Data", result.Value.GetRawText(), StringComparison.Ordinal);
        Assert.DoesNotContain("net.pipe", result.Value.GetRawText(), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("C:\\\\GRDP-Data\\\\Study 20")]
    [InlineData("\\\\server\\\\share\\\\Study 20")]
    [InlineData("/var/lib/pipesim/study")]
    [InlineData("file://localhost/private/study")]
    [InlineData("net.pipe://localhost/PIPESIM-private")]
    [InlineData("Bearer private-token")]
    public void RejectsUnsafePartialNetworkStudyText(string study)
    {
        using var document = JsonDocument.Parse($$"""
            { "status": "partial", "result": { "schemaVersion": "pipesim-network-result/1", "model_kind": "network", "runTask": "network", "resultContract": "VALID_PARTIAL", "study": {{JsonSerializer.Serialize(study)}}, "simulationState": "Completed", "topology": {{DefaultTopology}} }, "warnings": [{ "category": "PROTOCOL", "code": "NETWORK_RESULT_LIMITED", "message": "limited", "retryable": false }] }
            """);

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Theory]
    [InlineData("net.pipe://localhost/private-source", "SOURCE")]
    [InlineData("Run20 Source", "license-server-private")]
    public void RejectsUnsafePartialNetworkTopologyText(string nodeId, string componentType)
    {
        var topology = JsonSerializer.Serialize(new
        {
            nodes = new[] { new { id = nodeId, componentType }, new { id = "Run20 Sink", componentType = "SINK" } },
            edges = new[] { new { source = nodeId, destination = "Run20 Sink", sourcePort = "OUTLET" } },
            counts = new { nodes = 2, edges = 1, sources = 1, sinks = 1, flowlines = 0 }
        });
        using var document = NetworkEnvelope("partial", "\"system\": []", topology);

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Fact]
    public void OmitsUnsafePartialNetworkDisplayTextAndPreservesRun20Labels()
    {
        using var document = NetworkEnvelope("partial", """
            "system": [
              { "variable": "Pressure", "unit": "psi", "values": [{ "name": "Run20 Source A", "value": 100 }] },
              { "variable": "C:\\private", "unit": "psi", "values": [{ "name": "Source", "value": 100 }] }
            ],
            "profiles": [
              { "branch": "Run20 Production Branch", "pointCount": 1, "variables": [{ "variable": "TotalDistance", "unit": "ft", "values": [0] }, { "variable": "Pressure", "unit": "psi", "values": [100] }, { "variable": "BranchEquipment", "unit": "", "values": ["FLOWLINE-20"] }] },
              { "branch": "net.pipe://localhost/private", "pointCount": 1, "variables": [{ "variable": "TotalDistance", "unit": "ft", "values": [0] }, { "variable": "Pressure", "unit": "psi", "values": [100] }] },
              { "branch": "Run20 Unsafe Equipment", "pointCount": 1, "variables": [{ "variable": "TotalDistance", "unit": "ft", "values": [0] }, { "variable": "Pressure", "unit": "psi", "values": [100] }, { "variable": "BranchEquipment", "unit": "", "values": ["license-key-private"] }] }
            ]
            """);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out var result, out _, out _));
        Assert.Single(result?.GetProperty("system").EnumerateArray() ?? []);
        Assert.Single(result?.GetProperty("profiles").EnumerateArray() ?? []);
        Assert.Contains("Run20 Source A", result?.GetRawText());
        Assert.Contains("Run20 Production Branch", result?.GetRawText());
        Assert.Contains("FLOWLINE-20", result?.GetRawText());
        Assert.DoesNotContain("private", result?.GetRawText());
    }

    private const string DefaultProfiles = "[{ \"branch\": \"Branch 1\", \"pointCount\": 1, \"variables\": [{ \"variable\": \"TotalDistance\", \"unit\": \"ft\", \"values\": [1] }, { \"variable\": \"Pressure\", \"unit\": \"psi\", \"values\": [2] }] }]";
    private const string DefaultTopology = "{ \"nodes\": [{ \"id\": \"Source 1\", \"componentType\": \"SOURCE\" }, { \"id\": \"Sink 1\", \"componentType\": \"SINK\" }], \"edges\": [{ \"source\": \"Source 1\", \"destination\": \"Sink 1\", \"sourcePort\": \"OUTLET\" }], \"counts\": { \"nodes\": 2, \"edges\": 1, \"sources\": 1, \"sinks\": 1, \"flowlines\": 0 } }";

    private static string FullPayload(string system, string? profiles = null) => $$"""
        "system": {{system}},
        "node": [],
        "profiles": {{profiles ?? DefaultProfiles}},
        "summary": {}, "messages": [], "quality": []
        """;

    private static JsonDocument NetworkEnvelope(string status, string payload, string? topology = null)
    {
        var contract = status == "ok" ? "VALID_FULL" : "VALID_PARTIAL";
        return JsonDocument.Parse($$"""
        {
          "status": "{{status}}",
          "result": {
            "schemaVersion": "pipesim-network-result/1", "model_kind": "network", "runTask": "network",
            "resultContract": "{{contract}}", "study": "Study 1", "simulationState": "Completed",
            "topology": {{topology ?? DefaultTopology}},
            {{payload}}
          },
          "warnings": [{ "category": "PROTOCOL", "code": "NETWORK_RESULT_LIMITED", "message": "limited", "retryable": false }]
        }
        """);
    }
}
