---
name: portal-sensory
description: >-
  Operational playbook for using Meta Portal as physical eyes, ears, and workbench HUD
  for software/hardware engineering, spatial computing, LiDAR verification, and embedded electronics.
---

# Portal Sensory Playbook: Eyes, Ears & Physical HUD for AI Agents

This skill teaches AI agents how to perceive and interact with the physical world using a Meta Portal+ sitting on the workbench or in the lab. Through the `portal-sensory-mcp` tools, the agent has access to optical camera snapshots, audio recordings, video clips, and an interactive heads-up display on the Portal's 14-inch screen.

---

## 1. Sensory Tools Reference

The agent interacts with the Portal through these MCP tools:

| Tool | Parameters | Primary Use Case |
|---|---|---|
| `portal_get_status` | none | Verify connectivity, active camera ID, and ambient mic dB level. |
| `portal_capture_frame` | `resolution: "preview" \| "full"`, `save_filename` | Capture photo. Returns an image block directly into the context window for visual reasoning. |
| `portal_capture_audio` | `duration_seconds: 1..15`, `save_filename` | Record ambient audio via 4-mic array. Useful for acoustic diagnostics. |
| `portal_capture_video` | `duration_seconds: 1..15`, `save_filename` | Record H.264 MP4 clip to observe dynamic physical behavior or motion. |
| `portal_display_overlay` | `message`, `subtext`, `color` | Update the on-screen banner to communicate status with the human in the room. |

---

## 2. Resolution Selection Heuristic (Token Economy)

Always select the right camera resolution based on the task:

### Use `"preview"` (640×360, ~500 tokens) for:
* **Rapid scene verification:** Checking if the user is at the desk, if a board is present, or if ambient lighting changed.
* **Coarse state detection:** Is the 3D printer head moving? Is an LED on or off?
* **Low token overhead:** Whenever multiple successive captures are needed during iterative loops.

### Use `"full"` (1280×720, high detail) for:
* **LiDAR & Spatial Ground-Truthing:** Aligning point cloud features with room architecture (window frames, wall corners, door trim).
* **Reading Displays:** Deciphering digits on multimeters, oscilloscopes, 7-segment displays, and OLED panels.
* **Component & Wiring Inspection:** Verifying resistor color codes, IC markings, and breadboard jumper positions.

---

## 3. Spatial & LiDAR Point Cloud Ground-Truthing

When working on LiDAR SLAM (e.g. Livox Mid-360), 3D reconstruction, or robot navigation, point clouds contain geometric ambiguities that can only be resolved by optical ground truth:

```
┌────────────────────────┐         ┌────────────────────────┐
│   LiDAR Point Cloud    │         │  Portal Camera Frame   │
│  (Abstract 3D X, Y, Z) │         │ (Optical Ground Truth) │
└───────────┬────────────┘         └───────────┬────────────┘
            │                                  │
            └─────────────► [ Agent ] ◄────────┘
                                │
               Cross-Modal Discrepancy Detection:
               • Phantom cluster behind wall -> Glass window / mirror
               • Void with no returns -> Matte black chair / acoustic foam
               • Double parallel walls -> SLAM odometry drift / slip
```

### Heuristic Steps:
1. **Fetch Optical Ground Truth:** Call `portal_capture_frame(resolution="full")` to capture the room.
2. **Overlay HUD Status:** Call `portal_display_overlay(message="LIDAR AUDIT IN PROGRESS", subtext="Comparing point cloud vs room geometry", color="#00E5FF")`.
3. **Compare Discrepancies:**
   * **Ghost Points:** If the point cloud contains a cluster floating outside the room boundary, check the photo for glass windows, reflective whiteboards, or mirrors in that direction (multi-path specular reflection).
   * **Absorption Voids:** If a known solid object (e.g. an office chair or curtain) has zero point returns, verify its color/material in the photo (matte black materials absorb 905nm/1550nm laser pulses).
   * **Registration Slips:** If parallel surfaces appear where the camera shows only a single physical wall, flag loop closure failure or wheel/IMU odometry drift.
   * **Extrinsic Orientation:** Confirm whether the scanner's point cloud coordinate axes ($+X$ forward, $+Z$ up) match the physical orientation seen by the Portal.

---

## 4. Acoustic Verification Playbook

The Portal's 4-mic beamforming array provides high-fidelity auditory feedback:

### Workflow:
1. Trigger action via software (e.g. send motor step command, trigger relay GPIO, flash firmware).
2. Immediately call `portal_capture_audio(duration_seconds=2)`.
3. Analyze sound:
   * **Relay Clicks:** Look for an impulsive transient spike within 50ms of the GPIO toggle.
   * **Stepper Motors:** High-pitched whine indicates motor stall (steps skipped); smooth harmonic tone indicates clean stepping.
   * **Buzzers / Beepers:** Count beep bursts to decode BIOS/firmware error status codes.

---

## 5. Workbench HUD Communication

The Portal's 14-inch screen is a shared visual interface between you and the human:

* When beginning an autonomous test sequence:
  ```json
  { "message": "TEST SEQUENCE RUNNING", "subtext": "Testing motor step calibration", "color": "#00E5FF" }
  ```
* When an error or anomaly is detected:
  ```json
  { "message": "HARDWARE ANOMALY", "subtext": "Relay failed to click; check 5V VCC line", "color": "#FF5252" }
  ```
* When a test passes:
  ```json
  { "message": "VERIFICATION PASSED", "subtext": "All 8 GPIO states confirmed optically", "color": "#00E676" }
  ```

---

## 6. Connectivity Troubleshooting

1. **Default Transport:** Over USB-C via ADB forwarding (`http://127.0.0.1:8765`). The MCP server automatically runs `adb forward tcp:8765 tcp:8765`.
2. **Wi-Fi LAN Fallback:** If ADB is disconnected, set `PORTAL_BASE_URL="http://<portal-ip>:8765"`.
3. **App Status:** If requests return connection refused, launch the app:
   ```bash
   adb shell am start -n dev.portalsensory/.MainActivity
   ```
