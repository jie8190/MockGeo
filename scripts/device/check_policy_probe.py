import json
import sys
from pathlib import Path

events = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))["events"]
failures = []
if not any(e["api"] == "complete" for e in events):
    failures.append("run incomplete")
for phase in ("normal", "exempt", "resumed", "stopped", "restarted", "removed"):
    if not any(e["phase"] == phase for e in events):
        failures.append("missing phase " + phase)
    samples = [e for e in events if e["phase"] == phase and e["phaseMs"] >= 1000]
    if any("error" in e for e in samples):
        failures.append(phase + " API error")
    if phase in ("exempt", "stopped"):
        if any(e.get("matchesExpected") for e in samples if e["provider"] != "sensor" or "--require-sensors" in sys.argv):
            failures.append(phase + " still received synthetic data")
        for provider in ("gps", "network", "fused"):
            if not any(e["provider"] == provider and e["api"] == "last" for e in samples):
                failures.append(phase + " missing last-location observation for " + provider)
    elif phase == "removed":
        if any(e["provider"] != "probe" for e in samples):
            failures.append("callbacks after removal")
    else:
        for provider, apis in (("gps", ("last", "updates", "pending")), ("network", ("last", "updates", "pending")),
                               ("fused", ("last", "updates", "pending")), ("gnss", ("status",)), ("nmea", ("gga",))):
            for api in apis:
                selected = [e for e in samples if e["provider"] == provider and e["api"] == api]
                if not selected or not all(e.get("matchesExpected") for e in selected):
                    failures.append(phase + "/" + provider + "/" + api + " did not match")
        if "--require-sensors" in sys.argv:
            selected = [e for e in samples if e["provider"] == "sensor"]
            if not selected or not all(e.get("matchesExpected") for e in selected):
                failures.append(phase + " scoped sensor did not match")
print(json.dumps({"passed": not failures, "failures": failures}))
sys.exit(bool(failures))
