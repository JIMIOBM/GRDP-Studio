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
    [InlineData("{\"schemaVersion\":\"pipesim-well-profile-parameters/1\",\"outletPressurePsi\":100}", "profile", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-profile-parameters/1\",\"outletPressurePsi\":100}", "combined", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-profile-parameters/1\",\"outletPressurePsi\":0}", "profile", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Supply_1\",\"pressure\":1500,\"temperature\":130}]}", "network", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Supply_1\",\"flowRateType\":\"GasFlowRate\",\"gasFlowRate\":10}]}", "network", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Supply_1\",\"flowRateType\":\"GasFlowRate\",\"liquidFlowRate\":10}]}", "network", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Supply_1\",\"pressure\":0}]}", "network", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-choke-bean-size-parameters/1\",\"baselineRunId\":1001,\"choke\":\"Choke\",\"originalBeanSize\":2,\"targetBeanSize\":3}", "network", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-choke-bean-size-parameters/1\",\"baselineRunId\":1001,\"choke\":\"Choke\",\"originalBeanSize\":2,\"targetBeanSize\":2}", "network", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-optimizer-parameters/1\",\"applyResults\":false}", "network-optimizer", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-optimizer-parameters/2\",\"applyResults\":true}", "network-optimizer", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-optimizer-parameters/1\",\"applyResults\":true}", "network-optimizer", false)]
    [InlineData("null", "sensitivity", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-sensitivity-parameters/1\",\"targetVariable\":\"reservoirPressure\",\"values\":[3000,4000]}", "sensitivity", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-sensitivity-parameters/1\",\"targetVariable\":\"waterCut\",\"values\":[20,40]}", "sensitivity", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-sensitivity-parameters/1\",\"targetVariable\":\"reservoirPressure\",\"values\":[4000,3000]}", "sensitivity", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-sensitivity-parameters/1\",\"targetVariable\":\"reservoirPressure\",\"values\":[3000]}", "sensitivity", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-gas-lift-diagnostics-parameters/1\",\"producer\":\"Well_1\",\"outletPressurePsi\":151,\"surfaceInjectionTemperatureF\":110,\"targetInjectionRateMmscfd\":1.1,\"reservoirPressurePsi\":1700,\"gorScfPerStb\":400,\"waterCutPercent\":80}", "gas-lift-diagnostics", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-gas-lift-diagnostics-parameters/1\",\"producer\":\"Well_1\",\"outletPressurePsi\":151,\"surfaceInjectionTemperatureF\":110,\"targetInjectionRateMmscfd\":1.1,\"reservoirPressurePsi\":1700,\"gorScfPerStb\":400,\"waterCutPercent\":101}", "gas-lift-diagnostics", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-vfp-tables-parameters/1\",\"producer\":\"Well\",\"reservoirSimulator\":\"ECLIPSE\",\"tableNumber\":2,\"includeTemperature\":true,\"bottomHoleDatumDepth\":30,\"liquidRatesStbPerDay\":[200,300],\"outletPressuresPsi\":[250,350],\"waterCutFraction\":[0.4],\"gorMscfPerStb\":[0.265],\"artificialLiftInjectionDpPsi\":[40,50]}", "vfp-tables", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-vfp-tables-parameters/1\",\"producer\":\"Well\",\"reservoirSimulator\":\"ECLIPSE\",\"tableNumber\":2,\"includeTemperature\":true,\"bottomHoleDatumDepth\":30,\"liquidRatesStbPerDay\":[300,200],\"outletPressuresPsi\":[250,350],\"waterCutFraction\":[0.4],\"gorMscfPerStb\":[0.265],\"artificialLiftInjectionDpPsi\":[40,50]}", "vfp-tables", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/1\"}", "trajectory", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-curves-parameters/1\"}", "esp-curves", true)]
    [InlineData("null", "trajectory", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/1\",\"writeBack\":false}", "trajectory", false)]
    public void Validates(string json, string task, bool expected)
    {
        using var document = JsonDocument.Parse(json);
        Assert.Equal(expected, WellScenarioParameters.Valid(document.RootElement, task));
    }
}
