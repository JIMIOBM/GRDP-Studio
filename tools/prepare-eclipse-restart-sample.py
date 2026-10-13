"""Prepare EX5 observed history and two explicit forecasts; no simulator results bundled."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--official-directory", default="C:/ecl/2024.1/schedule/tutorials/ex5")
    parser.add_argument("--output", default="test-data/official-eclipse-ex5-history-forecasts.zip")
    args = parser.parse_args()
    official = Path(args.official_directory)
    output = Path(args.output)
    original = (official / "EX5.DATA").read_text(encoding="utf-8")
    assert original.count("\nSCHEDULE\n") == original.count("\nSUMMARY\n") == 1
    history = original.replace("\nSCHEDULE\n", "\nSCHEDULE\n\nRPTRST\n BASIC=3 FREQ=1 /\n", 1)
    wells = ["G1", "G2", "G3", "G4", "P1:01", "P1:02", "P2", "P3", "P4", "P5", "P6"]
    cumulative = "WOPT\n" + "".join(f" '{well}'\n" for well in wells) + " /\n"
    history = history.replace("\nSUMMARY\n", "\nSUMMARY\n\nFOPT\n\n" + cumulative, 1)
    payloads = {"EX5.DATA": history.encode(), "BASE.SCH": (official / "BASE.SCH").read_bytes(),
                "EX5.GRDECL": (official / "EX5.GRDECL").read_bytes()}
    for label, target in [("FOREA", 500), ("FOREB", 350)]:
        forecast, count = re.subn(r"(?ms)^SOLUTION\b.*?^SUMMARY\b", "SOLUTION\n\nRESTART\n 'EX5' 80 /\n\nSUMMARY", history)
        assert count == 1 and forecast.count("'BASE.SCH'") == 1
        forecast = forecast.replace("'BASE.SCH'", f"'{label}.SCH'", 1)
        forecast = forecast.replace("\nFMTOUT\n", "\nFMTIN\n\nUNIFIN\n\nFMTOUT\n", 1)
        schedule = "WCONPROD\n" + "".join(f" '{well}' 'OPEN' 'ORAT' {target} 4* 2000 /\n" for well in wells) + "/\n"
        schedule += "DATES\n 31 'DEC' 1976 /\n/\nDATES\n 31 'DEC' 1977 /\n/\n"
        payloads[label + ".DATA"] = forecast.encode()
        payloads[label + ".SCH"] = schedule.encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in sorted(payloads.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 12, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, content)
    provenance = {
        "mainFile": "EX5.DATA", "officialDirectory": str(official),
        "officialMainSha256": hashlib.sha256((official / "EX5.DATA").read_bytes()).hexdigest(),
        "changes": ["History: only BASIC=3 FREQ=1 restart output and FOPT/WOPT cumulative output added.",
                    "Forecast derivatives: formatted unified RESTART EX5 report80; SOLUTION inherited completely, no aquifer reinitialization.",
                    "Eleven existing producers: ORAT500/350, BHP2000; existing injection and other state inherited; two future DATES."],
        "historyCutoff": {"report": 80, "date": "1975-12-31", "timeDays": 2190},
        "forecastDates": ["1976-12-31", "1977-12-31"],
        "files": [{"relativePath": name, "sizeBytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
                  for name, content in sorted(payloads.items())],
        "zipSha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "platformVerified": False, "manualUiVerified": False,
    }
    output.with_suffix(".provenance.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(output), "files": len(payloads), "zipSha256": provenance["zipSha256"]}))


if __name__ == "__main__":
    main()
