"""GNSS, NMEA and live exemption tests against the normal app service; no private targets."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

p = argparse.ArgumentParser()
p.add_argument("--adb", default="adb")
p.add_argument("--serial", required=True)
p.add_argument("--require-sensors", action="store_true")
p.add_argument("--module", default="0.3.12")
p.add_argument("--engine", choices=("xposed", "root"), default="xposed")
args = p.parse_args()
root = Path(__file__).resolve().parents[2]
out = root / "build/reports/device" / (("root-" if args.engine == "root" else "compat-") + args.module.replace(".", "") + "-device") / "extended"
out.mkdir(parents=True, exist_ok=True)
adb = [args.adb, "-s", args.serial]
flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
saved_policy = False
last_et = None
last_update = time.monotonic()


def command(*parts, check=True):
    return subprocess.run(adb + list(parts), capture_output=True, text=True, encoding="utf-8", errors="replace",
        check=check, timeout=15, creationflags=flags, stdin=subprocess.DEVNULL).stdout


def su(script):
    return command("shell", "su -c '" + script + "'")


def state():
    return json.loads(command("shell", "settings", "get", "global", "mockgeo_state_v1"))


def live():
    global last_et, last_update
    cfg = state()
    if cfg["et"] != last_et:
        last_et, last_update = cfg["et"], time.monotonic()
    if not cfg["e"] or time.monotonic() - last_update > 10:
        raise RuntimeError("Controller lease is inactive; test invalid")
    return cfg


def wait_for(test, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if test():
            return
        time.sleep(0.2)
    raise RuntimeError("Timed out waiting for module state")


def start():
    command("shell", "am", "start", "-W", "-n", "com.mockgeo.app/.MainActivity")
    su("am start-foreground-service -n com.mockgeo.app/.sim.SimulationService -a com.mockgeo.action.START_FIXED --ef lat 39.9087 --ef lng 116.3975")
    wait_for(lambda: state()["e"] == 1)


def stop():
    su("am startservice -n com.mockgeo.app/.sim.SimulationService -a com.mockgeo.action.STOP")
    wait_for(lambda: state()["e"] == 0)


def read_report(filename, run_id):
    try:
        report = json.loads(command("shell", "run-as", "com.mockgeo.probe", "cat", "files/" + filename, check=False))
        return report if report.get("runId") == run_id else None
    except ValueError:
        return None


def phase(name):
    command("shell", "am", "start", "-n", "com.mockgeo.probe/.PolicyProbeActivity",
        "--activity-single-top", "--activity-clear-top", "--es", "phase", name)


def hold(seconds, enabled=True):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if enabled:
            live()
        elif state()["e"]:
            raise RuntimeError("Simulation resumed during stopped-phase test")
        time.sleep(0.3)


try:
    start()
    cfg = live()
    command("shell", "am", "force-stop", "com.mockgeo.probe")
    run_id = uuid.uuid4().hex
    command("shell", "am", "start", "-n", "com.mockgeo.probe/.GnssSensorProbeActivity",
        "--es", "runId", run_id, "--ei", "satellites", str(cfg["sat"]), "--ef", "bearing", str(cfg["brg"]))
    hold(13)
    report = read_report("gnss-report.json", run_id)
    if not report:
        raise RuntimeError("No GNSS report from this run")
    path = out / "gnss-report.json"
    path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    sensor_arg = ["--require-sensors"] if args.require_sensors else []
    subprocess.run([sys.executable, str(Path(__file__).resolve().parent / "check_gnss_probe.py"), str(path)] + sensor_arg, check=True)
    print("GNSS/NMEA and removal passed", flush=True)

    if "com.mockgeo.probe" in live().get("x", "").split(","):
        raise RuntimeError("Probe is already exempt; preserve the existing policy and stop")
    su("test ! -e /data/misc/mockgeo/exempt.acceptance.bak && cp -p /data/misc/mockgeo/exempt.txt /data/misc/mockgeo/exempt.acceptance.bak")
    saved_policy = True
    command("shell", "am", "force-stop", "com.mockgeo.probe")
    run_id = uuid.uuid4().hex
    command("shell", "am", "start", "-n", "com.mockgeo.probe/.PolicyProbeActivity",
        "--es", "runId", run_id, "--ei", "satellites", str(cfg["sat"]))
    hold(4)
    phase("transition")
    su('cp -p /data/misc/mockgeo/exempt.acceptance.bak /data/misc/mockgeo/exempt.next && printf "\\ncom.mockgeo.probe\\n" >> /data/misc/mockgeo/exempt.next && mv -f /data/misc/mockgeo/exempt.next /data/misc/mockgeo/exempt.txt')
    wait_for(lambda: "com.mockgeo.probe" in live().get("x", "").split(","))
    phase("exempt"); hold(4)
    phase("transition")
    su("cp -p /data/misc/mockgeo/exempt.acceptance.bak /data/misc/mockgeo/exempt.next && mv -f /data/misc/mockgeo/exempt.next /data/misc/mockgeo/exempt.txt")
    wait_for(lambda: "com.mockgeo.probe" not in live().get("x", "").split(","))
    phase("resumed"); hold(4)
    phase("transition"); stop(); phase("stopped"); hold(4, enabled=False)
    phase("transition"); start(); phase("restarted"); hold(5)
    phase("removed"); hold(3)
    phase("complete")
    report = read_report("policy-report.json", run_id)
    if not report:
        raise RuntimeError("No live-policy report from this run")
    path = out / "policy-report.json"
    path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    subprocess.run([sys.executable, str(Path(__file__).resolve().parent / "check_policy_probe.py"), str(path)] + sensor_arg, check=True)
    print("Live exemptions, stop/restart and removal passed", flush=True)
finally:
    if saved_policy:
        su("cp -p /data/misc/mockgeo/exempt.acceptance.bak /data/misc/mockgeo/exempt.next && mv -f /data/misc/mockgeo/exempt.next /data/misc/mockgeo/exempt.txt && rm /data/misc/mockgeo/exempt.acceptance.bak")
    command("shell", "am", "force-stop", "com.mockgeo.probe", check=False)
    stop()
    print("Original exemption policy restored; simulation stopped", flush=True)
