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
from ptk_gas_lift import discover_gas_lift  # noqa: E402


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
    well_contexts = []
    flowline_contexts = []
    flowlines = []
    try:
        from sixgill.definitions import Parameters

        for component, target in (("Well", well_contexts), ("Flowline", flowline_contexts)):
            try:
                discovered_contexts = model.find(component=component)
            except Exception:
                discovered_contexts = []
            for name in discovered_contexts:
                if (isinstance(name, str) and name.strip() and len(name) <= 255
                        and ".." not in name and "/" not in name and "\\" not in name
                        and not any(ord(character) < 32 or ord(character) == 127 for character in name)):
                    target.append(name.strip())

        # Keep a typed, unit-bearing inventory for the first persistent
        # component-edit slice. The string-only flowlineContexts list remains
        # for backwards-compatible consumers (e.g. sensitivity curves).
        for name in flowline_contexts:
            try:
                inner_diameter = _network_number(
                    model.get_value(context=name, parameter=Parameters.Flowline.INNERDIAMETER)
                )
                description = model.describe(context=name, parameter=Parameters.Flowline.INNERDIAMETER)
                unit = str(getattr(description, "units_symbol", "") or "").strip()
            except Exception:
                inner_diameter = None
                unit = ""
            if unit and len(unit) > 32:
                unit = ""
            flowlines.append({
                "name": name,
                "innerDiameter": inner_diameter if inner_diameter is not None and 0 < inner_diameter <= 1000 else None,
                "unit": unit,
            })

        try:
            choke_contexts = model.find(component="Choke")
        except Exception:
            choke_contexts = []
        for name in choke_contexts:
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
    return {"schemaVersion": "pipesim-network-inspection/5", "studies": inspections,
            "chokes": sorted(chokes, key=lambda item: item["name"]),
            "wellContexts": sorted(set(well_contexts)),
            "flowlineContexts": sorted(set(flowline_contexts)),
            "flowlines": sorted(flowlines, key=lambda item: item["name"])}


def _esp_contexts(model):
    """Return the official ESP component contexts safe to persist and display."""
    try:
        contexts = set()
        for context in model.find(component="ESP"):
            if (isinstance(context, str) and context.strip() and len(context) <= 255
                    and ".." not in context and "/" not in context and "\\" not in context
                    and not any(ord(character) < 32 or ord(character) == 127 for character in context)):
                contexts.add(context.strip())
        return sorted(contexts)[:128]
    except Exception:
        return []


def _well_contexts(model, esp_contexts):
    """Discover wells and their runnable flow/ESP objects through PTK's Well filter."""
    from sixgill.definitions import Parameters

    def component_contexts(component, well, allowed=None):
        try:
            values = model.find(component=component, Well=well)
        except Exception:
            return []
        safe = set()
        for value in values:
            if (isinstance(value, str) and value.strip() and len(value) <= 255
                    and ".." not in value and "/" not in value and "\\" not in value
                    and not any(ord(character) < 32 or ord(character) == 127 for character in value)):
                normalized = value.strip()
                if allowed is None or normalized in allowed:
                    safe.add(normalized)
        return sorted(safe)[:128]

    try:
        wells = set()
        for context in model.find(component="Well"):
            if (isinstance(context, str) and context.strip() and len(context) <= 255
                    and ".." not in context and "/" not in context and "\\" not in context
                    and not any(ord(character) < 32 or ord(character) == 127 for character in context)):
                wells.add(context.strip())
        esp_set = set(esp_contexts)
        result = []
        for well in sorted(wells):
            tubing_components = []
            for tubing in component_contexts("Tubing", well):
                try:
                    diameter = float(model.get_value(context=tubing, parameter=Parameters.Tubing.INNERDIAMETER))
                    description = model.describe(context=tubing, parameter=Parameters.Tubing.INNERDIAMETER)
                    unit = str(getattr(description, "units_symbol", "") or "").strip()
                except Exception:
                    continue
                if (math.isfinite(diameter) and 0 < diameter <= 1000 and unit and len(unit) <= 32
                        and not any(ord(character) < 32 or ord(character) == 127 for character in unit)):
                    tubing_components.append({"context": tubing, "innerDiameter": diameter, "unit": unit})
            result.append({
                "context": well,
                "espContexts": component_contexts("ESP", well, esp_set),
                "completionContexts": component_contexts("Completion", well),
                "tubingContexts": component_contexts("Tubing", well),
                "tubingComponents": tubing_components,
            })
        return result[:128]
    except Exception:
        return []


def result(status, message, model_kind=None, well=None, studies=None, error=None, inspection=None, esp_contexts=None, well_contexts=None, network_capability=None, gas_lift_capabilities=None):
    payload = {
        "status": status,
        "message": message,
        "modelKind": model_kind,
        "well": well,
        "studies": studies or [],
        "error": error,
        "inspection": inspection
    }
    if esp_contexts is not None:
        payload["espContexts"] = esp_contexts
    if well_contexts is not None:
        payload["wellContexts"] = well_contexts
    if network_capability is not None:
        payload["networkCapability"] = network_capability
    if gas_lift_capabilities is not None:
        payload["gasLiftCapabilities"] = gas_lift_capabilities
    print(json.dumps(payload, ensure_ascii=False), file=_PROTOCOL_OUTPUT, flush=True)


def _validated_network_capability(model, studies, network):
    """Advertise only Studies whose native Network Simulation task validates."""
    if not network.get("valid"):
        return None
    runnable_studies = []
    for study in studies:
        try:
            issues = model.tasks.networksimulation.validate(study=study, validate_model=False)
        except Exception:
            continue
        if not issues:
            runnable_studies.append(study)
    if not runnable_studies:
        return None
    inspection = _network_inspection(model, runnable_studies)
    if inspection is None:
        return None
    return {
        "schemaVersion": "pipesim-network-capability/1",
        "studies": runnable_studies,
        "networkInspection": inspection,
    }


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
        if network["candidate"] and not network["well_model"]:
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

        network_capability = _validated_network_capability(model, studies, network) if network["candidate"] else None

        components = {kind: list(model.find(component=kind)) for kind in ("Well", "BlackOilFluid", "Completion", "Tubing")}
        if not components["Well"]:
            return result("INVALID", "PIPESIM 井模型必须至少包含一口井")
        if any(not isinstance(context, str) or not context.strip() for context in components["Well"]):
            return result("INVALID", "PIPESIM 返回了无效井上下文，无法安全识别可运行对象")
        well = components["Well"][0] if len(components["Well"]) == 1 else None
        model_kind = "legacy_well"
        has_flow_components = bool(components["Completion"] and components["Tubing"])
        fluid_type = None
        if has_flow_components:
            fluid_type = model.fluids.fluid_type
            if fluid_type not in (Constants.FluidType.COMPOSITIONAL, Constants.FluidType.BLACKOIL):
                return result("INVALID", "PIPESIM 井模型流体类型不受当前 Toolkit 运行适配器支持")
        completion = components["Completion"][0] if components["Completion"] else None
        simple_well = has_flow_components and len(components["Well"]) == 1 and len(components["Completion"]) == 1 and len(components["Tubing"]) == 1
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
        esp_contexts = _esp_contexts(model)
        well_contexts = _well_contexts(model, esp_contexts)
        if len(well_contexts) != len(set(components["Well"])):
            return result("INVALID", "PIPESIM 井上下文清单不完整，无法安全选择目标井")
        inspection = inspect_well_pressure(model, completion, Parameters.Completion.RESERVOIRPRESSURE) if well and completion else None
        if well is None and network_capability is not None:
            # Multiple wells have no shared completion pressure. Keep a valid
            # nullable inspection envelope so scoped wells and verified Network
            # capability survive the reader; never select the first well.
            inspection = {"schemaVersion": "pipesim-well-inspection/1", "reservoirPressure": None}
        message = "模型验证完成"
        if model_kind == "legacy_well":
            message = "PIPESIM 通用井模型已识别；当前开放 Nodal、PT Profile、Combined、井轨迹，专用任务按结构校验"
        if well is None:
            message = "已识别 {0} 口井及其 ESP 上下文；运行井轨迹或 ESP 专项任务时必须显式选择对象".format(len(well_contexts))
        if not has_flow_components:
            message = "已识别井上下文；未发现可用于流动计算的完井或油管，井轨迹仍可按目标井读取"
        return result("READY", message, model_kind, well, studies, inspection=inspection,
                      esp_contexts=esp_contexts, well_contexts=well_contexts,
                      network_capability=network_capability,
                      gas_lift_capabilities=([discover_gas_lift(model, item["context"], study)
                                              for item in well_contexts for study in studies]
                                             if inspection is not None else None))
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
