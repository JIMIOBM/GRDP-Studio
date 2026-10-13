using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public class SystemAnalysisScenarioTests
{
    private static JsonElement Parameters(int version = 2, string producer = "Well_1") => JsonSerializer.SerializeToElement(new
    {
        schemaVersion = $"pipesim-system-analysis-parameters/{version}", producer,
        branchTerminator = "Flowline_1", outletPressurePsi = 600, scanVariable = "liquidFlowRate", values = new[] { 2400, 3000 }
    });

    private static JsonElement Envelope(int version = 2, string producer = "Well_1", int[]? scan = null, string? pressureUnit = "psia") =>
        JsonSerializer.SerializeToElement(new { status = "ok", result = new
        {
            schemaVersion = $"pipesim-system-analysis-result/{version}", model_kind = "network", runTask = "system-analysis",
            resultContract = "VALID_FULL", study = "Study 1", producer, branchTerminator = "Flowline_1",
            outletPressurePsi = 600, scanVariable = "liquidFlowRate",
            cases = (scan ?? [2400, 3000]).Select(flow => new
            {
                caseName = "Base Case", scanValue = flow,
                system = new[] { new { variable = "Pressure", unit = "psia", value = 1200 } },
                node = new[] { new { node = producer, variables = new[] { new { variable = "Pressure", unit = "psia", value = 1200 } } } },
                profile = new { pointCount = 2, variables = new[]
                {
                    new { variable = "TotalDistance", unit = "ft", values = new[] { 0, 100 } },
                    new { variable = "Pressure", unit = pressureUnit, values = new[] { 1200, 600 } },
                    new { variable = "Temperature", unit = "degF", values = new[] { 150, 120 } }
                } }
            }).ToArray()
        } });

    private static bool Read(JsonElement envelope, JsonElement? parameters = null) =>
        PtkRunService.TryReadEnvelope(envelope, "system-analysis", out _, out _, out _, out _, parameters, "Study 1");

    [Fact] public void DynamicRequestKeepsHistoricalWellRestriction()
    {
        Assert.True(SystemAnalysisParameters.Valid(Parameters()));
        Assert.False(SystemAnalysisParameters.Valid(Parameters(1)));
        Assert.True(SystemAnalysisParameters.Valid(Parameters(1, "Well")));
        Assert.False(SystemAnalysisParameters.Valid(Parameters(3)));
    }

    [Fact] public void MatchingDynamicResultAndLegacyHistoryAreAccepted()
    {
        Assert.True(Read(Envelope(), Parameters()));
        Assert.True(Read(Envelope(1, "Well")));
    }

    [Fact] public void DynamicResultWithoutRequestOrWrongProducerIsRejected()
    {
        Assert.False(Read(Envelope()));
        Assert.False(Read(Envelope(producer: "Well_2"), Parameters()));
        Assert.False(Read(Envelope(1, "Well"), Parameters(2, "Well")));
        Assert.False(Read(Envelope(2, "Well"), Parameters(1, "Well")));
    }

    [Fact] public void MissingCaseAlteredScanAndMissingUnitAreRejected()
    {
        Assert.False(Read(Envelope(scan: [2400]), Parameters()));
        Assert.False(Read(Envelope(scan: [2500, 3000]), Parameters()));
        Assert.False(Read(Envelope(pressureUnit: null), Parameters()));
    }

    [Theory]
    [InlineData(" Well_1")]
    [InlineData("Well_1\n")]
    [InlineData("../Well_1")]
    public void UnsafeDynamicNamesAreRejected(string name) => Assert.False(SystemAnalysisParameters.Valid(Parameters(2, name)));

    [Fact] public void WrongDimensionAndUnknownPressureUnitsAreRejected()
    {
        foreach (var unit in new[] { "STB/d", "banana", "psig", "bar" })
            Assert.False(Read(Envelope(pressureUnit: unit), Parameters()));
    }

    [Fact] public void WrongStudyAndMalformedCaseOrScalarAreRejectedWithoutThrowing()
    {
        var wrongStudy = JsonNode.Parse(Envelope().GetRawText())!;
        wrongStudy["result"]!["study"] = "Other Study";
        Assert.False(Read(JsonSerializer.SerializeToElement(wrongStudy), Parameters()));
        var scalar = JsonNode.Parse(Envelope().GetRawText())!;
        scalar["result"]!["model_kind"] = 42;
        Assert.False(Read(JsonSerializer.SerializeToElement(scalar), Parameters()));
        var badCase = JsonNode.Parse(Envelope().GetRawText())!;
        badCase["result"]!["cases"]![0] = 42;
        Assert.False(Read(JsonSerializer.SerializeToElement(badCase), Parameters()));
    }

    [Fact] public void NativeUnavailableSentinelsInEveryNumericSectionAreRejected()
    {
        foreach (var missing in new[] { -1e31, 1.2345e25 })
        {
            var system = JsonNode.Parse(Envelope().GetRawText())!;
            system["result"]!["cases"]![0]!["system"]![0]!["value"] = missing;
            Assert.False(Read(JsonSerializer.SerializeToElement(system), Parameters()));
            var node = JsonNode.Parse(Envelope().GetRawText())!;
            node["result"]!["cases"]![0]!["node"]![0]!["variables"]![0]!["value"] = missing;
            Assert.False(Read(JsonSerializer.SerializeToElement(node), Parameters()));
            var profile = JsonNode.Parse(Envelope().GetRawText())!;
            profile["result"]!["cases"]![0]!["profile"]!["variables"]![1]!["values"]![0] = missing;
            Assert.False(Read(JsonSerializer.SerializeToElement(profile), Parameters()));
        }
    }
}
