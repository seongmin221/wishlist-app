#!/bin/bash
set -euo pipefail

# Print only the new device UUID on stdout; download/status messages belong on stderr.
runtime_version=${1:?Usage: prepare-ci-simulator.sh <iOS version> <device type identifier>}
device_type=${2:?Usage: prepare-ci-simulator.sh <iOS version> <device type identifier>}

find_runtime() {
    xcrun simctl list runtimes --json | python3 -c '
import json, sys
version = sys.argv[1]
matches = [r for r in json.load(sys.stdin)["runtimes"]
           if r.get("version") == version and r.get("isAvailable")
           and r["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]
if not matches:
    sys.exit(1)
print(matches[0]["identifier"])
' "$runtime_version"
}

if ! runtime_id=$(find_runtime); then
    xcodebuild -downloadPlatform iOS -buildVersion "$runtime_version" >&2
    if ! runtime_id=$(find_runtime); then
        echo "iOS $runtime_version runtime is unavailable after download" >&2
        exit 1
    fi
fi

xcrun simctl create "Wishlist CI iOS $runtime_version" "$device_type" "$runtime_id"
