"""Validate public API results from the separate probe APK; no app identities or real coordinates."""
import json
import sys
from pathlib import Path

events = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))["events"]
failed = False
for provider in ("gps", "network", "fused"):
    for api in ("last", "current", "updates"):
        samples = [event for event in events if event.get("provider") == provider and event.get("api") == api]
        correct = bool(samples) and all(event.get("matchesExpected") is True and event.get("reportedProvider") == provider for event in samples)
        print(f"{provider}/{api}: {'PASS' if correct else 'FAIL'} ({len(samples)} samples)")
        failed |= not correct
web = [event for event in events if event.get("provider") == "webview" and event.get("api") == "geolocation"]
correct = bool(web) and all(event.get("matchesExpected") is True for event in web)
print(f"webview/geolocation: {'PASS' if correct else 'FAIL'}")
failed |= not correct
if not any(event.get("api") == "complete" for event in events):
    print("probe completion: FAIL")
    failed = True
sys.exit(1 if failed else 0)
