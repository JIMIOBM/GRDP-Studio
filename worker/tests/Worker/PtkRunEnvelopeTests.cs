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
    public void RejectsNonNumericDisplayedLeafInPartialPayload()
    {
        using var document = NetworkEnvelope("partial", """
            "system": [{ "variable": "Pressure", "unit": "psi", "values": [{ "name": "Line 1", "value": { "nested": "invalid" } }] }]
            """);

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
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
        using var document = NetworkEnvelope("partial", $$"""
            "system": [{ "variable": "Pressure", "unit": "psi", "values": [{ "name": "Line 1", "value": {{value}} }] }]
            """);

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
