#!/usr/bin/env python3
"""Select an available iPhone for a declared runtime without changing devices."""

import json
import subprocess
import sys


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: select-ios-simulator.py RUNTIME_IDENTIFIER")
    result = subprocess.run(
        ["xcrun", "simctl", "list", "devices", "available", "--json"],
        check=True, capture_output=True, text=True, timeout=30,
    )
    devices = json.loads(result.stdout)["devices"].get(sys.argv[1], [])
    phones = [device for device in devices if device.get("isAvailable") and device.get("deviceTypeIdentifier", "").startswith("com.apple.CoreSimulator.SimDeviceType.iPhone-")]
    if not phones:
        raise SystemExit(f"No available iPhone for {sys.argv[1]}; install the declared runtime and device.")
    print(sorted(phones, key=lambda device: (device["name"], device["udid"]))[0]["udid"])


if __name__ == "__main__":
    main()
