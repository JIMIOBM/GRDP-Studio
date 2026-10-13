import copy
import json
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from worker import ptk_run
from worker import ptk_validate


ROOT = Path(__file__).resolve().parents[3]
COMPARATOR = ROOT / "worker/tests/Golden/Compare-RunToGolden.ps1"
GOLDEN = ROOT / "docs/software-integration/golden/pipesim-well-result-v1/CSW_101/nodal.json"


class Named:
    def __init__(self, name):
        self.name = name


class Catalog:
    def __init__(self, studies):
        self.studies = studies

    def lookup_entries_by_class_ids(self, _):
        return [Named(name) for name in self.studies]


class Curve:
    def __init__(self, data):
        self.curve_data = data


class NodalResult:
    def __init__(self, gas=False):
        rate = "GasRate" if gas else "LiquidRate"
        self.inflow_curves = [Curve([{rate: 2, "Pressure": 20}])]
        self.outflow_curves = [Curve([{rate: 3, "Pressure": 30}])]


class ProfileResult:
    profile = {"Well_1": [{"MeasuredDepth": -10, "Pressure": 100, "Temperature": 60}]}


class EspResult:
    def __init__(self):
        curve = {
            ("Inputs",): {
                ("Frequency",): {("Value",): 60, ("Unit",): "Hz"},
                ("Manufacturer",): {("Value",): "REDA", ("Unit",): " "},
                ("Model",): {("Value",): "J7000N", ("Unit",): " "},
                ("MinFlowrate",): {("Value",): 4500, ("Unit",): "bbl/d"},
                ("MaxFlowrate",): {("Value",): 9000, ("Unit",): "bbl/d"},
                ("BaseStages",): {("Value",): 100, ("Unit",): " "},
            },
            ("Speed",): {
                ("Frequencies",): {
                    "FREQ= 60.00 Hz": {
                        ("Flowrate",): {("Values",): [100, 200], ("Unit",): "bbl/d"},
                        ("Head",): {("Values",): [1000, 800], ("Unit",): "ft"},
                    }
                },
                ("OperatingEnvelope",): {
                    ("MinCurve",): {("Flowrate",): {("Values",): [100], ("Unit",): "bbl/d"}, ("Head",): {("Values",): [900], ("Unit",): "ft"}},
                    ("BepCurve",): {("Flowrate",): {("Values",): [150], ("Unit",): "bbl/d"}, ("Head",): {("Values",): [850], ("Unit",): "ft"}},
                    ("MaxCurve",): {("Flowrate",): {("Values",): [200], ("Unit",): "bbl/d"}, ("Head",): {("Values",): [700], ("Unit",): "ft"}},
                },
            },
        }
        self.esp_curves = {"Flowrate=100 sbbl/day": {"B-ESP": curve}}


class TrajectoryFrame:
    columns = ["MeasuredDepth", "TrueVerticalDepth", "Inclination", "Azimuth", "MaxDogLegSeverity"]

    def to_dict(self, orient="records"):
        self.orient = orient
        return [
            {"MeasuredDepth": 0.0, "TrueVerticalDepth": 0.0, "Inclination": 0.0, "Azimuth": None, "MaxDogLegSeverity": None},
            {"MeasuredDepth": 1000.0, "TrueVerticalDepth": 980.0, "Inclination": 25.0, "Azimuth": 90.0, "MaxDogLegSeverity": 1.2},
        ]


class Runnable:
    def __init__(self, callback):
        self.callback = callback
        self.validation_issues = []
        self.validation_calls = []
        self.condition_calls = []

    def run(self, **kwargs):
        return self.callback(kwargs)

    def validate(self, **kwargs):
        self.validation_calls.append(kwargs)
        return self.validation_issues

    def set_conditions(self, **kwargs):
        self.condition_calls.append(kwargs)


class FakeModel:
    def __init__(self, profile_failure=None, nodal_failure=None, studies=None, gas=False):
        self._catalog = Catalog(studies or ["Study 1"])
        self.about = types.SimpleNamespace(unit_system="PIPESIM_FIELD")
        self.gas = gas
        self.fluids = types.SimpleNamespace(fluid_type="compositional" if gas else "blackoil")
        self.closed = False
        self.tasks = types.SimpleNamespace(
            nodalanalysis=Runnable(
                lambda _: (_ for _ in ()).throw(nodal_failure)
                if nodal_failure is not None
                else NodalResult(gas)
            ),
            ptprofilesimulation=Runnable(
                lambda _: (_ for _ in ()).throw(profile_failure)
                if profile_failure is not None
                else ProfileResult()
            ),
        )

    def find(self, component, **context):
        well_filter = context.get("Well")
        return {
            "Well": ["Well_1"] if well_filter is None else [well_filter],
            "BlackOilFluid": ["Fluid_1"],
            "Completion": ["Completion_1"] if well_filter is None or well_filter.startswith("Well_") else [],
            "Tubing": ["Tubing_1"] if well_filter is None or well_filter.startswith("Well_") else [],
            "ESP": ["Well_1:ESP"] if well_filter in (None, "Well_1") else [],
        }[component]

    def get_value(self, component, parameter=None):
        if parameter == "geometry":
            return "vertical"
        if parameter == "associated_fluid":
            return "Fluid_1"
        raise AssertionError("Unexpected model read")

    def set_value(self, *args, **kwargs):
        raise AssertionError("Demo-01 must never overwrite model parameters")

    def close(self):
        self.closed = True


class NetworkWellPerformanceModel(FakeModel):
    def __init__(self, diameter_unit="in", state="Completed"):
        super().__init__()
        self.diameter_unit = diameter_unit
        self.curves = types.SimpleNamespace(
            state=state,
            system={
                "SystemPressureLoss": {"Unit": "psi", "System": 125.5},
                "WellCurvePerformancePWI": {"Unit": "", "System": "WELL PERFORMANCE TEXT"},
            },
            node={
                "Case-1": {"Pressure": {"Unit": "psia", "Well": 1000.0}},
                "Case-2": {"Pressure": {"Unit": "psia", "Well": 900.0}},
                "Case-3": {"Pressure": {"Unit": "psia", "Well": 800.0}},
            },
            summary={"Info": ["Finished"], "Warning": [], "Error": []},
            messages=[],
        )
        self.tasks.wellperformancecurvessimulation = Runnable(lambda _: self.curves)

    def find(self, component, **context):
        if component == "Well":
            return ["Well"]
        if component == "Flowline":
            return ["FL-2"]
        return super().find(component, **context)

    def describe(self, context=None, parameter=None):
        return types.SimpleNamespace(units_symbol=self.diameter_unit)


class FakeEspParameterModel(FakeModel):
    def __init__(self):
        super().__init__()
        self.frequency = 60.0
        self.head_factor = 1.0
        self.esp_contexts = ["Well_1:ESP"]

    def find(self, component, **context):
        if component == "ESP":
            well_filter = context.get("Well")
            return self.esp_contexts if well_filter is None else [item for item in self.esp_contexts if item.startswith(well_filter + ":")]
        if component == "Well":
            return sorted({"Well_1", *(item.split(":", 1)[0] for item in self.esp_contexts)})
        return super().find(component, **context)

    def describe(self, context=None, parameter=None):
        return types.SimpleNamespace(units_symbol="Hz" if parameter == "operating_frequency" else None)

    def get_value(self, component=None, parameter=None, context=None):
        if parameter == "operating_frequency":
            return self.frequency
        if parameter == "head_factor":
            return self.head_factor
        return super().get_value(component, parameter)

    def set_value(self, component=None, parameter=None, value=None, context=None):
        if context not in self.esp_contexts:
            raise AssertionError("Unexpected ESP parameter write")
        if parameter == "operating_frequency":
            self.frequency = value
        elif parameter == "head_factor":
            self.head_factor = value
        else:
            raise AssertionError("Unexpected ESP parameter write")


class MultiWellFakeModel(FakeModel):
    def find(self, component, **context):
        if component == "Well":
            return ["Well_1", "Well_2"]
        return super().find(component, **context)


def fake_sixgill_modules():
    definitions = types.ModuleType("sixgill.definitions")
    definitions.Units = types.SimpleNamespace(FIELD="PIPESIM_FIELD", METRIC="PIPESIM_METRIC", SI="SI")
    definitions.Constants = types.SimpleNamespace(
        FluidType=types.SimpleNamespace(COMPOSITIONAL="compositional", BLACKOIL="blackoil"),
        CalculatedVariable=types.SimpleNamespace(FLOWRATE="flowrate"),
        FlowRateType=types.SimpleNamespace(GASFLOWRATE="gasflowrate"),
        VFPTablesOperationTable=types.SimpleNamespace(ECLIPSE="eclipse"),
    )
    definitions.Parameters = types.SimpleNamespace(
        Completion=types.SimpleNamespace(GEOMETRYPROFILETYPE="geometry", RESERVOIRPRESSURE="reservoir_pressure"),
        Well=types.SimpleNamespace(
            ASSOCIATEDBLACKOILFLUID="associated_fluid",
            DeviationSurvey=types.SimpleNamespace(
                SURVEYTYPE="SurveyType", RELATIVEDEPTHOPTION="DeviationRelativeDepth",
                DEPENDENTPARAMETER="TrajectoryDependantParameter",
                CALCULATEUSINGTANGENTIALMETHOD="CalculateUsingTangentialMethod",
            ),
        ),
        Tubing=types.SimpleNamespace(INNERDIAMETER="inner_diameter"),
        BlackOilFluid=types.SimpleNamespace(WATERCUT="water_cut", GOR="gor"),
        GasliftSensitivity=types.SimpleNamespace(COMPONENTNAME="component_name", MINVALVEINJECTIONDP="MinValveInjectionDP"),
        VfpTablesSimulation=types.SimpleNamespace(
            RESERVOIRSIMULATOR="reservoir_simulator", TABLENUMBER="table_number",
            INCLUDETEMPERATURE="include_temperature", BOTTOMHOLEDATUMDEPTH="bottom_hole_datum_depth",
            RATETYPE="rate_type", GLRATIOTYPE="gl_ratio_type", GWRATIOTYPE="gw_ratio_type",
            SENSITIVITYVARIABLES="sensitivity_variables", LIQUIDRATESENSITIVITY="liquid_rates",
            OUTLETPRESSURESENSITIVITY="outlet_pressures", WATERCUTSENSITIVITY="water_cuts",
            GORSENSITIVITY="gor_values", ARTIFICIALLIFTSENSITIVITY="artificial_lift",
            LIQUIDFLOWRATE="liquid_flow_rate", GOR="gor", WATERCUT="water_cut",
            SensitivityVariable=types.SimpleNamespace(COMPONENT="component", VARIABLE="variable", VALUES="values"),
        ),
        PTProfileSimulation=types.SimpleNamespace(
            CALCULATEDVARIABLE="calculated_variable",
            FLOWRATETYPE="flow_rate_type",
            INLETPRESSURE="inlet_pressure",
            OUTLETPRESSURE="outlet_pressure",
        ),
        NodalAnalysisSimulation=types.SimpleNamespace(OUTLETPRESSURE="outlet_pressure"),
        ESP=types.SimpleNamespace(OPERATINGFREQUENCY="operating_frequency", HEADFACTOR="head_factor"),
        Flowline=types.SimpleNamespace(INNERDIAMETER="inner_diameter"),
        WellPerformanceCurvesSimulation=types.SimpleNamespace(
            BRANCHTERMINATOR="branch_terminator", FLOWRATEPOINTS="flowrate_points", SENSITIVITYVARIABLES="sensitivity_variables",
            SensitivityVariable=types.SimpleNamespace(COMPONENT="component", VARIABLE="variable", VALUES="values"),
        ),
    )
    definitions.ProfileVariables = types.SimpleNamespace(TEMPERATURE="temperature", PRESSURE="pressure", ELEVATION="elevation", TOTAL_DISTANCE="distance")
    definitions.SystemVariables = types.SimpleNamespace(
        PRESSURE="pressure",
        TEMPERATURE="temperature",
        VOLUME_FLOWRATE_LIQUID_STOCKTANK="liquid_flow",
        VOLUME_FLOWRATE_GAS_STOCKTANK="gas_flow",
        WELL_CURVE_PERFORMANCE_PWI="WellCurvePerformancePWI",
        SYSTEM_PRESSURE_LOSS="SystemPressureLoss",
        SYSTEM_TEMPERATURE_DIFFERENCE="SystemTemperatureDifference",
        CASE_NUMBER="CaseNumber",
        VFP_TABLES="VfpTables",
        VFP_TABLES_WITH_TEMPERATURE="VfpTablesWithTemperature",
    )
    definitions.EspCurvesVariables = types.SimpleNamespace(
        INPUTS=("Inputs",), VARIABLESPEEDCURVE=("Speed",), FREQUENCIES=("Frequencies",), OPERATINGENVELOPE=("OperatingEnvelope",),
        MINCURVE=("MinCurve",), BEPCURVE=("BepCurve",), MAXCURVE=("MaxCurve",), FREQUENCY=("Frequency",), MANUFACTURER=("Manufacturer",),
        MODEL=("Model",), MINFLOWRATE=("MinFlowrate",), MAXFLOWRATE=("MaxFlowrate",), STAGES=("BaseStages",), FLOWRATE=("Flowrate",),
        HEAD=("Head",), VALUE=("Value",), VALUES=("Values",), UNIT=("Unit",), VARIABLEPERFORMANCECURVE="Performance",
    )
    resources = types.ModuleType("sixgill.core.resources")
    resources.ModelClasses = types.SimpleNamespace(STUDY="study")
    return {
        "sixgill": types.ModuleType("sixgill"),
        "sixgill.definitions": definitions,
        "sixgill.core": types.ModuleType("sixgill.core"),
        "sixgill.core.resources": resources,
    }


class PtkRunContractTests(unittest.TestCase):
    def test_vfp_table_rows_map_thp_indexes_to_liquid_rate_values_for_non_square_axes(self):
        axes = {
            "liquidRatesStbPerDay": [100, 200, 300],
            "outletPressuresPsi": [250, 350],
            "waterCutFraction": [0.4],
            "gorMscfPerStb": [0.265],
            "artificialLiftInjectionDpPsi": [0, 20],
        }
        content = """-- Table body:
1 1 1 1 100 200 300 /
1 1 1 2 400 500 600 /
2 1 1 1 700 800 900 /
2 1 1 2 1000 1100 1200 /
"""

        table = ptk_run._parse_vfp_table(content, "BHP", "psia", axes)

        self.assertEqual("BHP", table["valueName"])
        self.assertEqual(4, len(table["rows"]))
        self.assertEqual({"thpIndex": 1, "waterCutIndex": 1, "gorIndex": 1, "artificialLiftIndex": 1, "values": [100, 200, 300]}, table["rows"][0])
        self.assertEqual({"thpIndex": 2, "waterCutIndex": 1, "gorIndex": 1, "artificialLiftIndex": 2, "values": [1000, 1100, 1200]}, table["rows"][3])

    def test_vfp_table_parser_rejects_incomplete_or_transposed_table_rows(self):
        axes = {
            "liquidRatesStbPerDay": [100, 200, 300],
            "outletPressuresPsi": [250, 350],
            "waterCutFraction": [0.4],
            "gorMscfPerStb": [0.265],
            "artificialLiftInjectionDpPsi": [0, 20],
        }
        incomplete = """-- Table body:
1 1 1 1 100 200 300 /
1 1 1 2 400 500 600 /
2 1 1 1 700 800 900 /
"""
        transposed = """-- Table body:
1 1 1 1 100 200 /
1 1 1 2 400 500 /
2 1 1 1 700 800 /
2 1 1 2 1000 1100 /
"""

        with self.assertRaises(ptk_run.AdapterFailure):
            ptk_run._parse_vfp_table(incomplete, "BHP", "psia", axes)
        with self.assertRaises(ptk_run.AdapterFailure):
            ptk_run._parse_vfp_table(transposed, "BHP", "psia", axes)

    def test_vfp_tables_binds_explicit_producer_to_model_well_context(self):
        model = MultiWellFakeModel()
        parameters = {
            "schemaVersion": "pipesim-vfp-tables-parameters/1",
            "producer": "Well_2",
            "reservoirSimulator": "ECLIPSE",
            "tableNumber": 2,
            "includeTemperature": True,
            "bottomHoleDatumDepth": 30,
            "liquidRatesStbPerDay": [200, 300],
            "outletPressuresPsi": [250, 350],
            "waterCutFraction": [0.4],
            "gorMscfPerStb": [0.265],
            "artificialLiftInjectionDpPsi": [40, 50],
        }
        selected_contexts = []

        def capture_context(_model, study, components, _model_kind, _parameters, _emit_event, _artifact_directory=None):
            selected_contexts.append((components["Well"][0], study, _artifact_directory))
            return {"producer": components["Well"][0]}

        with tempfile.TemporaryDirectory() as artifact_directory:
            with patch.object(ptk_run, "_execute_vfp_tables", side_effect=capture_context):
                envelope, _, _ = self.execute("vfp-tables", model, parameters, artifact_directory=artifact_directory)

        self.assertEqual("ok", envelope["status"])
        self.assertEqual([("Well_2", "Study 1", artifact_directory)], selected_contexts)
        self.assertEqual({"producer": "Well_2"}, envelope["result"])

    def test_vfp_tables_passes_selected_study_to_official_task(self):
        run_calls = []
        condition_calls = []
        condition_readback_calls = []
        variable_discovery_calls = []
        def generate_engine_files(**kwargs):
            generated_path = Path(kwargs["folder_path"]) / "CSW_131_VFP.DATA"
            generated_path.write_text("VFPPROD\n 2 30 /\n", encoding="utf-8")
            return [str(generated_path)]

        model = types.SimpleNamespace(tasks=types.SimpleNamespace(vfptablessimulation=types.SimpleNamespace(
            get_artificial_lift_variables=lambda **kwargs: variable_discovery_calls.append(kwargs) or ["MinValveInjectionDP"],
            set_conditions=lambda **kwargs: condition_calls.append(kwargs),
            get_conditions=lambda **kwargs: condition_readback_calls.append(kwargs) or {"tableNumber": 2},
            generate_engine_files=generate_engine_files,
            run=lambda **kwargs: run_calls.append(kwargs) or types.SimpleNamespace(
                state="Completed",
                system={"VfpTables": {"Well_2": "BHP TABLE"},
                        "VfpTablesWithTemperature": {"Well_2": "TEMP TABLE"}},
            )
        )))
        parameters = {
            "schemaVersion": "pipesim-vfp-tables-parameters/1", "producer": "Well_2",
            "reservoirSimulator": "ECLIPSE", "tableNumber": 2, "includeTemperature": True,
            "bottomHoleDatumDepth": 30, "liquidRatesStbPerDay": [200, 300],
            "outletPressuresPsi": [250, 350], "waterCutFraction": [0.4],
            "gorMscfPerStb": [0.265], "artificialLiftInjectionDpPsi": [40, 50],
        }
        axes = {
            "LIQ flowrate values": [200.0, 300.0], "THP values": [250.0, 350.0],
            "WCT  values": [0.4], "GOR  values": [0.265], "ALQ": [40.0, 50.0],
        }
        table = {"valueName": "BHP", "unit": "psia", "rows": []}
        with tempfile.TemporaryDirectory() as engine_directory:
            with patch.dict(sys.modules, fake_sixgill_modules()):
                with patch.object(ptk_run, "_vfp_axis_values", side_effect=lambda _text, axis: axes[axis]):
                    with patch.object(ptk_run, "_parse_vfp_table", side_effect=[table, {**table, "valueName": "TEMP", "unit": "F"}]):
                        result = ptk_run._execute_vfp_tables(
                            model, "Study 2", {"Well": ["Well_2"]}, "black_oil_liquid", parameters,
                            lambda *_: None, engine_directory,
                        )

            manifest = json.loads((Path(engine_directory) / "engine-files.json").read_text(encoding="utf-8"))

        self.assertEqual("Study 2", run_calls[0]["study"])
        self.assertEqual("Well_2", run_calls[0]["producer"])
        self.assertEqual([{"producer": "Well_2", "study": "Study 2"}], variable_discovery_calls)
        self.assertEqual("Study 2", condition_calls[0]["study"])
        self.assertEqual([{"producer": "Well_2", "study": "Study 2"}], condition_readback_calls)
        self.assertEqual(["CSW_131_VFP.DATA"], manifest["files"])
        self.assertEqual("pipesim-vfp-tables-result/2", result["schemaVersion"])

    def test_vfp_tables_rejects_unsupported_discovered_lift_variable_before_mutating_task(self):
        condition_calls = []
        run_calls = []
        task = types.SimpleNamespace(
            get_artificial_lift_variables=lambda **kwargs: [],
            set_conditions=lambda **kwargs: condition_calls.append(kwargs),
            run=lambda **kwargs: run_calls.append(kwargs),
        )
        model = types.SimpleNamespace(tasks=types.SimpleNamespace(vfptablessimulation=task))
        parameters = {
            "schemaVersion": "pipesim-vfp-tables-parameters/1", "producer": "Well_2",
            "reservoirSimulator": "ECLIPSE", "tableNumber": 2, "includeTemperature": True,
            "bottomHoleDatumDepth": 30, "liquidRatesStbPerDay": [200, 300],
            "outletPressuresPsi": [250, 350], "waterCutFraction": [0.4],
            "gorMscfPerStb": [0.265], "artificialLiftInjectionDpPsi": [40, 50],
        }

        with patch.dict(sys.modules, fake_sixgill_modules()):
            with self.assertRaises(ptk_run.AdapterFailure) as raised:
                ptk_run._execute_vfp_tables(
                    model, "Study 2", {"Well": ["Well_2"]}, "black_oil_liquid", parameters,
                    lambda *_: None,
                )

        self.assertEqual("VFP_ARTIFICIAL_LIFT_VARIABLE_UNSUPPORTED", raised.exception.code)
        self.assertEqual([], condition_calls)
        self.assertEqual([], run_calls)

    def test_vfp_tables_does_not_run_when_selected_study_conditions_cannot_be_read_back(self):
        condition_calls = []
        run_calls = []
        task = types.SimpleNamespace(
            get_artificial_lift_variables=lambda **kwargs: ["MinValveInjectionDP"],
            set_conditions=lambda **kwargs: condition_calls.append(kwargs),
            get_conditions=lambda **kwargs: {},
            run=lambda **kwargs: run_calls.append(kwargs),
        )
        model = types.SimpleNamespace(tasks=types.SimpleNamespace(vfptablessimulation=task))
        parameters = {
            "schemaVersion": "pipesim-vfp-tables-parameters/1", "producer": "Well_2",
            "reservoirSimulator": "ECLIPSE", "tableNumber": 2, "includeTemperature": True,
            "bottomHoleDatumDepth": 30, "liquidRatesStbPerDay": [200, 300],
            "outletPressuresPsi": [250, 350], "waterCutFraction": [0.4],
            "gorMscfPerStb": [0.265], "artificialLiftInjectionDpPsi": [40, 50],
        }

        with patch.dict(sys.modules, fake_sixgill_modules()):
            with self.assertRaises(ptk_run.AdapterFailure) as raised:
                ptk_run._execute_vfp_tables(
                    model, "Study 2", {"Well": ["Well_2"]}, "black_oil_liquid", parameters,
                    lambda *_: None,
                )

        self.assertEqual("VFP_CONDITIONS_READBACK_FAILED", raised.exception.code)
        self.assertEqual("Study 2", condition_calls[0]["study"])
        self.assertEqual([], run_calls)

    def test_network_well_performance_curves_run_official_task_and_normalize_cases_and_pwi(self):
        model = NetworkWellPerformanceModel()
        parameters = {
            "schemaVersion": "pipesim-network-well-performance-parameters/1",
            "producerContext": "Well",
            "branchTerminator": "FL-2",
            "sensitivityUnit": "in",
            "sensitivityValues": [4, 5, 6],
        }

        envelope, events, _ = self.execute("network-well-performance-curves", model, parameters)

        self.assertEqual("ok", envelope["status"])
        result = envelope["result"]
        self.assertEqual("pipesim-network-well-performance-result/1", result["schemaVersion"])
        self.assertEqual([4.0, 5.0, 6.0], result["sensitivity"]["values"])
        self.assertEqual(["Case-1", "Case-2", "Case-3"], [item["caseId"] for item in result["cases"]])
        self.assertEqual([1000.0, 900.0, 800.0], [item["node"][0]["values"][0]["value"] for item in result["cases"]])
        self.assertEqual("WELL PERFORMANCE TEXT", result["pwiArtifact"]["content"])
        task = model.tasks.wellperformancecurvessimulation
        self.assertEqual(["RUNNING_NETWORK", "COLLECTING"], [item[0] for item in events])
        self.assertEqual("Well", task.condition_calls[0]["producer"])
        self.assertEqual("Study 1", task.condition_calls[0]["study"])
        self.assertEqual("FL-2", task.condition_calls[0]["parameters"]["branch_terminator"])
        self.assertEqual([4.0, 5.0, 6.0], task.condition_calls[0]["parameters"]["sensitivity_variables"][0]["values"])
        self.assertTrue(model.closed)

    def test_network_well_performance_curves_fails_closed_for_metric_inner_diameter(self):
        model = NetworkWellPerformanceModel(diameter_unit="mm")
        parameters = {
            "schemaVersion": "pipesim-network-well-performance-parameters/1",
            "producerContext": "Well",
            "branchTerminator": "FL-2",
            "sensitivityUnit": "in",
            "sensitivityValues": [4, 5, 6],
        }

        envelope, events, _ = self.execute("network-well-performance-curves", model, parameters)

        self.assertEqual("error", envelope["status"])
        self.assertEqual("NETWORK_DIAMETER_UNIT_UNSUPPORTED", envelope["error"]["code"])
        self.assertEqual([], model.tasks.wellperformancecurvessimulation.condition_calls)
        self.assertEqual([], events)

    def test_trajectory_edit_rejects_unverified_unit_system(self):
        model = FakeModel()
        model.about.unit_system = "PIPESIM_METRIC"
        with patch.dict(sys.modules, fake_sixgill_modules()):
            with self.assertRaises(ptk_run.AdapterFailure) as failure:
                ptk_run._trajectory_supported_p101_configuration(model, "Well_1")
        self.assertEqual("TRAJECTORY_EDIT_UNITS_UNSUPPORTED", failure.exception.code)

    def test_esp_curves_runs_official_two_task_shape(self):
        model = FakeModel()
        model.tasks.ptprofilesimulation = Runnable(lambda _: EspResult())
        model.tasks.nodalanalysis = Runnable(lambda _: EspResult())
        envelope, events, _ = self.execute(
            "esp-curves", model, {"schemaVersion": "pipesim-esp-curves-parameters/1"}
        )
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(["RUNNING_PROFILE", "RUNNING_NODAL", "COLLECTING"], [item[0] for item in events])
        self.assertEqual("pipesim-esp-curves-result/1", envelope["result"]["schemaVersion"])
        self.assertEqual("REDA", envelope["result"]["pump"]["inputs"]["manufacturer"])
        self.assertEqual(1, len(envelope["result"]["pump"]["frequencies"]))
        self.assertEqual(1, len(envelope["result"]["pump"]["operatingEnvelope"]["bep"]["flowRate"]))

    def test_esp_curves_accepts_official_basic_gas_model_kind(self):
        model = FakeModel(gas=True)
        model.tasks.ptprofilesimulation = Runnable(lambda _: EspResult())
        model.tasks.nodalanalysis = Runnable(lambda _: EspResult())
        envelope, _, _ = self.execute(
            "esp-curves", model, {"schemaVersion": "pipesim-esp-curves-parameters/1"}
        )
        self.assertEqual("ok", envelope["status"])
        self.assertEqual("basic_gas", envelope["result"]["model_kind"])

    def test_esp_parameter_runs_baseline_and_target_on_isolated_copy(self):
        model = FakeEspParameterModel()
        envelope, events, _ = self.execute(
            "esp-parameter",
            model,
            {
                "schemaVersion": "pipesim-esp-parameter-parameters/1",
                "espContext": "AUTO",
                "parameter": "operatingFrequency",
                "targetValueHz": 70,
            },
        )
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(
            ["RUNNING_PROFILE", "RUNNING_NODAL", "RUNNING_PROFILE", "RUNNING_NODAL", "COLLECTING"],
            [item[0] for item in events],
        )
        self.assertIn("baseline", events[0][1])
        self.assertIn("target", events[2][1])
        self.assertEqual("pipesim-esp-parameter-result/1", envelope["result"]["schemaVersion"])
        self.assertEqual("Well_1:ESP", envelope["result"]["espContext"])
        self.assertEqual(60.0, envelope["result"]["originalValue"])
        self.assertEqual(70.0, envelope["result"]["appliedValue"])
        self.assertTrue(envelope["result"]["sourceModelUnchanged"])
        self.assertTrue(envelope["result"]["isolatedCopy"])

    def test_esp_head_factor_runs_official_baseline_and_target_contract(self):
        model = FakeEspParameterModel()
        model.head_factor = 2.5  # Existing model values are not subject to the UI target guard.
        envelope, events, _ = self.execute(
            "esp-parameter",
            model,
            {
                "schemaVersion": "pipesim-esp-parameter-parameters/2",
                "espContext": "AUTO",
                "parameter": "headFactor",
                "targetValue": 0.9,
            },
        )
        self.assertEqual("ok", envelope["status"])
        result = envelope["result"]
        self.assertEqual("pipesim-esp-parameter-result/2", result["schemaVersion"])
        self.assertEqual("headFactor", result["parameter"])
        self.assertEqual("1", result["unit"])
        self.assertEqual(2.5, result["baseline"]["parameterValue"])
        self.assertEqual(0.9, result["target"]["parameterValue"])
        self.assertEqual(["RUNNING_PROFILE", "RUNNING_NODAL", "RUNNING_PROFILE", "RUNNING_NODAL", "COLLECTING"], [item[0] for item in events])

    def test_esp_parameter_requires_explicit_context_when_model_has_multiple_esps(self):
        model = FakeEspParameterModel()
        model.esp_contexts.append("Well_2:ESP")
        envelope, _, _ = self.execute(
            "esp-parameter", model,
            {"schemaVersion": "pipesim-esp-parameter-parameters/1", "espContext": "AUTO",
             "parameter": "operatingFrequency", "targetValueHz": 70},
        )
        self.assertEqual("error", envelope["status"])
        self.assertEqual("ESP_CONTEXT_REQUIRED", envelope["error"]["code"])

        model = FakeEspParameterModel()
        model.esp_contexts.append("Well_2:ESP")
        envelope, _, _ = self.execute(
            "esp-parameter", model,
            {"schemaVersion": "pipesim-esp-parameter-parameters/1", "espContext": "Well_2:ESP",
             "parameter": "operatingFrequency", "targetValueHz": 70},
        )
        self.assertEqual("ok", envelope["status"])
        self.assertEqual("Well_2:ESP", envelope["result"]["espContext"])

    def test_well_context_is_required_for_multiwell_runs_and_selected_for_trajectory(self):
        model = FakeEspParameterModel()
        model.esp_contexts.append("Well_2:ESP")
        model.get_trajectory = lambda **_: TrajectoryFrame()
        envelope, _, _ = self.execute(
            "trajectory", model, {"schemaVersion": "pipesim-well-trajectory-parameters/1"}
        )
        self.assertEqual("WELL_CONTEXT_REQUIRED", envelope["error"]["code"])

        envelope, _, _ = self.execute(
            "trajectory", model,
            {"schemaVersion": "pipesim-well-trajectory-parameters/2", "wellContext": "Well_2"},
        )
        self.assertEqual("ok", envelope["status"])
        self.assertEqual("Well_2", envelope["result"]["producer"])

    def test_trajectory_does_not_require_completion_or_tubing(self):
        class TrajectoryOnlyModel(FakeEspParameterModel):
            def find(self, component, **context):
                if component in ("Completion", "Tubing"):
                    return []
                return super().find(component, **context)

        model = TrajectoryOnlyModel()
        model.get_trajectory = lambda **_: TrajectoryFrame()
        envelope, _, _ = self.execute(
            "trajectory", model,
            {"schemaVersion": "pipesim-well-trajectory-parameters/1"},
        )
        self.assertEqual("ok", envelope["status"])
        self.assertEqual("Well_1", envelope["result"]["producer"])

    def test_esp_parameter_well_and_esp_must_belong_together(self):
        model = FakeEspParameterModel()
        model.esp_contexts.append("Well_2:ESP")
        envelope, _, _ = self.execute(
            "esp-parameter", model,
            {"schemaVersion": "pipesim-esp-parameter-parameters/3", "wellContext": "Well_1",
             "espContext": "Well_2:ESP", "parameter": "headFactor", "targetValue": 0.9},
        )
        self.assertEqual("ESP_CONTEXT_NOT_FOUND", envelope["error"]["code"])

    def test_validation_exposes_only_safe_unique_esp_contexts(self):
        model = types.SimpleNamespace(find=lambda component: [
            "Well_2:ESP", "../unsafe", "Well_1:ESP", "Well_1:ESP", None,
        ])
        self.assertEqual(["Well_1:ESP", "Well_2:ESP"], ptk_validate._esp_contexts(model))

    def test_validation_associates_esps_using_official_well_context_filter(self):
        class MultiWellModel:
            def find(self, component, **context):
                if component == "Well":
                    return ["Well_2", "Well_1"]
                if component == "ESP" and context.get("Well") == "Well_1":
                    return ["Well_1:ESP", "unsafe"]
                if component == "ESP" and context.get("Well") == "Well_2":
                    return ["Well_2:ESP"]
                if component == "Completion" and context.get("Well") == "Well_1":
                    return ["Well_1:Completion"]
                if component == "Tubing" and context.get("Well") == "Well_1":
                    return ["Well_1:Tubing"]
                if component == "ESP":
                    return ["Well_1:ESP", "Well_2:ESP"]
                return []

        with patch.dict(sys.modules, fake_sixgill_modules()):
            self.assertEqual([
                {"context": "Well_1", "espContexts": ["Well_1:ESP"], "completionContexts": ["Well_1:Completion"], "tubingContexts": ["Well_1:Tubing"], "tubingComponents": []},
                {"context": "Well_2", "espContexts": ["Well_2:ESP"], "completionContexts": [], "tubingContexts": [], "tubingComponents": []},
            ], ptk_validate._well_contexts(MultiWellModel(), ["Well_1:ESP", "Well_2:ESP"]))

    def test_esp_parameter_comparison_interpolates_on_common_independent_axis(self):
        baseline = [{"flow": 0, "pressure": 0}, {"flow": 10, "pressure": 100}]
        target = [{"flow": 2, "pressure": 30}, {"flow": 8, "pressure": 70}]
        self.assertEqual(
            10,
            ptk_run._max_aligned_result_delta(baseline, target, "flow", "pressure"),
        )
        self.assertIsNone(ptk_run._max_aligned_result_delta(
            [{"flow": 0, "pressure": 10}],
            [{"flow": 20, "pressure": 15}],
            "flow",
            "pressure",
        ))

    def test_trajectory_reads_official_dataframe_shape_without_writing_model(self):
        model = FakeModel()
        model.get_trajectory = lambda **_: TrajectoryFrame()
        envelope, events, _ = self.execute(
            "trajectory", model, {"schemaVersion": "pipesim-well-trajectory-parameters/1"}
        )
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(["READING_TRAJECTORY", "COLLECTING"], [item[0] for item in events])
        self.assertEqual("pipesim-well-trajectory-result/1", envelope["result"]["schemaVersion"])
        self.assertEqual("Well_1", envelope["result"]["producer"])
        self.assertEqual("ft", envelope["result"]["units"]["measuredDepth"])
        self.assertEqual(2, len(envelope["result"]["points"]))
        self.assertIsNone(envelope["result"]["points"][0]["azimuth"])
        self.assertEqual(90.0, envelope["result"]["points"][1]["azimuth"])

    def test_p101_tangential_trajectory_edit_saves_reopens_and_compares_real_profile_samples(self):
        class EditableFrame:
            def __init__(self, rows):
                self.rows = copy.deepcopy(rows)
                self.columns = list(self.rows[0])
                self.at = self.At(self)

            class At:
                def __init__(self, frame):
                    self.frame = frame

                def __setitem__(self, key, value):
                    row, column = key
                    self.frame.rows[row][column] = value

            def copy(self, deep=True):
                return EditableFrame(copy.deepcopy(self.rows) if deep else self.rows)

            def to_dict(self, orient="records"):
                if orient != "records":
                    raise AssertionError("unexpected trajectory dataframe orientation")
                return copy.deepcopy(self.rows)

        inclinations = [25.0, 26.0, 28.0, 30.0]
        rows = [{"MeasuredDepth": 0.0, "TrueVerticalDepth": 0.0, "Inclination": inclinations[0], "Azimuth": None, "MaxDogLegSeverity": None}]
        for index in range(1, len(inclinations)):
            md = index * 1000.0
            tvd = rows[-1]["TrueVerticalDepth"] + 1000.0 * __import__("math").cos(__import__("math").radians(inclinations[index - 1]))
            rows.append({"MeasuredDepth": md, "TrueVerticalDepth": tvd, "Inclination": inclinations[index], "Azimuth": None, "MaxDogLegSeverity": None})

        model = FakeModel()
        model.trajectory = EditableFrame(rows)
        model.saved = False
        model.set_trajectory_calls = 0
        config = {
            "SurveyType": "TwoDimensional",
            "DeviationRelativeDepth": "OriginalRKB",
            "TrajectoryDependantParameter": "Angle",
            "CalculateUsingTangentialMethod": True,
        }
        model.get_value = lambda component=None, parameter=None, context=None: config[parameter]
        model.get_trajectory = lambda context=None: model.trajectory
        def set_trajectory(context=None, value=None):
            model.trajectory = value.copy(deep=True)
            model.set_trajectory_calls += 1
        model.set_trajectory = set_trajectory
        model.save = lambda: setattr(model, "saved", True)

        class CompletedProfile:
            state = "Completed"
            def __init__(self, pressure_shift):
                self.profile = {"Well_1": [
                    {"Depth": 0.0, "Pressure": 1000.0 + pressure_shift, "Temperature": 60.0 + pressure_shift / 100},
                    {"Depth": 500.0, "Pressure": 900.0 + pressure_shift, "Temperature": 70.0 + pressure_shift / 100},
                    {"Depth": 1000.0, "Pressure": 800.0 + pressure_shift, "Temperature": 80.0 + pressure_shift / 100},
                ]}

        model.tasks.ptprofilesimulation = Runnable(
            lambda _: CompletedProfile(sum(row["Inclination"] for row in model.trajectory.rows))
        )
        parameters = {
            "schemaVersion": "pipesim-well-trajectory-parameters/3",
            "wellContext": "Well_1",
            "stationIndex": 2,
            "targetInclinationDeg": 29.0,
        }
        envelope, events, _ = self.execute("trajectory", model, parameters)

        self.assertEqual("ok", envelope["status"])
        result = envelope["result"]
        self.assertEqual("pipesim-well-trajectory-result/2", result["schemaVersion"])
        self.assertEqual(["RUNNING_PROFILE", "APPLYING_TRAJECTORY", "RUNNING_PROFILE", "COLLECTING"], [item[0] for item in events])
        self.assertTrue(model.saved)
        self.assertEqual(1, model.set_trajectory_calls)
        self.assertEqual(29.0, result["points"][2]["inclination"])
        self.assertAlmostEqual(result["points"][2]["trueVerticalDepth"] + 1000 * __import__("math").cos(__import__("math").radians(29.0)), result["points"][3]["trueVerticalDepth"])
        self.assertEqual(3, result["profileComparison"]["matchedPointCount"])
        self.assertGreater(result["profileComparison"]["maxAbsPressureDelta"], 0)
        self.assertTrue(result["application"]["sourceModelUnchanged"])

    def test_tubing_inner_diameter_edit_persists_reopens_reads_back_and_runs_profile(self):
        state = {"diameter": 2.441, "saves": 0, "closed": []}
        instances = []
        profile_calls = []

        class EditableTubingModel(FakeModel):
            def __init__(self):
                super().__init__()
                self.tasks.ptprofilesimulation = Runnable(lambda kwargs: (
                    profile_calls.append(kwargs),
                    types.SimpleNamespace(profile={"Well_1": [
                        {"Depth": 0.0, "Pressure": 1500.0, "Temperature": 80.0},
                        {"Depth": 1000.0, "Pressure": 1200.0, "Temperature": 100.0},
                    ]}),
                )[1])

            def get_value(self, component=None, parameter=None, context=None):
                if parameter == "inner_diameter":
                    return state["diameter"]
                if parameter == "associated_fluid":
                    return "Fluid_1"
                return super().get_value(component, parameter)

            def describe(self, context=None, parameter=None):
                if parameter == "inner_diameter":
                    return types.SimpleNamespace(units_symbol="in")
                return types.SimpleNamespace(units_symbol=None)

            def set_value(self, context=None, parameter=None, value=None):
                if context != "Tubing_1" or parameter != "inner_diameter":
                    raise AssertionError("Unexpected PIPESIM component write")
                state["diameter"] = value

            def save(self):
                state["saves"] += 1

            def close(self):
                self.closed = True
                state["closed"].append(True)

        def open_model(_path):
            model = EditableTubingModel()
            instances.append(model)
            return model

        parameters = {
            "schemaVersion": "pipesim-well-tubing-diameter-parameters/1",
            "baselineRunId": 7401,
            "wellContext": "Well_1",
            "tubingContext": "Tubing_1",
            "originalInnerDiameter": 2.441,
            "unit": "in",
            "targetInnerDiameter": 2.3,
        }
        with tempfile.NamedTemporaryFile(suffix=".pips") as model_file:
            model_file.seek(0)
            source_bytes = model_file.read()
            with patch.dict(sys.modules, fake_sixgill_modules()):
                envelope = ptk_run.execute_request(
                    {"modelPath": model_file.name, "study": "Study 1", "runTask": "profile", "parameters": parameters},
                    model_factory=open_model,
                )
            model_file.seek(0)
            self.assertEqual(source_bytes, model_file.read())

        self.assertEqual("ok", envelope["status"])
        self.assertEqual("pipesim-well-result/2", envelope["result"]["schemaVersion"])
        self.assertEqual(2.3, state["diameter"])
        self.assertEqual(1, state["saves"])
        self.assertEqual(2, len(instances))
        self.assertEqual([True, True], state["closed"])
        self.assertEqual("Study 1", profile_calls[0]["study"])
        self.assertEqual("Well_1", profile_calls[0]["producer"])
        self.assertEqual(2, len(envelope["result"]["profile"]))
        self.assertEqual("pipesim-well-tubing-applied.pips", envelope["result"]["application"]["artifactName"])

        state["diameter"] = 2.5
        with tempfile.NamedTemporaryFile(suffix=".pips") as model_file:
            with patch.dict(sys.modules, fake_sixgill_modules()):
                stale_envelope = ptk_run.execute_request(
                    {"modelPath": model_file.name, "study": "Study 1", "runTask": "profile", "parameters": parameters},
                    model_factory=open_model,
                )
        self.assertEqual("error", stale_envelope["status"])
        self.assertEqual("TUBING_DIAMETER_ORIGINAL_VALUE_MISMATCH", stale_envelope["error"]["code"])
        self.assertEqual(1, state["saves"])
        self.assertEqual(1, len(profile_calls))

    def test_tubing_inner_diameter_edit_rejects_stale_or_unchanged_values(self):
        base = {
            "schemaVersion": "pipesim-well-tubing-diameter-parameters/1",
            "baselineRunId": 7401,
            "wellContext": "Well_1",
            "tubingContext": "Tubing_1",
            "originalInnerDiameter": 2.441,
            "unit": "in",
            "targetInnerDiameter": 2.3,
        }
        for updated in (
                {**base, "targetInnerDiameter": 2.441},
                {**base, "tubingContext": "../Tubing"},
                {**base, "unit": ""}):
            with self.subTest(parameters=updated), self.assertRaises(ptk_run.AdapterFailure):
                ptk_run._well_tubing_diameter_parameters(updated)

    def test_pressure_scenario_task_matrix_and_profile_boundary(self):
        for gas in (False, True):
            for task in ('nodal', 'profile', 'combined'):
                for scenario in (None, {'schemaVersion': 'pipesim-well-parameters/1', 'reservoirPressurePsi': 4000}):
                    with self.subTest(gas=gas, task=task, scenario=scenario):
                        model = FakeModel(gas=gas)
                        base_get = model.get_value
                        pressure = [3000]
                        writes, calls = [], []
                        model.describe = lambda **kwargs: types.SimpleNamespace(units_symbol='psia')
                        model.get_value = lambda component, parameter=None: pressure[0] if parameter == 'reservoir_pressure' else base_get(component, parameter)
                        def write(component, parameter=None, value=None):
                            writes.append(value)
                            pressure[0] = value
                        model.set_value = write
                        model.tasks.nodalanalysis = Runnable(lambda kwargs: (calls.append(('nodal', pressure[0], kwargs)), NodalResult(gas))[1])
                        model.tasks.ptprofilesimulation = Runnable(lambda kwargs: (calls.append(('profile', pressure[0], kwargs)), ProfileResult())[1])
                        envelope, _, _ = self.execute(task, model, scenario)
                        self.assertTrue(model.closed)
                        if scenario is not None and not gas and task != 'nodal':
                            self.assertEqual('INVALID_SCENARIO_PARAMETERS', envelope['error']['code'])
                            self.assertEqual([], writes)
                            self.assertEqual([], calls)
                            continue
                        self.assertEqual('ok', envelope['status'])
                        self.assertEqual([] if scenario is None else [4000], writes)
                        for phase, applied, kwargs in calls:
                            expected = {'producer': 'Well_1', 'study': 'Study 1'}
                            expected.update({
                                'system_variables': ['pressure', 'liquid_flow', 'gas_flow'],
                                'profile_variables': ['temperature', 'pressure', 'elevation', 'distance'],
                            })
                            if gas and phase == 'profile':
                                expected['parameters'] = {'calculated_variable': 'flowrate', 'flow_rate_type': 'gasflowrate'}
                                if scenario is not None:
                                    expected['parameters']['inlet_pressure'] = 4000
                            self.assertEqual(expected, kwargs)
                            self.assertEqual(3000 if scenario is None else 4000, applied)

    def test_gas_scenario_profile_failure_preserves_partial_contract(self):
        for task in ('profile', 'combined'):
            model = FakeModel(gas=True, profile_failure=RuntimeError('profile solver failed'))
            base_get = model.get_value
            pressure = [3000]
            model.describe = lambda **kwargs: types.SimpleNamespace(units_symbol='psia')
            model.get_value = lambda component, parameter=None: pressure[0] if parameter == 'reservoir_pressure' else base_get(component, parameter)
            model.set_value = lambda component, parameter=None, value=None: pressure.__setitem__(0, value)
            envelope, _, _ = self.execute(task, model, {'schemaVersion': 'pipesim-well-parameters/1', 'reservoirPressurePsi': 4000})
            self.assertTrue(model.closed)
            if task == 'profile':
                self.assertEqual('error', envelope['status'])
                self.assertEqual('PROFILE_RUN_FAILED', envelope['error']['code'])
            else:
                self.assertEqual('partial', envelope['status'])
                self.assertEqual('VALID_PARTIAL', envelope['result']['resultContract'])
                self.assertTrue(envelope['result']['ipr'])
                self.assertTrue(envelope['result']['vlp'])
                self.assertEqual([], envelope['result']['profile'])
                self.assertEqual('PROFILE_RUN_FAILED', envelope['warnings'][0]['code'])

    def test_scenario_is_applied_before_nodal_and_failure_closes_without_running(self):
        for unit in ('psia', 'bara'):
            model = FakeModel()
            base_get = model.get_value
            pressure = [3000]
            calls = []
            model.describe = lambda **kwargs: types.SimpleNamespace(units_symbol=unit)
            model.get_value = lambda component, parameter=None: pressure[0] if parameter == 'reservoir_pressure' else base_get(component, parameter)
            model.set_value = lambda component, parameter=None, value=None: pressure.__setitem__(0, value)
            model.tasks.nodalanalysis = Runnable(lambda _: (calls.append(pressure[0]), NodalResult())[1])
            envelope, _, _ = self.execute('nodal', model, {'schemaVersion': 'pipesim-well-parameters/1', 'reservoirPressurePsi': 4000})
            self.assertTrue(model.closed)
            self.assertEqual([4000] if unit == 'psia' else [], calls)
            self.assertEqual('ok' if unit == 'psia' else 'error', envelope['status'])

    def test_sensitivity_runs_real_nodal_cases_and_restores_isolated_model(self):
        model = FakeModel()
        state = {"reservoir_pressure": 3000}
        calls = []
        run_kwargs = []
        base_get = model.get_value
        model.get_value = lambda component, parameter=None: state[parameter] if parameter in state else base_get(component, parameter)
        model.set_value = lambda component, parameter=None, value=None: state.__setitem__(parameter, value)
        model.tasks.nodalanalysis = Runnable(
            lambda kwargs: (run_kwargs.append(kwargs), calls.append(state["reservoir_pressure"]), NodalResult())[2]
        )
        model.tasks.nodalanalysis.get_conditions = lambda **_: (
            {"OutletPressure": 1100.0},
            {"Completion_1": {"Pressure": 3000.0}},
        )
        envelope, events, _ = self.execute(
            "sensitivity",
            model,
            {"schemaVersion": "pipesim-well-sensitivity-parameters/1", "targetVariable": "reservoirPressure", "values": [3000, 4000, 5000]},
        )
        self.assertEqual("ok", envelope["status"])
        self.assertEqual("pipesim-well-sensitivity-result/1", envelope["result"]["schemaVersion"])
        self.assertEqual([3000, 4000, 5000], calls)
        self.assertEqual(3000, state["reservoir_pressure"])
        self.assertEqual([1100.0, 1100.0, 1100.0], [item["parameters"]["outlet_pressure"] for item in run_kwargs])
        self.assertEqual([3000, 4000, 5000], [item["inlet_conditions"]["Completion_1"]["Pressure"] for item in run_kwargs])
        self.assertEqual(["RUNNING_NODAL", "COLLECTING"], [item[0] for item in events])
        self.assertEqual(3, len(envelope["result"]["cases"]))

    def execute(self, run_task, model=None, parameters=None, artifact_directory=None):
        events = []
        model = model or FakeModel()
        with tempfile.NamedTemporaryFile(suffix=".pips") as model_file:
            with patch.dict(sys.modules, fake_sixgill_modules()):
                envelope = ptk_run.execute_request(
                    {
                        "modelPath": model_file.name,
                        "study": "Study 1",
                        "runTask": run_task,
                        "parameters": parameters,
                        "artifactDirectory": artifact_directory,
                    },
                    model_factory=lambda _: model,
                    emit_event=lambda state, message: events.append((state, message)),
                )
        return envelope, events, model

    def test_rejects_non_null_parameters_without_opening_model(self):
        opened = []
        with tempfile.NamedTemporaryFile(suffix=".pips") as model_file:
            envelope = ptk_run.execute_request(
                {
                    "modelPath": model_file.name,
                    "study": "Study 1",
                    "runTask": "nodal",
                    "parameters": {},
                },
                model_factory=lambda _: opened.append(True),
            )
        self.assertEqual("error", envelope["status"])
        self.assertEqual("INVALID_SCENARIO_PARAMETERS", envelope["error"]["code"])
        self.assertEqual([], opened)

    def test_nodal_contract_and_event_order(self):
        envelope, events, model = self.execute("nodal")
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(["RUNNING_NODAL", "COLLECTING"], [item[0] for item in events])
        self.assertEqual("VALID_FULL", envelope["result"]["resultContract"])
        self.assertEqual([], envelope["result"]["profile"])
        self.assertIsNone(envelope["result"]["units"]["flow"]["displayUnit"])
        self.assertTrue(model.closed)
        self.assertEqual([{"producer": "Well_1", "study": "Study 1"}], model.tasks.nodalanalysis.validation_calls)

    def test_official_preflight_issues_are_returned_and_block_solver(self):
        model = FakeModel()
        model.tasks.nodalanalysis.validation_issues = [types.SimpleNamespace(
            path="Well_1", property_name="OutletPressure", message="Pressure is outside the task limits.")]
        run_calls = []
        model.tasks.nodalanalysis.run = lambda **kwargs: run_calls.append(kwargs)

        envelope, events, _ = self.execute("nodal", model)

        self.assertEqual("error", envelope["status"])
        self.assertEqual("PIPESIM_TASK_VALIDATION_FAILED", envelope["error"]["code"])
        self.assertEqual([{"path": "Well_1", "property": "OutletPressure",
                           "message": "Pressure is outside the task limits."}], envelope["error"]["diagnostics"])
        self.assertEqual([], run_calls)
        self.assertEqual([], events)

    def test_preflight_diagnostics_redact_machine_paths_and_credentials(self):
        diagnostics = ptk_run._validation_diagnostics([{
            "path": r"C:\Users\Demo\model.pips",
            "property_name": "Pressure",
            "message": r"Failed at C:\Users\Demo\model.pips token=abc123",
        }])
        self.assertEqual("[local path]", diagnostics[0]["path"])
        self.assertEqual("Failed at [local path] token=[redacted]", diagnostics[0]["message"])

    def test_profile_preflight_uses_same_well_study_and_outlet_pressure(self):
        model = FakeModel()
        envelope, _, _ = self.execute("profile", model,
                                      {"schemaVersion": "pipesim-well-profile-parameters/1", "outletPressurePsi": 250})
        self.assertEqual("ok", envelope["status"])
        self.assertEqual([{"producer": "Well_1", "study": "Study 1", "parameters": {"outlet_pressure": 250}}],
                         model.tasks.ptprofilesimulation.condition_calls)
        self.assertEqual([{"producer": "Well_1", "study": "Study 1"}],
                         model.tasks.ptprofilesimulation.validation_calls)

    def test_trajectory_profile_comparison_skips_ambiguous_duplicate_depths(self):
        baseline = [
            {"depth": 0.0, "pressure": 100.0, "temperature": 60.0},
            {"depth": 100.0, "pressure": 200.0, "temperature": 70.0},
            {"depth": 100.0, "pressure": 201.0, "temperature": 71.0},
            {"depth": 200.0, "pressure": 300.0, "temperature": 80.0},
        ]
        target = [
            {"depth": 0.0, "pressure": 101.0, "temperature": 61.0},
            {"depth": 100.0, "pressure": 202.0, "temperature": 72.0},
            {"depth": 200.0, "pressure": 305.0, "temperature": 82.0},
        ]

        comparison = ptk_run._trajectory_profile_comparison(baseline, target)

        self.assertEqual(4, comparison["baselinePointCount"])
        self.assertEqual(3, comparison["targetPointCount"])
        self.assertEqual([0.0, 200.0], [point["depth"] for point in comparison["matchedPoints"]])
        self.assertEqual(5.0, comparison["maxAbsPressureDelta"])
        self.assertEqual(2.0, comparison["maxAbsTemperatureDelta"])

    def test_profile_contract_and_event_order(self):
        envelope, events, _ = self.execute("profile")
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(["RUNNING_PROFILE", "COLLECTING"], [item[0] for item in events])
        self.assertEqual([], envelope["result"]["ipr"])
        self.assertEqual(10.0, envelope["result"]["profile"][0]["depth"])

    def test_basic_gas_uses_frozen_standard_gas_flow_unit(self):
        envelope, _, _ = self.execute("nodal", FakeModel(gas=True))
        self.assertEqual("ok", envelope["status"])
        self.assertEqual("basic_gas", envelope["result"]["model_kind"])
        self.assertEqual("mmscf/d", envelope["result"]["units"]["flow"]["displayUnit"])
        self.assertEqual(
            "standard_gas_volume_rate",
            envelope["result"]["units"]["flow"]["semantics"],
        )

    def test_combined_contract_and_phase_order(self):
        envelope, events, _ = self.execute("combined")
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(
            ["RUNNING_NODAL", "RUNNING_PROFILE", "COLLECTING"],
            [item[0] for item in events],
        )
        self.assertEqual("VALID_FULL", envelope["result"]["resultContract"])

    def test_combined_profile_failure_preserves_nodal_partial(self):
        envelope, events, _ = self.execute(
            "combined", FakeModel(profile_failure=RuntimeError("profile solver failed"))
        )
        self.assertEqual("partial", envelope["status"])
        self.assertEqual("VALID_PARTIAL", envelope["result"]["resultContract"])
        self.assertTrue(envelope["result"]["ipr"])
        self.assertTrue(envelope["result"]["vlp"])
        self.assertEqual([], envelope["result"]["profile"])
        self.assertEqual("PROFILE_RUN_FAILED", envelope["warnings"][0]["code"])
        self.assertEqual("COLLECTING", events[-1][0])

    def test_profile_failure_and_license_failure_are_structured(self):
        profile, _, _ = self.execute("profile", FakeModel(profile_failure=RuntimeError("failed")))
        licensed, _, _ = self.execute("nodal", FakeModel(nodal_failure=RuntimeError("license checkout failed")))
        self.assertEqual("PROFILE_RUN_FAILED", profile["error"]["code"])
        self.assertEqual("LICENSE", licensed["error"]["category"])
        self.assertEqual("LICENSE_UNAVAILABLE", licensed["error"]["code"])
        self.assertTrue(licensed["error"]["retryable"])

    def test_requires_an_existing_study(self):
        envelope, _, _ = self.execute("nodal", FakeModel(studies=["Other Study"]))
        self.assertEqual("STUDY_NOT_FOUND", envelope["error"]["code"])


class NetworkConnectionScenarioTests(unittest.TestCase):
    PARAMETERS = {
        "schemaVersion": "pipesim-network-connection-parameters/1",
        "baselineRunId": 41,
        "source": "Flowline-1",
        "sourcePort": "",
        "originalDestination": "Junction-1",
        "newDestination": "Junction-2",
    }

    class Model:
        def __init__(self, state):
            self.state = state
            self.closed = False
            self.save_count = 0
            self.connect_calls = []
            self.disconnect_calls = []

        def find(self, component, **_):
            return list(self.state["components"].get(component, []))

        def connections(self):
            return copy.deepcopy(self.state["edges"])

        def disconnect(self, source, destination=None, port=None):
            source_name = next(iter(source.values()))
            destination_name = next(iter(destination.values())) if destination is not None else None
            self.disconnect_calls.append((source_name, destination_name, port))
            matches = [edge for edge in self.state["edges"]
                       if edge["Source"] == source_name and
                       (edge["Destination"] == destination_name if destination_name is not None else edge["Source Port"] == port)]
            if len(matches) != 1:
                raise ValueError("connection is missing or ambiguous")
            self.state["edges"].remove(matches[0])

        def connect(self, source, destination, source_port=None):
            source_name = next(iter(source.values()))
            destination_name = next(iter(destination.values()))
            self.connect_calls.append((source_name, destination_name, source_port))
            if any(edge["Source"] == source_name and edge["Destination"] == destination_name
                   for edge in self.state["edges"]):
                raise ValueError("duplicate connection")
            self.state["edges"].append({
                "Source": source_name,
                "Destination": destination_name,
                "Source Port": source_port or "",
            })

        def save(self):
            self.save_count += 1

        def close(self):
            self.closed = True

    def make_model_factory(self):
        state = {
            "components": {
                "Source": ["Source-1"], "Flowline": ["Flowline-1"], "Junction": ["Junction-1", "Junction-2"],
                "Sink": ["Sink-1"],
            },
            "edges": [
                {"Source": "Source-1", "Destination": "Flowline-1", "Source Port": ""},
                {"Source": "Flowline-1", "Destination": "Junction-1", "Source Port": ""},
                {"Source": "Junction-1", "Destination": "Sink-1", "Source Port": ""},
                {"Source": "Source-1", "Destination": "Junction-2", "Source Port": ""},
                {"Source": "Junction-2", "Destination": "Sink-1", "Source Port": ""},
            ],
        }
        created = []

        def factory(path):
            self.assertEqual("derived.pips", path)
            model = self.Model(state)
            created.append(model)
            return model

        return factory, state, created

    def test_reconnect_apply_failure_reports_phase_and_redacts_local_details(self):
        factory, state, created = self.make_model_factory()
        definitions = types.ModuleType("sixgill.definitions")
        definitions.ModelComponents = types.SimpleNamespace(
            SOURCE="Source", FLOWLINE="Flowline", JUNCTION="Junction", SINK="Sink")
        definitions.Connection = types.SimpleNamespace(Separator=types.SimpleNamespace(TOP="Top Port", BOTTOM="Bottom Port"))
        sixgill = types.ModuleType("sixgill")
        sixgill.__path__ = []
        original_model = self.Model(state)

        def fail_connect(*_args, **_kwargs):
            raise TypeError(r"invalid context at C:\private\model.pips token=secret-value")

        original_model.connect = fail_connect
        with patch.dict(sys.modules, {"sixgill": sixgill, "sixgill.definitions": definitions}), \
                patch.object(ptk_run, "_study_names", return_value=["Study 1"]):
            with self.assertRaises(ptk_run.AdapterFailure) as raised:
                ptk_run._execute_network_connection(
                    original_model, factory, "derived.pips", "Study 1", self.PARAMETERS, lambda *_: None)

        self.assertEqual("NETWORK_CONNECTION_APPLY_FAILED", raised.exception.code)
        self.assertEqual("connect", raised.exception.diagnostics[0]["property"])
        self.assertIn("TypeError", raised.exception.diagnostics[0]["message"])
        self.assertIn("[local path]", raised.exception.diagnostics[0]["message"])
        self.assertIn("token=[redacted]", raised.exception.diagnostics[0]["message"])
        self.assertNotIn("C:\\private", raised.exception.diagnostics[0]["message"])

    def test_redirects_one_inspected_edge_saves_reopens_and_runs_network(self):
        factory, state, created = self.make_model_factory()
        definitions = types.ModuleType("sixgill.definitions")
        definitions.ModelComponents = types.SimpleNamespace(
            SOURCE="Source", FLOWLINE="Flowline", JUNCTION="Junction", SINK="Sink")
        definitions.Connection = types.SimpleNamespace(Separator=types.SimpleNamespace(TOP="Top Port", BOTTOM="Bottom Port"))
        sixgill = types.ModuleType("sixgill")
        sixgill.__path__ = []
        result_payload = {"resultContract": "VALID_FULL", "messages": []}
        original_model = self.Model(state)
        with patch.dict(sys.modules, {"sixgill": sixgill, "sixgill.definitions": definitions}), \
                patch.object(ptk_run, "_study_names", return_value=["Study 1"]), \
                patch.object(ptk_run, "_execute_network", return_value=(result_payload, [])) as network_run:
            result, warnings, reopened = ptk_run._execute_network_connection(
                original_model, factory, "derived.pips", "Study 1", self.PARAMETERS, lambda *_: None)

        self.assertEqual("VALID_FULL", result["resultContract"])
        self.assertEqual([], warnings)
        self.assertTrue(original_model.closed)
        self.assertEqual(1, original_model.save_count)
        self.assertEqual([("Flowline-1", "Junction-1", None)], original_model.disconnect_calls)
        self.assertEqual([("Flowline-1", "Junction-2", None)], original_model.connect_calls)
        self.assertEqual(1, network_run.call_count)
        self.assertIs(reopened, created[0])
        self.assertIn(("Flowline-1", "", "Junction-2"), [
            (edge["Source"], edge["Source Port"], edge["Destination"]) for edge in state["edges"]])
        self.assertEqual(5, len(state["edges"]))
        self.assertTrue(any("reopened and recalculated Study Study 1" in message for message in result["messages"]))

    def test_rejects_reconnect_to_choke_with_occupied_inlet_before_mutating_model(self):
        factory, state, created = self.make_model_factory()
        state["components"]["Choke"] = ["Choke-1"]
        state["edges"].append({"Source": "Junction-2", "Destination": "Choke-1", "Source Port": ""})
        parameters = {**self.PARAMETERS, "newDestination": "Choke-1"}
        definitions = types.ModuleType("sixgill.definitions")
        definitions.ModelComponents = types.SimpleNamespace(
            SOURCE="Source", FLOWLINE="Flowline", JUNCTION="Junction", SINK="Sink", CHOKE="Choke")
        definitions.Connection = types.SimpleNamespace(Separator=types.SimpleNamespace(TOP="Top Port", BOTTOM="Bottom Port"))
        sixgill = types.ModuleType("sixgill")
        sixgill.__path__ = []
        original_model = self.Model(state)
        with patch.dict(sys.modules, {"sixgill": sixgill, "sixgill.definitions": definitions}), \
                patch.object(ptk_run, "_study_names", return_value=["Study 1"]):
            with self.assertRaises(ptk_run.AdapterFailure) as failure:
                ptk_run._execute_network_connection(
                    original_model, factory, "derived.pips", "Study 1", parameters, lambda *_: None)

        self.assertEqual("NETWORK_CONNECTION_TARGET_INLET_OCCUPIED", failure.exception.code)
        self.assertEqual([], original_model.disconnect_calls)
        self.assertEqual([], original_model.connect_calls)
        self.assertEqual(0, original_model.save_count)
        self.assertEqual([], created)

    def test_rejects_reconnect_that_would_leave_original_flowline_inlet_open(self):
        factory, state, created = self.make_model_factory()
        parameters = {
            **self.PARAMETERS, "source": "Source-1", "originalDestination": "Flowline-1",
            "newDestination": "Junction-2",
        }
        definitions = types.ModuleType("sixgill.definitions")
        definitions.ModelComponents = types.SimpleNamespace(
            SOURCE="Source", FLOWLINE="Flowline", JUNCTION="Junction", SINK="Sink")
        definitions.Connection = types.SimpleNamespace(Separator=types.SimpleNamespace(TOP="Top Port", BOTTOM="Bottom Port"))
        sixgill = types.ModuleType("sixgill")
        sixgill.__path__ = []
        original_model = self.Model(state)
        with patch.dict(sys.modules, {"sixgill": sixgill, "sixgill.definitions": definitions}), \
                patch.object(ptk_run, "_study_names", return_value=["Study 1"]):
            with self.assertRaises(ptk_run.AdapterFailure) as failure:
                ptk_run._execute_network_connection(
                    original_model, factory, "derived.pips", "Study 1", parameters, lambda *_: None)

        self.assertEqual("NETWORK_CONNECTION_FLOWLINE_INLET_REQUIRED", failure.exception.code)
        self.assertEqual([], original_model.disconnect_calls)
        self.assertEqual([], original_model.connect_calls)
        self.assertEqual(0, original_model.save_count)
        self.assertEqual([], created)

    def test_separator_reconnect_disconnects_only_the_selected_official_port(self):
        factory, state, created = self.make_model_factory()
        state["components"] = {
            "Well": ["Well-1"], "TwoPhaseSeparator": ["Separator-1"],
            "Junction": ["Junction-1", "Junction-2"], "Sink": ["Sink-1"],
        }
        state["edges"] = [
            {"Source": "Well-1", "Destination": "Separator-1", "Source Port": ""},
            {"Source": "Separator-1", "Destination": "Junction-1", "Source Port": "Top Port"},
            {"Source": "Separator-1", "Destination": "Sink-1", "Source Port": "Bottom Port"},
            {"Source": "Well-1", "Destination": "Junction-2", "Source Port": ""},
            {"Source": "Junction-2", "Destination": "Sink-1", "Source Port": ""},
        ]
        parameters = {
            **self.PARAMETERS, "source": "Separator-1", "sourcePort": "Top Port",
            "originalDestination": "Junction-1", "newDestination": "Junction-2",
        }
        definitions = types.ModuleType("sixgill.definitions")
        definitions.ModelComponents = types.SimpleNamespace(
            WELL="Well", TWOPHASESEPARATOR="TwoPhaseSeparator", JUNCTION="Junction", SINK="Sink")
        definitions.Connection = types.SimpleNamespace(Separator=types.SimpleNamespace(TOP="Top Port", BOTTOM="Bottom Port"))
        sixgill = types.ModuleType("sixgill")
        sixgill.__path__ = []
        original_model = self.Model(state)
        with patch.dict(sys.modules, {"sixgill": sixgill, "sixgill.definitions": definitions}), \
                patch.object(ptk_run, "_study_names", return_value=["Study 1"]), \
                patch.object(ptk_run, "_execute_network", return_value=({"resultContract": "VALID_FULL", "messages": []}, [])):
            result, warnings, reopened = ptk_run._execute_network_connection(
                original_model, factory, "derived.pips", "Study 1", parameters, lambda *_: None)

        self.assertEqual("VALID_FULL", result["resultContract"])
        self.assertEqual([], warnings)
        self.assertTrue(original_model.closed)
        self.assertEqual([("Separator-1", None, "Top Port")], original_model.disconnect_calls)
        self.assertEqual([("Separator-1", "Junction-2", "Top Port")], original_model.connect_calls)
        self.assertIs(reopened, created[0])
        self.assertIn({"Source": "Separator-1", "Destination": "Sink-1", "Source Port": "Bottom Port"}, state["edges"])
        self.assertIn({"Source": "Separator-1", "Destination": "Junction-2", "Source Port": "Top Port"}, state["edges"])

    def test_redirect_that_disconnects_previously_reachable_sink_is_rejected(self):
        factory, state, _ = self.make_model_factory()
        state["edges"] = [
            {"Source": "Source-1", "Destination": "Flowline-1", "Source Port": ""},
            {"Source": "Flowline-1", "Destination": "Junction-1", "Source Port": ""},
            {"Source": "Junction-1", "Destination": "Sink-1", "Source Port": ""},
        ]
        parameters = {**self.PARAMETERS, "newDestination": "Junction-2"}
        definitions = types.ModuleType("sixgill.definitions")
        definitions.ModelComponents = types.SimpleNamespace(
            SOURCE="Source", FLOWLINE="Flowline", JUNCTION="Junction", SINK="Sink")
        definitions.Connection = types.SimpleNamespace(Separator=types.SimpleNamespace(TOP="Top Port", BOTTOM="Bottom Port"))
        sixgill = types.ModuleType("sixgill")
        sixgill.__path__ = []
        with patch.dict(sys.modules, {"sixgill": sixgill, "sixgill.definitions": definitions}), \
                patch.object(ptk_run, "_study_names", return_value=["Study 1"]), \
                patch.object(ptk_run, "_execute_network", return_value=({"resultContract": "VALID_FULL", "messages": []}, [])):
            with self.assertRaises(ptk_run.AdapterFailure) as failure:
                ptk_run._execute_network_connection(self.Model(state), factory, "derived.pips", "Study 1", parameters, lambda *_: None)
        self.assertEqual("NETWORK_CONNECTION_DISCONNECTS_SINK", failure.exception.code)

    def test_contract_rejects_same_or_invalid_connection_endpoints(self):
        self.assertIsNone(ptk_run.validate_scenario(self.PARAMETERS, "network"))
        for changes in ({"newDestination": "Junction-1"}, {"newDestination": "Flowline-1"}, {"sourcePort": "unknown"}):
            with self.subTest(changes=changes), self.assertRaises(ptk_run.AdapterFailure):
                ptk_run.validate_scenario({**self.PARAMETERS, **changes}, "network")


class GoldenComparatorTests(unittest.TestCase):
    def run_comparator(self, actual):
        with tempfile.TemporaryDirectory() as temporary:
            actual_path = Path(temporary) / "actual.json"
            actual_path.write_text(json.dumps(actual), encoding="utf-8")
            return subprocess.run(
                [
                    "pwsh",
                    "-NoProfile",
                    "-File",
                    str(COMPARATOR),
                    "-ActualResult",
                    str(actual_path),
                    "-GoldenResult",
                    str(GOLDEN),
                ],
                capture_output=True,
                text=True,
                errors="replace",
                check=False,
            )

    def test_comparator_accepts_exact_result(self):
        actual = json.loads(GOLDEN.read_text(encoding="utf-8"))
        completed = self.run_comparator(actual)
        self.assertEqual(0, completed.returncode, completed.stderr)

    def test_comparator_rejects_value_and_order_changes(self):
        actual = json.loads(GOLDEN.read_text(encoding="utf-8"))
        changed_value = copy.deepcopy(actual)
        changed_value["ipr"][0]["flow"] += 0.000001
        self.assertNotEqual(0, self.run_comparator(changed_value).returncode)
        changed_order = copy.deepcopy(actual)
        changed_order["vlp"][0], changed_order["vlp"][1] = changed_order["vlp"][1], changed_order["vlp"][0]
        self.assertNotEqual(0, self.run_comparator(changed_order).returncode)

    def test_comparator_rejects_schema_category_units_and_length_changes(self):
        actual = json.loads(GOLDEN.read_text(encoding="utf-8"))
        changed_schema = copy.deepcopy(actual)
        changed_schema["schemaVersion"] = "pipesim-well-result/other"
        self.assertNotEqual(0, self.run_comparator(changed_schema).returncode)
        changed_category = copy.deepcopy(actual)
        changed_category["model_kind"] = "basic_gas"
        self.assertNotEqual(0, self.run_comparator(changed_category).returncode)
        changed_units = copy.deepcopy(actual)
        changed_units["units"]["flow"] = {
            "displayUnit": "mmscf/d",
            "semantics": "standard_gas_volume_rate",
        }
        self.assertNotEqual(0, self.run_comparator(changed_units).returncode)
        changed_length = copy.deepcopy(actual)
        changed_length["ipr"].pop()
        self.assertNotEqual(0, self.run_comparator(changed_length).returncode)


if __name__ == "__main__":
    unittest.main()
