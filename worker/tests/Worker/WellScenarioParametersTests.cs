using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public class WellScenarioParametersTests
{
    [Theory]
    [InlineData("null", "combined", true)]
    [InlineData("{}", "nodal", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-parameters/1\",\"reservoirPressurePsi\":4000}", "nodal", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-parameters/1\",\"reservoirPressurePsi\":4000}", "profile", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-parameters/1\",\"reservoirPressurePsi\":4000}", "combined", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-parameters/1\",\"reservoirPressurePsi\":4000}", "network", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-parameters/1\",\"reservoirPressurePsi\":true}", "nodal", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-parameters/1\",\"reservoirPressurePsi\":0}", "nodal", false)]
    public void Validates(string json, string task, bool expected)
    {
        using var document = JsonDocument.Parse(json);
        Assert.Equal(expected, WellScenarioParameters.Valid(document.RootElement, task));
    }
}
