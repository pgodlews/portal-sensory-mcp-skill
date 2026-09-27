# Portal Sensory: Eyes, Ears & Physical HUD for AI Agents

> Turn your Meta Portal (`cipher`, Portal+, Android 10 / API 29) into an autonomous physical observer, acoustic sensor, and interactive workbench HUD for AI agents.

## Overview

When developing hardware, embedded microcontrollers, robotics, or spatial computing pipelines (like LiDAR SLAM), an AI agent is normally blind and deaf to physical reality. **Portal Sensory** bridges this gap:

* **Eyes (Dual-Resolution Vision):**
  * **`preview` (640×360):** Ultra-fast (<40ms), low-token JPEG snapshots for presence detection, rapid iteration, and coarse state verification.
  * **`full` (1280×720):** High-detail JPEG captures for OCR on multimeter/oscilloscope screens, circuit inspection, and aligning LiDAR point clouds with real-world architecture.
* **Ears (Acoustic Diagnostics):**
  * 16 kHz 16-bit PCM WAV captures via the Portal's 4-mic beamforming array to detect relay clicks, stepper motor whine/stalls, and buzzer frequencies.
* **Motion (Video Recording):**
  * Hardware-encoded H.264 MP4 video clips via in-process `MediaCodec` + `MediaMuxer`.
* **Interactive HUD Console:**
  * The Portal's 14-inch display presents a live camera preview, real-time multi-band audio frequency histogram & dB peak meter, and agent status banners.
* **MCP Integration:**
  * Comes with a native Node.js Model Context Protocol (MCP) server providing standard tools directly into Antigravity, Claude, and Gemini.

---

## Architecture

```
┌────────────────────────────────────────────────────────┐
│  Host Machine (Mac / Antigravity / Agent)              │
│                                                        │
│  [ Antigravity Skill: portal-sensory ]                 │
│         │ (reasoning & workflows)                      │
│         ▼                                              │
│  [ Node.js MCP Server: portal-sensory-mcp ]            │
│    Tools:                                              │
│      • portal_capture_frame(resolution: "preview"|"full")
│      • portal_capture_audio(duration_seconds: 1..15)   │
│      • portal_capture_video(duration_seconds: 1..15)   │
│      • portal_get_status()                             │
│      • portal_display_overlay(message, subtext, color) │
└───────────────┬────────────────────────────────────────┘
                │ ADB Forward (tcp:8765) or Local Wi-Fi LAN
┌───────────────▼────────────────────────────────────────┐
│  Meta Portal+ (Android 10 / API 29)                    │
│                                                        │
│  [ portal-sensory Native Android App ]                 │
│    ├── UI: Fullscreen Camera + Audio Spectrum HUD      │
│    ├── Camera2 Engine (Live TextureView + Snapshot)    │
│    ├── Audio Engine (16kHz PCM Buffer + Real-time FFT) │
│    ├── Video Engine (MediaCodec H.264 + MediaMuxer)    │
│    └── Embedded HTTP Server (Port 8765)                │
└────────────────────────────────────────────────────────┘
```

---

## Quick Start

### 1. Build & Deploy to Portal
Connect your Portal via USB-C (ensure ADB is enabled):
```bash
./scripts/build.sh
./scripts/deploy.sh
```

### 2. Verify On-Device Endpoints
The deploy script forwards `tcp:8765` over ADB:
```bash
# Check device & sensor telemetry
curl http://127.0.0.1:8765/status

# Capture a preview frame (640x360, ~25 KB)
curl "http://127.0.0.1:8765/capture/frame?res=small" -o preview.jpg

# Capture full resolution (1280x720, ~200 KB)
curl "http://127.0.0.1:8765/capture/frame?res=full" -o full.jpg

# Record 3 seconds of ambient audio
curl -X POST "http://127.0.0.1:8765/capture/audio?duration=3" -o audio.wav

# Record a 5-second MP4 video clip
curl -X POST "http://127.0.0.1:8765/capture/video?duration=5" -o video.mp4

# Update the on-screen HUD banner
curl -X POST -H "Content-Type: application/json" \
  -d '{"message":"LIDAR CALIBRATION","subtext":"Cross-referencing point cloud","color":"#00E5FF"}' \
  http://127.0.0.1:8765/display/overlay
```

---

## MCP Server Setup

Add to your `mcp_config.json` (e.g. `~/.gemini/config/mcp_config.json` or Claude Desktop configuration):

```json
{
  "mcpServers": {
    "portal-sensory": {
      "command": "node",
      "args": [
        "/path/to/portal-sensory/mcp-server/index.js"
      ]
    }
  }
}
```
*(Replace `/path/to/portal-sensory` with the absolute path to your cloned repository.)*

The MCP server automatically executes `adb forward tcp:8765 tcp:8765` if connected via USB-C.

---

## Antigravity Skill

The skill playbook is located at:
[`skills/portal-sensory/SKILL.md`](skills/portal-sensory/SKILL.md)

To install it for Antigravity:
```bash
mkdir -p ~/.gemini/antigravity/skills/portal-sensory
cp skills/portal-sensory/SKILL.md ~/.gemini/antigravity/skills/portal-sensory/
```

It teaches the agent:
1. **Token Economics:** When to use preview vs full resolution.
2. **LiDAR SLAM Verification:** Heuristics to detect ghost reflection points, acoustic absorption voids, and double-wall SLAM registration errors against physical camera frames.
3. **Acoustic Diagnostics:** How to detect motor stalls, relay triggers, and buzzer frequencies.

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
