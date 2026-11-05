"""Cross-process ARM64 loader/ART test. Targets only our disposable app_process VM."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time
import uuid
from zipfile import ZipFile

project = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
parser.add_argument("--app-apk", type=Path, default=project / "app/build/outputs/apk/release/app-release.apk",
                    help="Test the exact Root payload shipped in this main APK")
args = parser.parse_args()
nonce = uuid.uuid4().hex
remote = "/data/local/tmp/mockgeo-root-probe-" + nonce
output = project / "build/reports/root-probe" / nonce
output.mkdir(parents=True, exist_ok=True)
adb = [args.adb, "-s", args.serial]

# Validate and extract known entries before touching a connected device. Testing
# an unrelated unstripped build cannot verify the distributed APK.
artifacts = {}
artifact_directory = output / "artifacts"
artifact_directory.mkdir()
with ZipFile(args.app_apk) as apk:
    properties = dict(line.split('=', 1) for line in apk.read('assets/root/manifest.properties').decode().splitlines()
                      if line and not line.startswith('#'))
    for name in ('payload.apk', 'libmockgeo_root.so', 'loader'):
        content = apk.read('assets/root/' + name)
        assert hashlib.sha256(content).hexdigest() == properties[name], 'Packaged Root hash mismatch'
        artifacts[name] = artifact_directory / name
        artifacts[name].write_bytes(content)
(output / 'artifact-source.json').write_text(json.dumps(dict(
    apk=str(args.app_apk.resolve()), sha256=hashlib.sha256(args.app_apk.read_bytes()).hexdigest(),
    rootVersion=properties['version']), indent=2), encoding='utf-8')


def run(*parts, check=True, timeout=20):
    result = subprocess.run(adb + list(parts), capture_output=True, text=True, encoding="utf-8", errors="replace",
                            timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result.stdout


def root(command, **options):
    return run("shell", "su -c " + shlex.quote(command), **options)


system_pid = run("shell", "pidof", "system_server").strip()
host_pid = None
try:
    run("shell", "mkdir", "-p", remote)
    for name, local in artifacts.items():
        run("push", str(local), remote + "/" + name)
    root(f"chown root:root {remote} {remote}/*; chmod 755 {remote}; chmod 555 {remote}/loader; chmod 444 {remote}/payload.apk {remote}/libmockgeo_root.so")
    root(f"CLASSPATH={remote}/payload.apk nohup /system/bin/app_process64 /system/bin --nice-name=mockgeo-root-probe "
         f"com.mockgeo.root.ProbeHost {remote} </dev/null >{remote}/host.log 2>&1 &")
    until = time.monotonic() + 15
    while time.monotonic() < until:
        value = root(f"cat {remote}/host.pid", check=False).strip()
        if value.isdigit():
            host_pid = int(value)
            break
        time.sleep(0.2)
    if host_pid is None:
        raise RuntimeError("Probe VM did not become ready: " + root(f"cat {remote}/host.log", check=False))
    host_maps = root(f"cat /proc/{host_pid}/maps").lower()
    framework_present = "lsposed" in host_maps or "/lspd/" in host_maps
    (output / "runtime.json").write_text(json.dumps(dict(
        hostPid=host_pid, systemPid=system_pid, frameworkPresent=framework_present), indent=2), encoding="utf-8")
    assert not framework_present, "This independent probe must not contain an injected LSPosed framework"
    print("Injecting only disposable probe PID", host_pid, flush=True)
    result = root(f"{remote}/loader {host_pid} {remote}/libmockgeo_root.so {remote}/payload.apk {nonce} probe", timeout=65)
    (output / "loader.json").write_text(result, encoding="utf-8")
    assert json.loads(result)["queued"] is True
    until = time.monotonic() + 20
    report = {}
    while time.monotonic() < until:
        try:
            report = json.loads(root(f"cat {remote}/receipt.json", check=False))
        except ValueError:
            time.sleep(0.2)
            continue
        break
    (output / "receipt.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    assert report.get("ready") is True and report.get("mode") == "probe" and report.get("nonce") == nonce, report
    assert report.get("pid") == host_pid and report.get("checks") == 8, report
    assert report.get("version") == properties['version'], 'Wrong loaded payload version'
    for _ in range(20):
        if "restored" in root(f"cat {remote}/host-complete", check=False):
            break
        time.sleep(0.1)
    else:
        raise RuntimeError("Probe's original caller did not resume")
    assert run("shell", "pidof", "system_server").strip() == system_pid, "System PID changed"
    print("PASS: remote load, eight ART checks, original caller resumed, system PID unchanged", flush=True)
finally:
    (output / "host.log").write_text(root(f"cat {remote}/host.log", check=False), encoding="utf-8")
    if host_pid:
        logs = run("logcat", "-d", "--pid=" + str(host_pid), check=False)
        (output / "probe-logcat.txt").write_text(logs, encoding="utf-8")
        # Never stop a reused PID or any private application.
        name = root(f"cat /proc/{host_pid}/cmdline", check=False).split("\0")[0]
        if name == "mockgeo-root-probe":
            root("kill " + str(host_pid), check=False)
    for name in ("payload.apk", "libmockgeo_root.so", "loader", "host.pid", "heartbeat", "host.log",
                 "receipt.json", "receipt.tmp", "host-complete"):
        root("rm -f " + shlex.quote(remote + "/" + name), check=False)
    root("rmdir " + shlex.quote(remote), check=False)
    print("Reports:", output, flush=True)
