"""Build the ARM64 Root loader/payload with pinned sources and the local Android NDK."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--sdk", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()
project = Path(__file__).resolve().parent.parent
workspace = project.parent
native = project / "rootpayload/src/main/cpp"
output = Path(args.output).resolve()
suffix = ".exe" if os.name == "nt" else ""
toolroot = workspace / "_tools/root-build-python"
cmake = os.environ.get("ROOT_CMAKE") or str(toolroot / f"cmake/data/bin/cmake{suffix}")
ninja = os.environ.get("ROOT_NINJA") or str(toolroot / f"ninja/data/bin/ninja{suffix}")
if not Path(cmake).is_file():
    cmake = shutil.which("cmake") or cmake
if not Path(ninja).is_file():
    ninja = shutil.which("ninja") or ninja
ndk = Path(args.sdk) / "ndk/27.0.12077973"
subprocess.run([cmake, "-S", str(native), "-B", str(output), "-G", "Ninja",
    "-DCMAKE_MAKE_PROGRAM=" + ninja, "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
    "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
    "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"], check=True)
subprocess.run([cmake, "--build", str(output), "--target", "mockgeo_root", "mockgeo_loader", "-j", "4"], check=True)
