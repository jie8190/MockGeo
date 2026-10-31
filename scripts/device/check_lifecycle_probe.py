import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
events = report["events"]
failures = []
if not any(e["api"] == "complete" for e in events):
    failures.append("incomplete run")
for event in events:
    if "error" in event or event["api"] in ("pending-after-remove", "pre-cancel-callback", "queued-cancel-callback"):
        failures.append(f'{event["provider"]}/{event["api"]}')
    if event.get("result") == "fix" and not all((event.get("matchesExpected"), event.get("mockMatches"), event["reportedProvider"] == event["provider"])):
        failures.append(f'wrong fix: {event["provider"]}/{event["api"]}')
for provider in ("gps", "network", "fused"):
    pending = [e for e in events if e["provider"] == provider and e["api"] == "pending" and e.get("result") == "fix"]
    single = [e for e in events if e["provider"] == provider and e["api"] == "single" and e.get("result") == "fix"]
    if len(pending) < 2:
        failures.append(f"{provider}: fewer than two pending deliveries")
    if len(single) != 1:
        failures.append(f"{provider}: single delivered {len(single)} times")
    if "--require-flush" in sys.argv[2:]:
        flush = [e for e in events if e["provider"] == provider and e["api"] == "flush"]
        code = 710 + ("gps", "network", "fused").index(provider)
        if len(flush) != 1 or not flush[0].get("validType") or flush[0].get("code") != code:
            failures.append(f"{provider}: flush must deliver exactly one integer request code {code}")
print(json.dumps({"passed": not failures, "failures": failures}, ensure_ascii=False))
sys.exit(bool(failures))
