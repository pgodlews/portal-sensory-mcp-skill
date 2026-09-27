#!/bin/bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
apk_path="$repo_dir/dist/portal-sensory.apk"

if [ ! -f "$apk_path" ]; then
  echo "Building APK first..."
  "$repo_dir/scripts/build.sh"
fi

echo "Installing APK to device..."
adb install -r "$apk_path"

echo "Granting permissions..."
adb shell pm grant dev.portalsensory android.permission.CAMERA || true
adb shell pm grant dev.portalsensory android.permission.RECORD_AUDIO || true

echo "Setting up ADB port forwarding (tcp:8765 -> tcp:8765)..."
adb forward tcp:8765 tcp:8765

echo "Starting Portal Sensory..."
adb shell am start -n dev.portalsensory/.MainActivity

echo "Ready! Test with:"
echo "curl http://127.0.0.1:8765/status"
