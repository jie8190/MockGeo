"""Validate all public API samples, including late reversion and stalled subscriptions."""
import json
import sys
from pathlib import Path

def validate(report):
    events = report.get("events", [])
    failures = []
    # Old archived reports contain only three frames and cannot prove sustained delivery.
    duration = report.get("durationMs", 0)
    for provider in ("gps", "network", "fused"):
        for api in ("last", "current", "updates"):
            samples = [e for e in events if e.get("provider") == provider and e.get("api") == api]
            if not samples or not all(e.get("matchesExpected") is True and e.get("reportedProvider") == provider for e in samples):
                failures.append(f"{provider}/{api}: missing or incorrect location")
            if api == "updates" and duration:
                times = sorted(e.get("elapsedMs", -1) for e in samples)
                gaps = [b - a for a, b in zip([0] + times, times + [duration])]
                if not times or times[0] < 0 or times[-1] > duration + 1_000 or max(gaps) > 5_000:
                    failures.append(f"{provider}/updates: delivery gap exceeds 5 seconds")
    web = [e for e in events if e.get("provider") == "webview" and e.get("api") == "geolocation"]
    if not web or not all(e.get("matchesExpected") is True for e in web):
        failures.append("webview/geolocation: missing or incorrect location")
    completion = [e for e in events if e.get("api") == "complete"]
    if not completion or (duration and completion[-1].get("elapsedMs", 0) < duration):
        failures.append("probe completion missing or premature")
    return failures


if __name__ == "__main__":
    data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    errors = validate(data)
    print("\n".join(errors) if errors else "Public API locations: PASS")
    if not data.get("durationMs"):
        print("Archived first-frame report: sustained delivery NOT verified")
    sys.exit(1 if errors else 0)
