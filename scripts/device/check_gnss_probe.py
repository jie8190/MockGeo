import json
import sys
from pathlib import Path

events = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))["events"]
failures = []
if not any(e["api"] == "complete" for e in events):
    failures.append("run incomplete")
for e in events:
    if "error" in e or e.get("accepted") is False:
        failures.append(e["api"] + " failed")
    if e.get("afterRemoval") and e["api"] in ("gnss-status", "nmea-GGA", "nmea-RMC", "gnss-started", "gnss-first-fix"):
        failures.append("callback after unregister: " + e["api"])
for api in ("gnss-status", "nmea-GGA", "nmea-RMC"):
    samples = [e for e in events if e["api"] == api]
    if len(samples) < 3 or any(not e.get("matchesExpected") for e in samples):
        failures.append(api + " requires at least 3 matching samples")
    if api.startswith("nmea") and any(not e.get("checksumValid") or not e.get("consistent") for e in samples):
        failures.append(api + " checksum/date/satellite mismatch")
for api in ("gnss-started", "gnss-first-fix"):
    if sum(e["api"] == api for e in events) != 1:
        failures.append(api + " should occur exactly once")
if not any(e["api"] == "public-builder" and e.get("passed") for e in events):
    failures.append("public GNSS Builder did not pass")
if "--require-sensors" in sys.argv:
    for typ in (1, 2, 4, 9, 10, 11):
        samples = [e for e in events if e["api"] == "sensor" and e.get("type") == typ]
        if not samples or not all(e.get("matchesSynthetic") for e in samples):
            failures.append(f"scoped sensor {typ} did not match")
print(json.dumps({"passed": not failures, "failures": failures}))
sys.exit(bool(failures))
