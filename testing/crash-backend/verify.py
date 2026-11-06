#!/usr/bin/env python3
"""Exercise fatal capture in isolated macOS child processes, never in a host app."""

import hashlib
import json
from pathlib import Path
import resource
import signal
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]


def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, capture_output=True, cwd=ROOT, **kwargs)


def no_core_dump():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if sys.platform != "darwin":
        raise RuntimeError("This process-level fixture requires macOS and Xcode command-line tools")
    # The final ordinary Clang flag also avoids a SwiftPM parser failure when
    # -Werror is the final forwarded argument in this toolchain.
    for target in ["OsservioCrashSupport", "OsservioCrashBackend"]:
        run(["swift", "build", "--target", target, "-Xcc", "-Werror",
             "-Xcc", "-fno-omit-frame-pointer"], timeout=180)
    binary_path = Path(run(["swift", "build", "--show-bin-path"], timeout=60).stdout.strip())
    work = ROOT / ".work" / "crash-backend"
    work.mkdir(parents=True, exist_ok=True)
    fixture = Path(tempfile.mkdtemp(prefix="process-", dir=work))
    probe = fixture / "osservio-crash-probe"
    vendor = ROOT / "native/ios/Vendor/PLCrashReporter"
    support = ROOT / "native/ios/Sources/OsservioCrashSupport"
    objects = sorted((binary_path / "OsservioCrashBackend.build").rglob("*.o"))
    objects += sorted((binary_path / "OsservioCrashSupport.build").rglob("*.o"))
    if not objects:
        raise RuntimeError("The backend build produced no object files")
    symbols = run(["xcrun", "nm", "-gU", *map(str, objects)], timeout=60).stdout
    for line in symbols.splitlines():
        if not line or line.endswith(":"):
            continue
        if any(name in line for name in ["plcrash", "PLCrash", "protobuf", "shared_handler_context"]):
            if "Osservio" not in line:
                raise AssertionError(f"Unnamespaced backend symbol: {line}")
    run(["xcrun", "clang", "-fobjc-arc", "-Wall", "-Wextra", "-Werror", "-DPLCR_PRIVATE",
         "-I", str(vendor / "include"), "-I", str(vendor / "Source"),
         "-I", str(vendor / "Dependencies/protobuf-c"),
         "-I", str(support / "include"), str(Path(__file__).with_name("Probe.m")),
         *map(str, objects), "-framework", "Foundation", "-lc++", "-o", str(probe)], timeout=60)

    receipt = {"fixture": str(fixture.relative_to(ROOT)), "probeSHA256": digest(probe), "checks": []}

    def invoke(mode, path, expected=0):
        result = subprocess.run([str(probe), mode, str(path)], text=True, capture_output=True,
                                timeout=20, preexec_fn=no_core_dump)
        if result.returncode != expected:
            raise AssertionError(f"{mode}: expected {expected}, got {result.returncode}: {result.stderr}")
        if result.stderr:
            raise AssertionError(f"{mode}: unexpected stderr: {result.stderr}")
        return result.stdout.strip()

    receipt["checks"].append({"mode": "symbol-namespace", "isolated": True})
    for mode in ["budget", "decoder-initialization", "image-budget", "image-size", "metadata", "live", "signal-conflict",
                 "ignored-signal", "exception-conflict", "mach-conflict"]:
        directory = fixture / mode
        directory.mkdir(mode=0o700)
        result = invoke(mode, directory)
        receipt["checks"].append({"mode": mode, "result": result})

    paths = {}
    for mode in ["on", "detail", "default-off", "off", "expired", "baseline-after-expiry"]:
        directory = fixture / mode
        directory.mkdir(mode=0o700)
        path = Path(invoke(mode, directory, -signal.SIGABRT))
        if not path.is_relative_to(directory):
            raise AssertionError("Report path escaped its fixture")
        paths[mode] = path
        expected_file = mode in {"on", "detail", "baseline-after-expiry"}
        if path.exists() != expected_file:
            raise AssertionError(f"{mode}: wrong recording disposition")
        check = {"mode": mode, "recorded": expected_file}
        if expected_file:
            check["report"] = json.loads(invoke("read", path))
            check["sha256"] = digest(path)
            if path.stat().st_mode & 0o777 != 0o600:
                raise AssertionError("Report file permissions are not private")
        receipt["checks"].append(check)

    original = digest(paths["on"])
    invoke("on", fixture / "on", -signal.SIGABRT)
    if digest(paths["on"]) != original:
        raise AssertionError("An unrecovered report was overwritten")
    receipt["checks"].append({"mode": "existing-file", "preserved": True})

    protected = fixture / "protected.bin"
    protected.write_bytes(b"test-owned sentinel")
    paths["off"].symlink_to(protected)
    original = digest(protected)
    invoke("on", fixture / "off", -signal.SIGABRT)
    if digest(protected) != original or not paths["off"].is_symlink():
        raise AssertionError("A report symlink was followed or replaced")
    receipt["checks"].append({"mode": "symlink", "preserved": True})

    limited = fixture / "small-budget"
    limited.mkdir(mode=0o700)
    limited_report = Path(invoke("small-budget", limited, -signal.SIGABRT))
    if not limited_report.is_relative_to(limited) or not 0 < limited_report.stat().st_size <= 128:
        raise AssertionError("The fatal writer ignored its byte limit")
    invoke("invalid", limited_report)
    receipt["checks"].append({"mode": "small-budget", "bytes": limited_report.stat().st_size,
                              "incompleteReportRejected": True})

    receipt_path = fixture / "receipt.json"
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"{len(receipt['checks'])} process checks passed; {receipt_path.relative_to(ROOT)}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.stderr.write(error.stdout or "")
        sys.stderr.write(error.stderr or "")
        raise SystemExit(error.returncode) from None
