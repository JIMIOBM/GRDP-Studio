using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public class WellScenarioParametersTests
{
    [Theory]
    [InlineData("gas-lift-performance", "{\"schemaVersion\":42,\"producer\":\"Well_1\",\"outletPressurePsi\":151,\"surfaceInjectionTemperatureF\":110,\"targetInjectionRateMmscfd\":1.1,\"reservoirPressurePsi\":1700,\"gorScfPerStb\":400,\"waterCutPercent\":80,\"valuesMmscfd\":[1,2]}")]
    [InlineData("gas-lift-diagnostics", "{\"schemaVersion\":42,\"producer\":\"Well_1\",\"outletPressurePsi\":151,\"surfaceInjectionTemperatureF\":110,\"targetInjectionRateMmscfd\":1.1,\"reservoirPressurePsi\":1700,\"gorScfPerStb\":400,\"waterCutPercent\":80}")]
    [InlineData("vfp-tables", "{\"schemaVersion\":42,\"producer\":\"Well\",\"reservoirSimulator\":\"ECLIPSE\",\"tableNumber\":2,\"includeTemperature\":true,\"bottomHoleDatumDepth\":30,\"liquidRatesStbPerDay\":[200,300],\"outletPressuresPsi\":[250,350],\"waterCutFraction\":[0.4],\"gorMscfPerStb\":[0.265],\"artificialLiftInjectionDpPsi\":[40,50]}")]
    [InlineData("vfp-tables", "{\"schemaVersion\":\"pipesim-vfp-tables-parameters/1\",\"producer\":\"Well\",\"reservoirSimulator\":{},\"tableNumber\":2,\"includeTemperature\":true,\"bottomHoleDatumDepth\":30,\"liquidRatesStbPerDay\":[200,300],\"outletPressuresPsi\":[250,350],\"waterCutFraction\":[0.4],\"gorMscfPerStb\":[0.265],\"artificialLiftInjectionDpPsi\":[40,50]}")]
    public void WrongJsonTypesAreRejectedWithoutThrowing(string task, string json)
    {
        using var document = JsonDocument.Parse(json);
        Assert.False(WellScenarioParameters.Valid(document.RootElement, task));
    }

    [Fact]
    public void NullParametersDoNotMakeAnUnknownTaskValid()
    {
        using var document = JsonDocument.Parse("null");
        Assert.False(WellScenarioParameters.Valid(document.RootElement, "unknown-task"));
    }

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
    [InlineData("{\"schemaVersion\":\"pipesim-well-tubing-diameter-parameters/1\",\"baselineRunId\":74,\"wellContext\":\"Well_1\",\"tubingContext\":\"Well_1:Tubing\",\"originalInnerDiameter\":2.441,\"unit\":\"in\",\"targetInnerDiameter\":2.3}", "profile", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-tubing-diameter-parameters/1\",\"baselineRunId\":74,\"wellContext\":\"Well_1\",\"tubingContext\":\"Well_1:Tubing\",\"originalInnerDiameter\":2.441,\"unit\":\"in\",\"targetInnerDiameter\":2.441}", "profile", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-tubing-diameter-parameters/1\",\"baselineRunId\":0,\"wellContext\":\"Well_1\",\"tubingContext\":\"Well_1:Tubing\",\"originalInnerDiameter\":2.441,\"unit\":\"in\",\"targetInnerDiameter\":2.3}", "profile", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-tubing-diameter-parameters/1\",\"baselineRunId\":74,\"wellContext\":\"../Well\",\"tubingContext\":\"Well_1:Tubing\",\"originalInnerDiameter\":2.441,\"unit\":\"in\",\"targetInnerDiameter\":2.3}", "profile", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-tubing-diameter-parameters/1\",\"baselineRunId\":74,\"wellContext\":\"Well_1\",\"tubingContext\":\"Well_1:Tubing\",\"originalInnerDiameter\":2.441,\"unit\":\"in\",\"targetInnerDiameter\":2.3}", "nodal", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Supply_1\",\"pressure\":1500,\"temperature\":130}]}", "network", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Supply_1\",\"flowRateType\":\"GasFlowRate\",\"gasFlowRate\":10}]}", "network", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Supply_1\",\"flowRateType\":\"GasFlowRate\",\"liquidFlowRate\":10}]}", "network", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-parameters/1\",\"boundaries\":[{\"node\":\"Supply_1\",\"pressure\":0}]}", "network", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-choke-bean-size-parameters/1\",\"baselineRunId\":1001,\"choke\":\"Choke\",\"originalBeanSize\":2,\"targetBeanSize\":3}", "network", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-choke-bean-size-parameters/1\",\"baselineRunId\":1001,\"choke\":\"Choke\",\"originalBeanSize\":2,\"targetBeanSize\":2}", "network", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-connection-parameters/1\",\"baselineRunId\":1001,\"source\":\"Flowline-1\",\"sourcePort\":\"\",\"originalDestination\":\"Junction-1\",\"newDestination\":\"Junction-2\"}", "network", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-connection-parameters/1\",\"baselineRunId\":1001,\"source\":\"Flowline-1\",\"sourcePort\":\"\",\"originalDestination\":\"Junction-1\",\"newDestination\":\"Flowline-1\"}", "network", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-connection-parameters/1\",\"baselineRunId\":1001,\"source\":\"Separator-1\",\"sourcePort\":\"Side Port\",\"originalDestination\":\"Flowline-1\",\"newDestination\":\"Sink-1\"}", "network", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-optimizer-parameters/1\",\"applyResults\":false}", "network-optimizer", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-optimizer-parameters/2\",\"applyResults\":true}", "network-optimizer", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-optimizer-parameters/1\",\"applyResults\":true}", "network-optimizer", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-well-performance-parameters/1\",\"producerContext\":\"Well\",\"branchTerminator\":\"FL-2\",\"sensitivityUnit\":\"in\",\"sensitivityValues\":[4,5,6]}", "network-well-performance-curves", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-well-performance-parameters/1\",\"producerContext\":\"Well\",\"branchTerminator\":\"FL-2\",\"sensitivityUnit\":\"mm\",\"sensitivityValues\":[4,5,6]}", "network-well-performance-curves", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-network-well-performance-parameters/1\",\"producerContext\":\"Well\",\"branchTerminator\":\"FL-2\",\"sensitivityUnit\":\"in\",\"sensitivityValues\":[4,4,6]}", "network-well-performance-curves", false)]
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
    [InlineData("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/2\",\"wellContext\":\"Well_2\"}", "trajectory", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/3\",\"wellContext\":\"Well_2\",\"stationIndex\":2,\"targetInclinationDeg\":26.5}", "trajectory", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/3\",\"wellContext\":\"Well_2\",\"stationIndex\":0,\"targetInclinationDeg\":26.5}", "trajectory", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/3\",\"wellContext\":\"Well_2\",\"stationIndex\":2,\"targetInclinationDeg\":90.1}", "trajectory", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-curves-parameters/1\"}", "esp-curves", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-curves-parameters/2\",\"wellContext\":\"Well_2\",\"espContext\":\"Well_2:ESP\"}", "esp-curves", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/1\",\"espContext\":\"AUTO\",\"parameter\":\"operatingFrequency\",\"targetValueHz\":70}", "esp-parameter", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/1\",\"espContext\":\"Well_1:ESP\",\"parameter\":\"operatingFrequency\",\"targetValueHz\":70}", "esp-parameter", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/1\",\"espContext\":\"../ESP\",\"parameter\":\"operatingFrequency\",\"targetValueHz\":70}", "esp-parameter", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/1\",\"espContext\":\"AUTO\",\"parameter\":\"operatingFrequency\",\"targetValueHz\":0}", "esp-parameter", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/1\",\"espContext\":\"AUTO\",\"parameter\":\"operatingFrequency\",\"targetValueHz\":70,\"extra\":true}", "esp-parameter", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/2\",\"espContext\":\"AUTO\",\"parameter\":\"headFactor\",\"targetValue\":0.9}", "esp-parameter", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/3\",\"wellContext\":\"Well_2\",\"espContext\":\"Well_2:ESP\",\"parameter\":\"operatingFrequency\",\"targetValue\":70}", "esp-parameter", true)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/2\",\"espContext\":\"AUTO\",\"parameter\":\"headFactor\",\"targetValue\":2.1}", "esp-parameter", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-esp-parameter-parameters/2\",\"espContext\":\"AUTO\",\"parameter\":\"operatingFrequency\",\"targetValue\":0.9}", "esp-parameter", false)]
    [InlineData("null", "trajectory", false)]
    [InlineData("{\"schemaVersion\":\"pipesim-well-trajectory-parameters/1\",\"writeBack\":false}", "trajectory", false)]
    public void Validates(string json, string task, bool expected)
    {
        using var document = JsonDocument.Parse(json);
        Assert.Equal(expected, WellScenarioParameters.Valid(document.RootElement, task));
    }
}
