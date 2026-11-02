"""Verify the installed app's real foreground service, without holding instrumentation open."""
import json
import subprocess
import sys
import time
import uuid
import argparse
from pathlib import Path

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--compatibility", action="store_true", help="Expect the UI compatibility switch already enabled")
parser.add_argument("--format", choices=("current", "standard", "compatibility"), default="current")
parser.add_argument("--module", help="Verify the loaded module/payload with the short engine suite first")
parser.add_argument("--engine", choices=("xposed", "root"), default="xposed")
parser.add_argument("--serial", required=True)
parser.add_argument("--adb", default="adb")
parser.add_argument("--require-flush", action="store_true")
parser.add_argument("--duration-seconds", type=int, default=60, choices=range(20, 181), metavar="20..180")
parser.add_argument("--output-dir", type=Path, help="Keep this run separate from previous acceptance reports")
options = parser.parse_args()
if options.compatibility:
    options.format = "compatibility"
reports = options.output_dir or root / "build/reports/device" / (("root-" if options.engine == "root" else "compat-") + (options.module or "0.3.4").replace(".", "") + "-device") / "normal-app"
reports.mkdir(parents=True, exist_ok=True)
adb = [options.adb, "-s", options.serial]
flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def run(*args, check=True):
    result = subprocess.run(adb + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=15, check=check, creationflags=flags, stdin=subprocess.DEVNULL)
    return result.stdout


def state():
    raw = run("shell", "settings", "get", "global", "mockgeo_state_v1")
    return json.loads(raw)


if options.module:
    if options.engine == "root":
        # This ROM may suspend a background instrumentation process; keep our app visible.
        precheck = reports / "engine-precheck.log"
        with precheck.open("w", encoding="utf-8") as stream:
            proc = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "mode", "root-check",
                "com.mockgeo.app.test/com.mockgeo.app.DeviceSmokeInstrumentation"], stdout=stream,
                stderr=subprocess.STDOUT, creationflags=flags, stdin=subprocess.DEVNULL)
            time.sleep(2)
            run("shell", "am", "start", "-W", "-n", "com.mockgeo.app/.MainActivity")
            try:
                proc.wait(timeout=120)
            except subprocess.TimeoutExpired:
                run("shell", "am", "force-stop", "com.mockgeo.app", check=False)
                proc.wait(timeout=10)
                raise RuntimeError("Root precheck timed out; controller stopped, lease expires automatically")
        smoke = precheck.read_text(encoding="utf-8")
        if "backend=root" not in smoke or ("version=" + options.module) not in smoke:
            raise RuntimeError("Independent Root identity/version not confirmed; see engine-precheck.log")
    else:
        smoke = run("shell", "am", "instrument", "-w", "-r", "-e", "requiredModule", options.module,
                    "com.mockgeo.app.test/com.mockgeo.app.DeviceSmokeInstrumentation")
    (reports / "engine-precheck.log").write_text(smoke, encoding="utf-8")
    if "INSTRUMENTATION_CODE: -1" not in smoke:
        raise RuntimeError("Loaded module/engine precheck failed; see engine-precheck.log")
run("shell", "am", "start", "-W", "-n", "com.mockgeo.app/.MainActivity")
time.sleep(1)
try:
    print(run("shell", "su -c 'am start-foreground-service -n com.mockgeo.app/.sim.SimulationService -a com.mockgeo.action.START_FIXED --ef lat 39.9087 --ef lng 116.3975'"), flush=True)
    deadline = time.monotonic() + 10
    while not state()["e"]:
        if time.monotonic() > deadline:
            raise RuntimeError("Normal service failed to start")
        time.sleep(0.2)
    last_update = time.monotonic()
    last_et = state()["et"]
    compatibility = bool(state()["h"])
    if options.format != "current" and compatibility != (options.format == "compatibility"):
        raise RuntimeError("App's compatibility setting differs from the requested test")
    out = reports / ("compatibility" if compatibility else "standard")
    out.mkdir(parents=True, exist_ok=True)
    for activity, filename, validator in (("ProbeActivity", "report.json", "check_external_probe.py"),
        ("LifecycleProbeActivity", "lifecycle-report.json", "check_lifecycle_probe.py")):
        run("shell", "am", "force-stop", "com.mockgeo.probe")
        identity = uuid.uuid4().hex
        run("shell", "am", "start", "-n", "com.mockgeo.probe/." + activity, "--es", "runId", identity,
            "--ez", "expectedMock", str(not compatibility).lower(), "--ez", "keepScreenOn", "true",
            "--ei", "durationMs", str(options.duration_seconds * 1000))
        deadline = time.monotonic() + (options.duration_seconds + 15 if activity == "ProbeActivity" else 30)
        next_foreground_check = time.monotonic() + 3
        while time.monotonic() < deadline:
            fresh = state()
            if fresh["et"] != last_et:
                last_et, last_update = fresh["et"], time.monotonic()
            if not fresh["e"] or time.monotonic() - last_update > 10:
                raise RuntimeError("Normal app stopped renewing its simulation lease")
            try:
                report = json.loads(run("shell", "run-as", "com.mockgeo.probe", "cat", "files/" + filename, check=False))
            except ValueError:
                report = {}
            if time.monotonic() >= next_foreground_check:
                activity_state = run("shell", "dumpsys", "activity", "activities")
                resumed = [line for line in activity_state.splitlines()
                    if ("mResumedActivity:" in line or "topResumedActivity=" in line) and "ActivityRecord{" in line]
                foreground = bool(resumed) and all("com.mockgeo.probe/." + activity in line for line in resumed)
                if not foreground:
                    (out / ("incomplete-" + filename)).write_text(json.dumps(report, indent=2), encoding="utf-8")
                    raise RuntimeError("Test receiver left the foreground; keep the independent probe visible and rerun")
                next_foreground_check = time.monotonic() + 5
            if report.get("runId") == identity and any(e["api"] == "complete" for e in report.get("events", [])):
                if activity == "ProbeActivity" and report.get("durationMs") != options.duration_seconds * 1000:
                    raise RuntimeError("Old probe APK cannot verify sustained delivery; install the matching new probe")
                destination = out / filename
                destination.write_text(json.dumps(report, indent=2), encoding="utf-8")
                extra = ["--require-flush"] if options.require_flush and validator == "check_lifecycle_probe.py" else []
                subprocess.run([sys.executable, str(Path(__file__).resolve().parent / validator), str(destination)] + extra, check=True)
                if any(e.get("result") == "fix" and e["provider"] != "webview" and
                       e.get("isMock") != (not compatibility) for e in report["events"]):
                    raise RuntimeError("Mock flag mismatch")
                print(activity + " passed with live normal-app lease", flush=True)
                break
            time.sleep(0.3)
        else:
            (out / ("incomplete-" + filename)).write_text(json.dumps(report, indent=2), encoding="utf-8")
            raise RuntimeError("Probe timed out")
finally:
    run("shell", "am", "force-stop", "com.mockgeo.probe", check=False)
    print(run("shell", "su -c 'am startservice -n com.mockgeo.app/.sim.SimulationService -a com.mockgeo.action.STOP'", check=False), flush=True)
    deadline = time.monotonic() + 8
    while state()["e"] and time.monotonic() < deadline:
        time.sleep(0.2)
    if state()["e"]:
        run("shell", "am", "force-stop", "com.mockgeo.app")
        raise RuntimeError("Stop not confirmed; controller killed, lease will expire")
    print("Normal service STOP confirmed", flush=True)
