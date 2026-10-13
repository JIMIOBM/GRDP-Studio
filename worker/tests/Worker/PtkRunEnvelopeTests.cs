using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class PtkRunEnvelopeTests
{
    [Fact]
    public void PreservesBoundedOfficialValidationDiagnosticsOnTaskFailure()
    {
        using var document = JsonDocument.Parse("""
            {
              "status":"error",
              "error":{
                "category":"VALIDATION",
                "code":"PIPESIM_TASK_VALIDATION_FAILED",
                "message":"PIPESIM task preflight found 1 issue(s).",
                "retryable":false,
                "diagnostics":[{"path":"Well_1","property":"OutletPressure","message":"Pressure is outside the task limits."}]
              }
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(
            document.RootElement, "nodal", out var status, out var result, out var error, out _);

        Assert.True(accepted);
        Assert.Equal("error", status);
        Assert.Null(result);
        Assert.Equal("PIPESIM_TASK_VALIDATION_FAILED", error?.Code);
        Assert.Equal("OutletPressure", Assert.Single(error!.Diagnostics!).Property);
    }

    [Fact]
    public void AcceptsOfficialGasLiftPerformanceResult()
    {
        using var document = JsonDocument.Parse("""
            {
              "status": "ok",
              "result": {
                "schemaVersion": "pipesim-gas-lift-performance-result/1",
                "model_kind": "black_oil_liquid",
                "runTask": "gas-lift-performance",
                "resultContract": "VALID_FULL",
                "producer": "Well_1",
                "outletPressurePsi": 151,
                "surfaceInjectionTemperatureF": 110,
                "targetInjectionRateMmscfd": 1.1,
                "reservoirPressurePsi": 1700,
                "gorScfPerStb": 400,
                "waterCutPercent": 80,
                "scanVariable": "gasLiftInjectionRate",
                "scanUnit": "mmscf/d",
                "productionUnit": "STB/d",
                "cases": [
                  { "caseName": "INJGASRATE=0.55 mmscfd Flowrate=987.4514 sbbl/day", "injectionRateMmscfd": 0.55, "liquidRateStbPerDay": 987.4513984865446 },
                  { "caseName": "INJGASRATE=0.65 mmscfd Flowrate=1330.814 sbbl/day", "injectionRateMmscfd": 0.65, "liquidRateStbPerDay": 1330.8137384764686 }
                ]
              },
              "warnings": []
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(
            document.RootElement, "gas-lift-performance", out var status, out var result, out var error, out _);

        Assert.True(accepted);
        Assert.Equal("ok", status);
        Assert.Equal("VALID_FULL", result?.GetProperty("resultContract").GetString());
        Assert.Null(error);
    }

    [Fact]
    public void AcceptsOfficialGasLiftDiagnosticsResult()
    {
        using var document = JsonDocument.Parse("""
            {
              "status": "ok",
              "result": {
                "schemaVersion":"pipesim-gas-lift-diagnostics-result/1",
                "model_kind":"black_oil_liquid",
                "runTask":"gas-lift-diagnostics",
                "resultContract":"VALID_FULL",
                "producer":"Well_1",
                "outletPressurePsi":151,
                "surfaceInjectionTemperatureF":110,
                "targetInjectionRateMmscfd":1.1,
                "reservoirPressurePsi":1700,
                "gorScfPerStb":400,
                "waterCutPercent":80,
                "diagnosticType":"FIXEDINJECTION",
                "throttling":"ON",
                "usePhaseRatio":true,
                "injectionUnit":"mmscf/d",
                "liquidRateUnit":"STB/d",
                "cases":[{
                  "caseName":"INJGASRATE=0.5 mmscfd Flowrate=986.524 sbbl/day",
                  "injectionRateMmscfd":0.5,
                  "liquidRateStbPerDay":986.5239826290447,
                  "valves":[{
                    "valveName":"GLI 1","positionStatus":"Open","status":"Throttling",
                    "gasRateNoThrottlingMmscfd":0.3395,"portDiameterIn":0.1875,"domeTemperatureF":82.97,
                    "closingPressurePsi":310.0,"openingPressurePsi":281.6,"ptroPsi":342.0,
                    "dischargeCoefficient":0.65,"portToBellowArea":0.093548,"operationMode":"PPO","portType":"SPRING"
                  }]
                }]
              },
              "warnings": []
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(
            document.RootElement, "gas-lift-diagnostics", out var status, out var result, out var error, out _);

        Assert.True(accepted);
        Assert.Equal("ok", status);
        Assert.Equal("VALID_FULL", result?.GetProperty("resultContract").GetString());
        Assert.Null(error);
    }

    [Fact]
    public void AcceptsOfficialVfpTablesResultWithNonSquareAxes()
    {
        using var document = JsonDocument.Parse(VfpTablesEnvelope());

        var accepted = PtkRunService.TryReadEnvelope(document.RootElement, "vfp-tables", out var status, out var result, out var error, out _);

        Assert.True(accepted);
        Assert.Equal("ok", status);
        Assert.Equal("VALID_FULL", result?.GetProperty("resultContract").GetString());
        Assert.Null(error);
    }

    [Fact]
    public void RejectsVfpRowsThatDoNotCoverThpWfrGfrAlqGrid()
    {
        var malformed = VfpTablesEnvelope().Replace(
            "{\"thpIndex\":2,\"waterCutIndex\":1,\"gorIndex\":1,\"artificialLiftIndex\":2,\"values\":[1000,1100,1200]}",
            "{\"thpIndex\":1,\"waterCutIndex\":1,\"gorIndex\":1,\"artificialLiftIndex\":2,\"values\":[1000,1100,1200]}",
            StringComparison.Ordinal);
        using var document = JsonDocument.Parse(malformed);

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "vfp-tables", out _, out _, out _, out _));
    }

    private static string VfpTablesEnvelope() => """
            {
              "status":"ok", "result":{
                "schemaVersion":"pipesim-vfp-tables-result/2", "model_kind":"black_oil_liquid", "runTask":"vfp-tables", "resultContract":"VALID_FULL", "producer":"Well", "reservoirSimulator":"ECLIPSE", "tableNumber":2, "includeTemperature":true, "bottomHoleDatumDepth":30,
                "axes":{"liquidRatesStbPerDay":[100,200,300],"outletPressuresPsi":[250,350],"waterCutFraction":[0.4],"gorMscfPerStb":[0.265],"artificialLiftInjectionDpPsi":[40,50]},
                "table":{"valueName":"BHP","unit":"psia","rows":[
                  {"thpIndex":1,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":1,"values":[100,200,300]},
                  {"thpIndex":1,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":2,"values":[400,500,600]},
                  {"thpIndex":2,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":1,"values":[700,800,900]},
                  {"thpIndex":2,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":2,"values":[1000,1100,1200]}]},
                "temperatureTable":{"valueName":"TEMP","unit":"F","rows":[
                  {"thpIndex":1,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":1,"values":[50,51,52]},
                  {"thpIndex":1,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":2,"values":[53,54,55]},
                  {"thpIndex":2,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":1,"values":[56,57,58]},
                  {"thpIndex":2,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":2,"values":[59,60,61]}]},
                "vfpTableContent":"VFPPROD 2 30.0", "vfpTableWithTemperatureContent":"VFPPROD 2 30.0"
              }, "warnings":[]
            }
            """;

    [Fact]
    public void AcceptsOfficialWellTrajectoryResult()
    {
        using var document = JsonDocument.Parse("""
            {
              "status":"ok", "result":{
                "schemaVersion":"pipesim-well-trajectory-result/1", "model_kind":"black_oil_liquid", "runTask":"trajectory", "resultContract":"VALID_FULL", "producer":"Well_1",
                "units":{"measuredDepth":"ft","trueVerticalDepth":"ft","inclination":"deg","azimuth":"deg","maxDogLegSeverity":"deg/100ft"},
                "points":[
                  {"measuredDepth":0,"trueVerticalDepth":0,"inclination":0,"azimuth":null,"maxDogLegSeverity":null},
                  {"measuredDepth":1000,"trueVerticalDepth":980,"inclination":25,"azimuth":90,"maxDogLegSeverity":1.2}
                ]
              }, "warnings":[]
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(document.RootElement, "trajectory", out var status, out var result, out var error, out _);

        Assert.True(accepted);
        Assert.Equal("ok", status);
        Assert.Equal("Well_1", result?.GetProperty("producer").GetString());
        Assert.Equal(2, result?.GetProperty("points").GetArrayLength());
        Assert.Null(error);
    }

    [Fact]
    public void AcceptsVerifiedEditedTrajectoryResultWithExactDepthProfileComparison()
    {
        using var document = JsonDocument.Parse("""
            {
              "status":"ok", "result":{
                "schemaVersion":"pipesim-well-trajectory-result/2", "model_kind":"black_oil_liquid", "runTask":"trajectory", "resultContract":"VALID_FULL", "producer":"Well_1",
                "units":{"measuredDepth":"ft","trueVerticalDepth":"ft","inclination":"deg","azimuth":"deg","maxDogLegSeverity":"deg/100ft"},
                "points":[
                  {"measuredDepth":0,"trueVerticalDepth":0,"inclination":0,"azimuth":null,"maxDogLegSeverity":null},
                  {"measuredDepth":500,"trueVerticalDepth":490,"inclination":26,"azimuth":null,"maxDogLegSeverity":null},
                  {"measuredDepth":1000,"trueVerticalDepth":980,"inclination":30,"azimuth":null,"maxDogLegSeverity":null}
                ],
                "application":{"applied":true,"isolatedCopy":true,"sourceModelUnchanged":true,"artifactName":"pipesim-trajectory-applied.pips","stationIndex":1,"originalInclinationDeg":25,"targetInclinationDeg":26,"maxTvdBeforeFt":980,"maxTvdAfterFt":980},
                "profileComparison":{
                  "method":"exact-depth-match-no-interpolation","baselinePointCount":4,"targetPointCount":3,"matchedPointCount":2,
                  "baseline":[{"depth":0,"pressure":100,"temperature":60},{"depth":1,"pressure":90,"temperature":61},{"depth":1,"pressure":91,"temperature":61.5},{"depth":2,"pressure":80,"temperature":62}],
                  "target":[{"depth":0,"pressure":101,"temperature":60.5},{"depth":1,"pressure":88,"temperature":62},{"depth":2,"pressure":78,"temperature":63}],
                  "matchedPoints":[
                    {"depth":0,"baselinePressure":100,"targetPressure":101,"pressureDelta":1,"baselineTemperature":60,"targetTemperature":60.5,"temperatureDelta":0.5},
                    {"depth":2,"baselinePressure":80,"targetPressure":78,"pressureDelta":-2,"baselineTemperature":62,"targetTemperature":63,"temperatureDelta":1}
                  ],
                  "maxAbsPressureDelta":2,"maxAbsTemperatureDelta":1
                }
              }, "warnings":[]
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(document.RootElement, "trajectory", out var status, out var result, out var error, out _);

        Assert.True(accepted);
        Assert.Equal("ok", status);
        Assert.Equal("pipesim-well-trajectory-result/2", result?.GetProperty("schemaVersion").GetString());
        Assert.Null(error);
    }

    [Fact]
    public void AcceptsOfficialEspCurvesResultForBasicGasModel()
    {
        using var document = JsonDocument.Parse("""
            {
              "status":"ok", "result":{
                "schemaVersion":"pipesim-esp-curves-result/1", "model_kind":"basic_gas", "runTask":"esp-curves", "resultContract":"VALID_FULL", "producer":"Well_1",
                "pump":{
                  "pumpName":"B-ESP",
                  "inputs":{"frequency":60,"frequencyUnit":"Hz","manufacturer":"REDA","model":"J7000N","minFlowRate":100,"maxFlowRate":1000,"stages":120},
                  "frequencies":[{"frequencyHz":60,"frequencyLabel":"60 Hz","flowRate":[100,500],"flowRateUnit":"bbl/d","head":[1000,800],"headUnit":"ft"}],
                  "operatingEnvelope":{
                    "qMin":{"flowRate":[100],"flowRateUnit":"bbl/d","head":[1000],"headUnit":"ft"},
                    "bep":{"flowRate":[500],"flowRateUnit":"bbl/d","head":[800],"headUnit":"ft"},
                    "qMax":{"flowRate":[1000],"flowRateUnit":"bbl/d","head":[600],"headUnit":"ft"}
                  }
                },
                "nodalPump":{
                  "pumpName":"B-ESP",
                  "inputs":{"frequency":60,"frequencyUnit":"Hz","manufacturer":"REDA","model":"J7000N","minFlowRate":100,"maxFlowRate":1000,"stages":120},
                  "frequencies":[{"frequencyHz":60,"frequencyLabel":"60 Hz","flowRate":[100,500],"flowRateUnit":"bbl/d","head":[1000,800],"headUnit":"ft"}],
                  "operatingEnvelope":{
                    "qMin":{"flowRate":[100],"flowRateUnit":"bbl/d","head":[1000],"headUnit":"ft"},
                    "bep":{"flowRate":[500],"flowRateUnit":"bbl/d","head":[800],"headUnit":"ft"},
                    "qMax":{"flowRate":[1000],"flowRateUnit":"bbl/d","head":[600],"headUnit":"ft"}
                  }
                }
              }, "warnings":[]
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(
            document.RootElement, "esp-curves", out var status, out var result, out var error, out _);

        Assert.True(accepted);
        Assert.Equal("ok", status);
        Assert.Equal("basic_gas", result?.GetProperty("model_kind").GetString());
        Assert.Null(error);
    }

    [Fact]
    public void AcceptsContextBoundEspCurvesResultForLegacyWellAndNamedPump()
    {
        using var document = JsonDocument.Parse("""
            {"status":"ok","result":{"schemaVersion":"pipesim-esp-curves-result/2","model_kind":"legacy_well","runTask":"esp-curves","resultContract":"VALID_FULL","producer":"Well_2","espContext":"Well_2:ESP",
             "pump":{"pumpName":"ESP-2","inputs":{"frequency":60,"frequencyUnit":"Hz","manufacturer":"REDA","model":"J7000N","minFlowRate":100,"maxFlowRate":1000,"stages":120},
             "frequencies":[{"frequencyHz":60,"frequencyLabel":"60 Hz","flowRate":[100,500],"flowRateUnit":"bbl/d","head":[1000,800],"headUnit":"ft"}],
             "operatingEnvelope":{"qMin":{"flowRate":[100],"flowRateUnit":"bbl/d","head":[1000],"headUnit":"ft"},"bep":{"flowRate":[500],"flowRateUnit":"bbl/d","head":[800],"headUnit":"ft"},"qMax":{"flowRate":[1000],"flowRateUnit":"bbl/d","head":[600],"headUnit":"ft"}}},
             "nodalPump":{"pumpName":"ESP-2","inputs":{"frequency":60,"frequencyUnit":"Hz","manufacturer":"REDA","model":"J7000N","minFlowRate":100,"maxFlowRate":1000,"stages":120},
             "frequencies":[{"frequencyHz":60,"frequencyLabel":"60 Hz","flowRate":[100,500],"flowRateUnit":"bbl/d","head":[1000,800],"headUnit":"ft"}],
             "operatingEnvelope":{"qMin":{"flowRate":[100],"flowRateUnit":"bbl/d","head":[1000],"headUnit":"ft"},"bep":{"flowRate":[500],"flowRateUnit":"bbl/d","head":[800],"headUnit":"ft"},"qMax":{"flowRate":[1000],"flowRateUnit":"bbl/d","head":[600],"headUnit":"ft"}}}},"warnings":[]}
            """);

        var accepted = PtkRunService.TryReadEnvelope(
            document.RootElement, "esp-curves", out var status, out var result, out var error, out _);

        Assert.True(accepted);
        Assert.Equal("legacy_well", result?.GetProperty("model_kind").GetString());
        Assert.Equal("Well_2:ESP", result?.GetProperty("espContext").GetString());
        Assert.Null(error);
    }

    [Fact]
    public void AcceptsOfficialEspParameterResultWithBaselineAndTargetCases()
    {
        using var document = JsonDocument.Parse("""
            {
              "status":"ok", "result":{
                "schemaVersion":"pipesim-esp-parameter-result/1", "model_kind":"black_oil_liquid", "runTask":"esp-parameter", "resultContract":"VALID_FULL", "producer":"Well_1", "espContext":"Well_1:ESP",
                "parameter":"operatingFrequency", "unit":"Hz", "originalValue":60, "targetValue":70, "appliedValue":70, "sourceModelUnchanged":true, "isolatedCopy":true,
                "baseline":{"frequencyHz":60,"ipr":[{"flow":100,"pressure":1200}],"vlp":[{"flow":100,"pressure":1300}],"profile":[{"depth":0,"pressure":1500,"temperature":80}]},
                "target":{"frequencyHz":70,"ipr":[{"flow":100,"pressure":1250}],"vlp":[{"flow":100,"pressure":1350}],"profile":[{"depth":0,"pressure":1550,"temperature":82}]},
                "comparison":{"maxIprPressureDelta":null,"maxVlpPressureDelta":50,"maxProfilePressureDelta":50,"maxProfileTemperatureDelta":2}
              }, "warnings":[]
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(
            document.RootElement, "esp-parameter", out var status, out var result, out var error, out _);

        Assert.True(accepted);
        Assert.Equal("ok", status);
        Assert.Equal("Well_1:ESP", result?.GetProperty("espContext").GetString());
        Assert.Equal(70, result?.GetProperty("appliedValue").GetDouble());
        Assert.Null(error);
    }

    [Fact]
    public void AcceptsDimensionlessEspHeadFactorResultV2()
    {
        using var document = JsonDocument.Parse("""
            {
              "status":"ok", "result":{
                "schemaVersion":"pipesim-esp-parameter-result/2", "model_kind":"legacy_well", "runTask":"esp-parameter", "resultContract":"VALID_FULL", "producer":"Well_1", "espContext":"Well_1:ESP",
                "parameter":"headFactor", "unit":"1", "originalValue":2.5, "targetValue":0.9, "appliedValue":0.9, "sourceModelUnchanged":true, "isolatedCopy":true,
                "baseline":{"parameterValue":2.5,"ipr":[{"flow":100,"pressure":1200}],"vlp":[{"flow":100,"pressure":1300}],"profile":[{"depth":0,"pressure":1500,"temperature":80}]},
                "target":{"parameterValue":0.9,"ipr":[{"flow":100,"pressure":1201}],"vlp":[{"flow":100,"pressure":1301}],"profile":[{"depth":0,"pressure":1500,"temperature":80}]},
                "comparison":{"maxIprPressureDelta":1,"maxVlpPressureDelta":1,"maxProfilePressureDelta":null,"maxProfileTemperatureDelta":null}
              }, "warnings":[]
            }
            """);

        var accepted = PtkRunService.TryReadEnvelope(
            document.RootElement, "esp-parameter", out var status, out var result, out var error, out _);

        Assert.True(accepted);
        Assert.Equal("ok", status);
        Assert.Equal(0.9, result?.GetProperty("targetValue").GetDouble());
        Assert.Null(error);
    }

    [Fact]
    public void RejectsWellTrajectoryWithNonIncreasingMeasuredDepth()
    {
        using var document = JsonDocument.Parse("""
            {
              "status":"ok", "result":{
                "schemaVersion":"pipesim-well-trajectory-result/1", "model_kind":"black_oil_liquid", "runTask":"trajectory", "resultContract":"VALID_FULL", "producer":"Well_1",
                "units":{"measuredDepth":"ft","trueVerticalDepth":"ft","inclination":"deg","azimuth":"deg","maxDogLegSeverity":"deg/100ft"},
                "points":[
                  {"measuredDepth":1000,"trueVerticalDepth":980,"inclination":25,"azimuth":90,"maxDogLegSeverity":1.2},
                  {"measuredDepth":1000,"trueVerticalDepth":980,"inclination":25,"azimuth":90,"maxDogLegSeverity":1.2}
                ]
              }, "warnings":[]
            }
            """);

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "trajectory", out _, out _, out _, out _));
    }

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
        Assert.Equal(2, result?.GetProperty("system").GetArrayLength());
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

    [Fact]
    public void FullNetworkAcceptsPlainLocalPipePlaceholderAndRun36NullQualityShape()
    {
        using var document = NetworkEnvelope("ok", $$"""
            "system": [{ "variable": "Pressure", "unit": "psi", "values": [{ "name": "Network", "value": null }] }],
            "node": [],
            "profiles": [{ "branch": "Branch 1", "pointCount": 1, "variables": [{ "variable": "TotalDistance", "unit": "ft", "values": [0] }, { "variable": "Pressure", "unit": "psi", "values": [null] }] }],
            "summary": { "info": ["Service endpoint [local pipe]"], "warnings": [], "errors": [] },
            "messages": ["Network simulation completed via [local pipe]"],
            "quality": [{ "path": "system.Pressure.Network", "code": "UNAVAILABLE" }, { "path": "profiles.Branch 1.Pressure[0]", "code": "NON_FINITE" }]
            """);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out var result, out _, out _));
        Assert.Equal("VALID_FULL", result?.GetProperty("resultContract").GetString());
        Assert.Contains("[local pipe]", result?.GetRawText());
    }

    [Fact]
    public void FullNetworkAcceptsOfficialNativeStringAndBooleanNodeValues()
    {
        using var document = NetworkEnvelope("ok", $$"""
            "system": [
              { "variable": "Route", "unit": "", "values": [{ "name": "Network", "value": "MOLLIER" }] },
              { "variable": "FlowrateBeyondCurveMaxRate", "unit": "", "values": [{ "name": "Network", "value": false }] }
            ],
            "node": [{ "variable": "LimitedBy", "unit": "", "values": [{ "name": "Node 1", "value": "POWER" }] }],
            "profiles": {{DefaultProfiles}},
            "summary": { "info": [], "warnings": [], "errors": [] },
            "messages": [],
            "quality": []
            """);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out var result, out _, out _));
        Assert.Equal("MOLLIER", result?.GetProperty("system")[0].GetProperty("values")[0].GetProperty("value").GetString());
        Assert.False(result?.GetProperty("system")[1].GetProperty("values")[0].GetProperty("value").GetBoolean());
    }

    [Fact]
    public void FullNetworkAcceptsOfficialDimensionedNetworkUnit()
    {
        using var document = NetworkEnvelope("ok", $$"""
            "system": [{ "variable": "CompressorHead", "unit": "(ft.lbf)[redacted]", "values": [{ "name": "Pmp", "value": 3794.2179840178305 }] }],
            "node": [],
            "profiles": {{DefaultProfiles}},
            "summary": { "info": [], "warnings": [], "errors": [] },
            "messages": [],
            "quality": []
            """);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out var error, out _));
        Assert.Null(error);
    }

    [Theory]
    [InlineData("summary", "net.pipe://localhost/pipe/[redacted]")]
    [InlineData("summary", "C:\\\\private\\result.log")]
    [InlineData("messages", "net.pipe://localhost/pipe/private-id")]
    public void FullNetworkRejectsUnsafeSummaryOrMessages(string section, string unsafeText)
    {
        var summary = section == "summary"
            ? $"{{ \"info\": [{JsonSerializer.Serialize(unsafeText)}], \"warnings\": [], \"errors\": [] }}"
            : "{ \"info\": [], \"warnings\": [], \"errors\": [] }";
        var messages = section == "messages" ? $"[{JsonSerializer.Serialize(unsafeText)}]" : "[]";
        using var document = NetworkEnvelope("ok", $$"""
            "system": [], "node": [], "profiles": {{DefaultProfiles}},
            "summary": {{summary}}, "messages": {{messages}}, "quality": []
            """);

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Theory]
    [InlineData("{ \"path\": \"profiles.Branch 1.Pressure[0]\", \"code\": \"UNKNOWN\" }")]
    [InlineData("{ \"path\": \"net.pipe://localhost/pipe/private-quality\", \"code\": \"UNAVAILABLE\" }")]
    [InlineData("{ \"path\": \"profiles.Branch 1.Pressure[0]\", \"code\": \"UNAVAILABLE\", \"message\": \"extra\" }")]
    public void FullNetworkRejectsInvalidQualityItems(string item)
    {
        using var document = NetworkEnvelope("ok", $$"""
            "system": [], "node": [], "profiles": {{DefaultProfiles}},
            "summary": { "info": [], "warnings": [], "errors": [] }, "messages": [], "quality": [{{item}}]
            """);

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Fact]
    public void FullNetworkRejectsDuplicateQualityPaths()
    {
        using var document = NetworkEnvelope("ok", $$"""
            "system": [], "node": [], "profiles": {{DefaultProfiles}},
            "summary": { "info": [], "warnings": [], "errors": [] }, "messages": [],
            "quality": [{ "path": "profiles.Branch 1.Pressure[0]", "code": "UNAVAILABLE" }, { "path": "profiles.Branch 1.Pressure[0]", "code": "NON_FINITE" }]
            """);

        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "network", out _, out _, out _, out _));
    }

    [Fact]
    public void TubingEditAcceptsOnlyReadBackValuesBoundToTheParameterRequest()
    {
        using var parameters = JsonDocument.Parse("""
            {"schemaVersion":"pipesim-well-tubing-diameter-parameters/1","baselineRunId":7401,"wellContext":"Well_1","tubingContext":"Tubing_1","originalInnerDiameter":2.441,"unit":"in","targetInnerDiameter":2.3}
            """);
        using var document = JsonDocument.Parse("""
            {"status":"ok","result":{"schemaVersion":"pipesim-well-result/2","model_kind":"black_oil_liquid","runTask":"profile","resultContract":"VALID_FULL","units":{"flow":{"displayUnit":null,"semantics":"unspecified"},"pressure":{"displayUnit":null,"semantics":"unspecified"},"depth":{"displayUnit":null,"semantics":"unspecified"},"temperature":{"displayUnit":null,"semantics":"unspecified"}},"ipr":[],"vlp":[],"profile":[{"depth":0.0,"pressure":1500.0,"temperature":80.0}],"application":{"applied":true,"isolatedCopy":true,"sourceModelUnchanged":true,"artifactName":"pipesim-well-tubing-applied.pips","wellContext":"Well_1","tubingContext":"Tubing_1","unit":"in","originalInnerDiameter":2.441,"targetInnerDiameter":2.3,"appliedInnerDiameter":2.3}}}
            """);

        Assert.True(PtkRunService.TryReadEnvelope(document.RootElement, "profile", out var status, out var result, out _, out _, parameters.RootElement));
        Assert.Equal("ok", status);
        Assert.Equal("pipesim-well-result/2", result?.GetProperty("schemaVersion").GetString());

        using var changed = JsonDocument.Parse(document.RootElement.GetRawText().Replace("\"appliedInnerDiameter\":2.3", "\"appliedInnerDiameter\":2.31"));
        Assert.False(PtkRunService.TryReadEnvelope(changed.RootElement, "profile", out _, out _, out _, out _, parameters.RootElement));
        Assert.False(PtkRunService.TryReadEnvelope(document.RootElement, "nodal", out _, out _, out _, out _, parameters.RootElement));
    }

    private const string DefaultProfiles = "[{ \"branch\": \"Branch 1\", \"pointCount\": 1, \"variables\": [{ \"variable\": \"TotalDistance\", \"unit\": \"ft\", \"values\": [1] }, { \"variable\": \"Pressure\", \"unit\": \"psi\", \"values\": [2] }] }]";
    private const string DefaultTopology = "{ \"nodes\": [{ \"id\": \"Source 1\", \"componentType\": \"SOURCE\" }, { \"id\": \"Sink 1\", \"componentType\": \"SINK\" }], \"edges\": [{ \"source\": \"Source 1\", \"destination\": \"Sink 1\", \"sourcePort\": \"OUTLET\" }], \"counts\": { \"nodes\": 2, \"edges\": 1, \"sources\": 1, \"sinks\": 1, \"flowlines\": 0 } }";

    private static string FullPayload(string system, string? profiles = null) => $$"""
        "system": {{system}},
        "node": [],
        "profiles": {{profiles ?? DefaultProfiles}},
        "summary": { "info": [], "warnings": [], "errors": [] }, "messages": [], "quality": []
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
