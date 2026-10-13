"""Independent formatted-output proof for EX5 history and two restarted forecasts."""
from pathlib import Path
import argparse
import hashlib
import json
import math
import re
import struct

HEADER = re.compile(r"(?m)^[ \t]*'([A-Z0-9_ ]{8})'[ \t]+(\d+)[ \t]+'(INTE|REAL|DOUB|CHAR|LOGI|MESS)'[ \t]*\r?$")


def blocks(path):
    text = path.read_bytes().decode("ascii")
    headings = list(HEADER.finditer(text))
    assert headings and not text[:headings[0].start()].strip()
    result = []
    for index, match in enumerate(headings):
        end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
        keyword, count, kind = match[1].rstrip(), int(match[2]), match[3]
        body = text[match.end():end]
        tokens = re.findall(r"'([^']{8})'", body) if kind == "CHAR" else body.split()
        assert len(tokens) == count, (path, keyword, count, len(tokens))
        if kind == "CHAR":
            values = [token.strip() for token in tokens]
        elif kind == "LOGI":
            assert set(tokens) <= {"T", "F"}
            values = tokens
        else:
            def number(token):
                token = token.replace("D", "E").replace("d", "E")
                if "e" not in token.lower():
                    token = re.sub(r"(?<=[0-9.])([+-]\d{2,4})$", r"E\1", token)
                value = int(token) if kind == "INTE" else float(token)
                if kind == "REAL":
                    value = struct.unpack("f", struct.pack("f", value))[0]
                assert math.isfinite(value)
                return value
            values = [number(token) for token in tokens]
        result.append((keyword, kind, values))
    return result


def summary(folder, case):
    specification = {name: (kind, values) for name, kind, values in blocks(folder / (case + ".FSMSPEC"))}
    keywords, names, units = [specification[key][1] for key in ["KEYWORDS", "WGNAMES", "UNITS"]]
    assert specification["STARTDAT"] == ("INTE", [1, 1, 1970, 0, 0, 0])
    assert len(keywords) == len(names) == len(units)
    assert len([key for key in keywords if key == "TIME"]) == 1
    axis = keywords.index("TIME")
    numbers = specification["NUMS"][1]
    assert len(numbers) == len(keywords) and all(number <= 0 for number in numbers)
    rows = [values for name, kind, values in blocks(folder / (case + ".FUNSMRY")) if name == "PARAMS"]
    assert all(len(values) == len(keywords) for values in rows)
    times = [values[axis] for values in rows]
    assert times == sorted(set(times))
    identities, series = set(), []
    for column, (keyword, name, unit) in enumerate(zip(keywords, names, units)):
        if column == axis:
            continue
        obj = None if name in ["", "FIELD", ":+:+:+:+"] else name
        assert (keyword, obj) not in identities
        identities.add((keyword, obj))
        series.append({"keyword": keyword, "objectName": obj, "unit": unit or None,
                       "points": [{"timeDays": values[axis], "value": values[column]} for values in rows]})
    return {"series": series, "times": times, "startDate": "1970-01-01"}


def diagnostics(folder, case):
    text = (folder / (case + ".ECLEND")).read_text(encoding="ascii")
    counts = {key.lower(): int(re.search(r"\b" + key + r"\s+(\d+)", text)[1]) for key in ["Comments", "Warnings", "Problems", "Errors", "Bugs"]}
    assert all(counts[key] == 0 for key in ["problems", "errors", "bugs"]), counts
    return counts


def selected_report(path, number):
    data = path.read_bytes()
    headings = list(re.finditer(rb"(?m)^[ \t]*'SEQNUM  '[ \t]+1[ \t]+'INTE'[ \t]*\r?\n[ \t]*(\d+)", data))
    records = [(int(match[1]), match.start(), headings[index + 1].start() if index + 1 < len(headings) else len(data)) for index, match in enumerate(headings)]
    matches = [(start, end) for report, start, end in records if report == number]
    assert len(matches) == 1
    start, end = matches[0]
    return data[start:end], [report for report, start, end in records], hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    root = args.directory
    history = summary(root / "history", "EX5")
    assert len(history["series"]) == 88 and len(history["times"]) == 81 and history["times"][0] == 0 and history["times"][-1] == 2190
    state, reports, source_sha = selected_report(root / "history/EX5.FUNRST", 80)
    assert reports == list(range(81))
    proof = {"history": {"summary": history, "diagnostics": diagnostics(root / "history", "EX5"), "restartReports": reports,
                         "restartSha256": source_sha}, "selectedState": {"reportNumber": 80, "date": "1975-12-31", "timeDays": 2190,
                         "sizeBytes": len(state), "sha256": hashlib.sha256(state).hexdigest()}, "forecasts": {}}
    for name in ["FOREA", "FOREB"]:
        future = summary(root / name, name)
        assert len(future["series"]) == 88 and future["times"] == [2556, 2921]
        assert (root / name / "EX5.FUNRST").read_bytes() == state
        proof["forecasts"][name] = {"summary": future, "diagnostics": diagnostics(root / name, name),
                                   "inputSha256": hashlib.sha256((root / name / (name + ".DATA")).read_bytes()).hexdigest()}
    proof.update({"nativeVerified": True, "platformVerified": False, "manualUiVerified": False})
    destination = root / "native-acceptance.json"
    destination.write_text(json.dumps(proof, indent=2), encoding="utf-8")
    print(json.dumps({"nativeVerified": True, "historyPoints": 81, "series": 88, "futureTimes": [2556, 2921], "evidence": str(destination)}))


if __name__ == "__main__":
    main()
