using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Xunit;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class GasLiftCapabilityTests
{
    private static readonly WellEspContextInspection[] Wells = [new("Well_1", [], [], [], [])];
    private static string Entry(string study = "Study 1", string state = "supported", string reason = "GAS_LIFT_VALVE_SUPPORTED",
        string valve = "true", string axis = "true") => $$"""
        {"well":"Well_1","study":"{{study}}","state":"{{state}}","reason":"{{reason}}",
        "useGasliftValve":{{valve}},"targetInjectionRateAvailable":{{axis}}}
        """;

    [Fact]
    public void PreservesSupportedUnsupportedAndUnknownWithoutGuessing()
    {
        foreach (var entry in new[] { Entry(), Entry(state: "unsupported", reason: "GAS_LIFT_VALVE_REQUIRED", valve: "false", axis: "false"),
            Entry(state: "unknown", reason: "GAS_LIFT_CAPABILITY_UNKNOWN", valve: "null", axis: "null") })
        {
            using var doc = JsonDocument.Parse("[" + entry + "]");
            Assert.Single(GasLiftCapabilityReader.Read(doc.RootElement, Wells, ["Study 1"])!);
        }
    }

    [Fact]
    public void RequiresCompleteDistinctWellStudyTuplesAndConsistentFlags()
    {
        foreach (var data in new[] { "[]", "[" + Entry(valve: "false") + "]", "[" + Entry(study: "Other") + "]",
                     "[" + Entry() + "," + Entry() + "]" })
        {
            using var doc = JsonDocument.Parse(data);
            Assert.Null(GasLiftCapabilityReader.Read(doc.RootElement, Wells, data.Contains(",{") ? ["Study 1", "Study 2"] : ["Study 1"]));
        }
    }

    [Fact]
    public void NewEnvelopeRetainsOldPressureTubingAndEspFields()
    {
        var old = new WellDataInspection("pipesim-well-inspection/8", new(5000, "psia"),
            [new("model.pips", 12, new string('a', 64))], "Well_1", [], Wells);
        var current = old with { SchemaVersion = "pipesim-well-inspection/9", GasLiftCapabilities = [new("Well_1", "Study 1", "supported", "GAS_LIFT_VALVE_SUPPORTED", true, true)] };
        var json = JsonSerializer.SerializeToElement(current, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        Assert.Equal(5000, json.GetProperty("reservoirPressure").GetProperty("value").GetDouble());
        Assert.True(json.GetProperty("wellContexts")[0].TryGetProperty("tubingComponents", out _));
        Assert.Single(current.GasLiftCapabilities!);
    }
}
