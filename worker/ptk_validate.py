import json
import os
import sys

_PROTOCOL_OUTPUT = sys.stdout
ptk_path = os.environ.get("PIPESIM_PTK_PATH")
if ptk_path:
    sys.path.insert(0, ptk_path)


def result(status, message, model_kind=None, well=None, studies=None, error=None):
    print(json.dumps({
        "status": status,
        "message": message,
        "modelKind": model_kind,
        "well": well,
        "studies": studies or [],
        "error": error
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
        components = {kind: list(model.find(component=kind)) for kind in ("Well", "BlackOilFluid", "Completion", "Tubing")}
        for required in ("Well", "Completion", "Tubing"):
            if len(components[required]) != 1:
                return result("INVALID", "首版仅支持恰好包含一口井、一个完井和一个油管的单井生产模型")
        completion = components["Completion"][0]
        fluid_type = model.fluids.fluid_type
        geometry = model.get_value(completion, parameter=Parameters.Completion.GEOMETRYPROFILETYPE)
        if fluid_type == Constants.FluidType.COMPOSITIONAL:
            if str(geometry).lower() != "vertical":
                return result("INVALID", "首版仅支持 CSW_102 型垂直单完井基础气井")
            model_kind = "basic_gas"
        elif fluid_type == Constants.FluidType.BLACKOIL:
            fluid = model.get_value(completion, parameter=Parameters.Well.ASSOCIATEDBLACKOILFLUID)
            if fluid not in components["BlackOilFluid"]:
                return result("INVALID", "首版仅支持完井关联黑油流体的液体生产模型")
            model_kind = "black_oil_liquid"
        else:
            return result("INVALID", "首版仅支持黑油液体或组合流体基础气井生产模型")
        studies = [study.name for study in model._catalog.lookup_entries_by_class_ids([ModelClasses.STUDY])]
        if not studies:
            return result("INVALID", "模型中未找到可运行的研究方案")
        return result("READY", "模型验证完成", model_kind, components["Well"][0], studies)
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
