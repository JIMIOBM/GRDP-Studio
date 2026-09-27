import json
import math
import os
import sys

_PROTOCOL_OUTPUT = sys.stdout
SCRIPT_DIRECTORY = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIRECTORY not in sys.path:
    sys.path.insert(0, SCRIPT_DIRECTORY)

ptk_path = os.environ.get("PIPESIM_PTK_PATH")
if ptk_path:
    sys.path.insert(0, ptk_path)

from ptk_network import format_validation_issues, inspect_network  # noqa: E402
from ptk_well_inspection import inspect_well_pressure  # noqa: E402


def _network_number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        return None
    if abs(value - 1.2345e25) <= 1.2345e25 * 1e-12 or value == -1e31:
        return None
    return value


def _network_inspection(model, studies):
    """Return display-safe Study boundary metadata without exposing model paths."""
    inspections = []
    chokes = []
    try:
        from sixgill.definitions import Parameters

        for name in model.find(component="Choke"):
            try:
                bean_size = _network_number(model.get_value(context=name, parameter=Parameters.Choke.BEANSIZE))
                description = model.describe(context=name, parameter=Parameters.Choke.BEANSIZE)
                unit = str(getattr(description, "units_symbol", "") or "")
            except Exception:
                continue
            if isinstance(name, str) and name.strip() and len(name) <= 255 and bean_size is not None and bean_size > 0 and bean_size <= 1000000 and unit and len(unit) <= 32:
                chokes.append({"name": name, "beanSize": bean_size, "unit": unit})
        for study in studies:
            conditions = model.tasks.networksimulation.get_conditions(study=study)
            if not isinstance(conditions, dict):
                return None
            boundaries = []
            for node, condition in conditions.items():
                if not isinstance(node, str) or not node.strip() or len(node) > 255:
                    return None
                if not isinstance(condition, dict):
                    return None
                boundary_type = condition.get("BoundaryNodeType")
                if not isinstance(boundary_type, str) or not boundary_type.strip() or len(boundary_type) > 64:
                    return None
                flow_rate_type = condition.get("FlowRateType")
                if flow_rate_type is not None and flow_rate_type not in ("GasFlowRate", "LiquidFlowRate", "MassFlowRate"):
                    flow_rate_type = None
                boundaries.append({
                    "node": node,
                    "boundaryNodeType": boundary_type,
                    "isActive": condition.get("IsActive") is True,
                    "isSurfaceCondition": condition.get("IsSurfaceCondition") is True,
                    "flowRateType": flow_rate_type,
                    "pressure": _network_number(condition.get("Pressure")),
                    "temperature": _network_number(condition.get("Temperature")),
                    "gasFlowRate": _network_number(condition.get("GasFlowRate")),
                    "liquidFlowRate": _network_number(condition.get("LiquidFlowRate")),
                    "massFlowRate": _network_number(condition.get("MassFlowRate")),
                })
            if not boundaries:
                return None
            inspections.append({"study": study, "boundaries": sorted(boundaries, key=lambda item: item["node"])})
    except Exception:
        return None
    return {"schemaVersion": "pipesim-network-inspection/3", "studies": inspections,
            "chokes": sorted(chokes, key=lambda item: item["name"])}


def result(status, message, model_kind=None, well=None, studies=None, error=None, inspection=None):
    print(json.dumps({
        "status": status,
        "message": message,
        "modelKind": model_kind,
        "well": well,
        "studies": studies or [],
        "error": error,
        "inspection": inspection
    }, ensure_ascii=False), file=_PROTOCOL_OUTPUT, flush=True)


def validate(path):
    if not path or not os.path.isfile(path):
        return result("INVALID", "模型文件不存在或不可读")
    model = None
    try:
        from sixgill.pipesim import Model
        from sixgill.core.resources import ModelClasses
        from sixgill.definitions import Constants, Parameters

        model = Model.open(path)
        studies = [study.name for study in model._catalog.lookup_entries_by_class_ids([ModelClasses.STUDY])]
        if not studies:
            return result("INVALID", "模型中未找到可运行的研究方案")

        network = inspect_network(model)
        if network["candidate"]:
            if not network["valid"]:
                return result(
                    "INVALID",
                    "PIPESIM Network 模型缺少必要管网组件：{0}".format(", ".join(network["missing"])),
                    "network",
                )
            model_issues = model.validate()
            if model_issues:
                return result(
                    "INVALID",
                    "PIPESIM Network 模型验证失败：{0}".format(format_validation_issues(model_issues)),
                    "network",
                )
            runnable_studies = []
            rejected_issues = []
            for study in studies:
                issues = model.tasks.networksimulation.validate(study=study, validate_model=False)
                if issues:
                    rejected_issues.extend(issues)
                else:
                    runnable_studies.append(study)
            if not runnable_studies:
                return result(
                    "INVALID",
                    "Network Simulation 研究方案验证失败：{0}".format(format_validation_issues(rejected_issues)),
                    "network",
                )
            counts = network["components"]
            message = "Network 模型验证完成（上游 {0}、出口 {1}、管线 {2}、连接 {3}）".format(
                len(counts["Source"]) + len(counts["Well"]),
                len(counts["Sink"]),
                len(counts["Flowline"]),
                len(network["connections"]),
            )
            if len(runnable_studies) != len(studies):
                message += "；仅返回通过 Network Simulation 验证的研究方案"
            return result("READY", message, "network", studies=runnable_studies,
                          inspection=_network_inspection(model, runnable_studies))

        components = {kind: list(model.find(component=kind)) for kind in ("Well", "BlackOilFluid", "Completion", "Tubing")}
        if len(components["Well"]) != 1 or not components["Completion"] or not components["Tubing"]:
            return result("INVALID", "PIPESIM 井模型必须包含一口井、至少一个完井和至少一个油管")
        completion = components["Completion"][0]
        fluid_type = model.fluids.fluid_type
        if fluid_type not in (Constants.FluidType.COMPOSITIONAL, Constants.FluidType.BLACKOIL):
            return result("INVALID", "PIPESIM 井模型流体类型不受当前 Toolkit 运行适配器支持")
        model_kind = "legacy_well"
        simple_well = len(components["Completion"]) == 1 and len(components["Tubing"]) == 1
        if simple_well:
            try:
                geometry = model.get_value(completion, parameter=Parameters.Completion.GEOMETRYPROFILETYPE)
            except Exception:
                geometry = None
            if fluid_type == Constants.FluidType.COMPOSITIONAL:
                if str(geometry).lower() == "vertical":
                    model_kind = "basic_gas"
            elif fluid_type == Constants.FluidType.BLACKOIL:
                try:
                    fluid = model.get_value(completion, parameter=Parameters.Well.ASSOCIATEDBLACKOILFLUID)
                except Exception:
                    fluid = None
                if fluid in components["BlackOilFluid"]:
                    model_kind = "black_oil_liquid"
        inspection = inspect_well_pressure(model, completion, Parameters.Completion.RESERVOIRPRESSURE)
        message = "模型验证完成"
        if model_kind == "legacy_well":
            message = "PIPESIM 通用井模型已识别；当前开放 Nodal、PT Profile、Combined、井轨迹，专用任务按结构校验"
        return result("READY", message, model_kind, components["Well"][0], studies, inspection=inspection)
    except (ImportError, ModuleNotFoundError):
        return result("ENVIRONMENT_ERROR", "PIPESIM Python Toolkit 不可用", error={
            "category": "ENVIRONMENT",
            "code": "PTK_UNAVAILABLE",
            "message": "PIPESIM Python Toolkit is unavailable.",
            "retryable": True,
        })
    except Exception as exc:
        if "license" in str(exc).lower():
            return result("ENVIRONMENT_ERROR", "PIPESIM Python Toolkit 许可证不可用", error={
                "category": "LICENSE",
                "code": "LICENSE_UNAVAILABLE",
                "message": "PIPESIM Python Toolkit license is unavailable.",
                "retryable": True,
            })
        return result("INVALID", "PIPESIM 模型打开失败 ({0})".format(type(exc).__name__))
    finally:
        if model is not None:
            try:
                model.close()
            except Exception:
                pass


if __name__ in ("__main__", "<run_path>"):
    sys.stdout = sys.stderr
    if os.environ.get("GRDP_PTK_START_GATED") == "1":
        try:
            start_released = json.loads(sys.stdin.readline()) == {"type": "start"}
        except Exception:
            start_released = False
        if not start_released:
            result("ENVIRONMENT_ERROR", "PIPESIM 验证进程启动门未释放", error={
                "category": "PROTOCOL",
                "code": "START_GATE_FAILED",
                "message": "The validation adapter start gate was not released.",
                "retryable": False,
            })
            sys.exit(2)
    validate(sys.argv[1] if len(sys.argv) > 1 else "")
