# AGENTS.md — Contributor & Development Guide

This repository contains **Portal Sensory**, a bridge enabling AI agents to perceive and interact with the physical world via a Meta Portal (`cipher`, Portal+, Android 10 / API 29).

This guide is designed for AI coding agents (Antigravity, Claude Code, Cursor, Copilot) assisting with development, debugging, and enhancements.

---

## 1. Project Architecture

The repository is structured as a lightweight multi-component system:

```
portal-sensory/
├── android/                 # Native Android app (Kotlin, Jetpack Compose)
│   ├── app/src/main/java/dev/portalsensory/
│   │   ├── camera/          # Camera2 API capture engine (preview & full resolution)
│   │   ├── audio/           # 16kHz PCM audio recorder & real-time FFT engine
│   │   ├── video/           # Hardware-accelerated MediaCodec H.264 recorder
│   │   ├── server/          # Embedded NanoHTTPD server (port 8765)
│   │   ├── ui/              # Fullscreen Compose HUD & audio visualizer
│   │   └── MainActivity.kt  # App lifecycle & service management
├── mcp-server/              # Model Context Protocol (MCP) server (Node.js ESM)
│   ├── index.js             # stdio transport & tool declarations
│   └── package.json         # @modelcontextprotocol/sdk dependency
├── skills/portal-sensory/   # Antigravity skill playbook (operational instructions)
│   └── SKILL.md             # Sensory token economics, LiDAR audit, acoustic diagnostics
└── scripts/                 # Automation scripts
    ├── build.sh             # Compiles debug APK and outputs to dist/
    └── deploy.sh            # Installs APK via ADB, forwards port 8765, launches app
```

---

## 2. Environment & Prerequisites

* **Java:** OpenJDK 17 (recommended: `/opt/homebrew/opt/openjdk@17` on macOS).
* **Android SDK:** `compileSdk = 35`, `minSdk = 28`, `targetSdk = 29` (Android 10).
* **Target Hardware:** Meta Portal+ / standard Portal (`cipher`), running Android 10 (API 29).
* **Node.js:** `>= 18` (ESM module support).
* **ADB:** Required in `$PATH` for USB-C device deployment and port forwarding.

---

## 3. Essential Workflows & Commands

### Building the Android App
Always use the provided build script or Gradle wrapper:
```bash
# Automated build (outputs to dist/portal-sensory.apk)
./scripts/build.sh

# Or directly via Gradle
cd android && ./gradlew :app:assembleDebug
```

### Deploying & Starting on Device
To install the APK on a connected Portal, grant camera/mic permissions, forward ports, and launch:
```bash
./scripts/deploy.sh
```

### Running the MCP Server
```bash
cd mcp-server
npm install
node index.js
```

### Verifying Device HTTP Endpoints (Port 8765)
Once the app is running on device (with `adb forward tcp:8765 tcp:8765` active):
```bash
# 1. Telemetry & Sensor status
curl http://127.0.0.1:8765/status

# 2. Camera snapshots
curl "http://127.0.0.1:8765/capture/frame?res=small" -o test_preview.jpg
curl "http://127.0.0.1:8765/capture/frame?res=full" -o test_full.jpg

# 3. Audio recording (WAV)
curl -X POST "http://127.0.0.1:8765/capture/audio?duration=2" -o test_audio.wav

# 4. Video recording (MP4)
curl -X POST "http://127.0.0.1:8765/capture/video?duration=3" -o test_video.mp4

# 5. HUD banner overlay
curl -X POST -H "Content-Type: application/json" \
  -d '{"message":"AGENT ONLINE","subtext":"Testing overlay","color":"#00E5FF"}' \
  http://127.0.0.1:8765/display/overlay
```

---

## 4. Key Constraints & Design Principles

1. **Hardware & API Level Limit (API 29):**
   * The Meta Portal runs Android 10 (API level 29). **Do not use Android APIs introduced in API 30+** without runtime version checks (`Build.VERSION.SDK_INT`).
2. **Lean Dependencies:**
   * Keep Android dependencies minimal: Compose for HUD UI, NanoHTTPD for the embedded lightweight server, standard Android `Camera2`, `AudioRecord`, and `MediaCodec`.
   * MCP server uses `@modelcontextprotocol/sdk` and standard Node.js APIs (`fetch`, `child_process`, `fs`). Avoid adding heavy runtime dependencies.
3. **Dual Transport Architecture:**
   * Default transport is USB-C ADB forwarding: `http://127.0.0.1:8765`.
   * Fallback transport is Wi-Fi LAN: configured via `PORTAL_BASE_URL="http://<portal-ip>:8765"`.
4. **Privacy & Hygiene Invariants:**
   * **Never commit captured media files** (`.jpg`, `.wav`, `.mp4`) or files under `mcp-server/captures/`.
   * **Never hardcode personal paths**, local LAN IP addresses, or internal hostnames.
   * Keep APK binaries out of git tracking; release binaries belong in GitHub Release tags.
