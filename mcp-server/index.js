#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { exec } from "child_process";
import { promisify } from "util";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const execAsync = promisify(exec);
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const CAPTURES_DIR = path.join(__dirname, "captures");

if (!fs.existsSync(CAPTURES_DIR)) {
  fs.mkdirSync(CAPTURES_DIR, { recursive: true });
}

const DEFAULT_BASE_URL = process.env.PORTAL_BASE_URL || "http://127.0.0.1:8765";

let portalBaseUrl = DEFAULT_BASE_URL;

async function ensureAdbForward() {
  if (portalBaseUrl.includes("127.0.0.1") || portalBaseUrl.includes("localhost")) {
    try {
      await execAsync("adb forward tcp:8765 tcp:8765");
    } catch (e) {
      // ignore if adb not found or already forwarded
    }
  }
}

async function fetchFromPortal(endpoint, options = {}) {
  await ensureAdbForward();
  const url = `${portalBaseUrl}${endpoint}`;
  try {
    const res = await fetch(url, options);
    if (!res.ok) {
      throw new Error(`Portal returned HTTP ${res.status}: ${res.statusText}`);
    }
    return res;
  } catch (err) {
    throw new Error(`Could not connect to Portal at ${url}: ${err.message}`);
  }
}

const server = new Server(
  {
    name: "portal-sensory-mcp",
    version: "0.1.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: "portal_get_status",
        description: "Check connectivity and sensor telemetry (camera state, ambient audio RMS & dB levels) on the Meta Portal.",
        inputSchema: {
          type: "object",
          properties: {},
        },
      },
      {
        name: "portal_capture_frame",
        description: "Capture a photo from the Meta Portal camera. Returns an image block directly into your context for visual reasoning and saves to disk.",
        inputSchema: {
          type: "object",
          properties: {
            resolution: {
              type: "string",
              enum: ["preview", "full"],
              description: "Use 'preview' (640x360) for fast iteration and low token usage; use 'full' (1280x720) for reading text, fine circuit detail, or aligning with LiDAR point clouds.",
              default: "preview",
            },
            save_filename: {
              type: "string",
              description: "Optional custom filename to save in the captures directory.",
            },
          },
        },
      },
      {
        name: "portal_capture_audio",
        description: "Record a snippet of ambient audio from the Meta Portal 4-mic beamforming array. Useful for detecting motor hum, relay clicks, and buzzer frequencies.",
        inputSchema: {
          type: "object",
          properties: {
            duration_seconds: {
              type: "number",
              description: "Duration to record in seconds (1 to 15, default 3).",
              default: 3,
            },
            save_filename: {
              type: "string",
              description: "Optional custom filename to save in the captures directory.",
            },
          },
        },
      },
      {
        name: "portal_capture_video",
        description: "Record an H.264 MP4 video clip from the Meta Portal camera feed. Useful for inspecting dynamic physical processes or motion.",
        inputSchema: {
          type: "object",
          properties: {
            duration_seconds: {
              type: "number",
              description: "Duration of the video clip in seconds (1 to 15, default 5).",
              default: 5,
            },
            save_filename: {
              type: "string",
              description: "Optional custom filename to save in the captures directory.",
            },
          },
        },
      },
      {
        name: "portal_display_overlay",
        description: "Display a custom notification or status banner on the Portal's 14-inch screen to communicate with the human in the room.",
        inputSchema: {
          type: "object",
          properties: {
            message: {
              type: "string",
              description: "Primary headline text to show on screen.",
            },
            subtext: {
              type: "string",
              description: "Secondary explanatory details.",
            },
            color: {
              type: "string",
              description: "Hex color code for the HUD border and headline (e.g. #00E5FF for cyan, #00E676 for green, #FF5252 for alert red).",
              default: "#00E5FF",
            },
          },
          required: ["message"],
        },
      },
    ],
  };
});

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    switch (name) {
      case "portal_get_status": {
        const res = await fetchFromPortal("/status");
        const json = await res.json();
        return {
          content: [
            {
              type: "text",
              text: JSON.stringify(json, null, 2),
            },
          ],
        };
      }

      case "portal_capture_frame": {
        const resolution = args?.resolution === "full" ? "full" : "small";
        const res = await fetchFromPortal(`/capture/frame?res=${resolution}`);
        const buffer = Buffer.from(await res.arrayBuffer());
        const base64 = buffer.toString("base64");

        const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
        const filename = args?.save_filename || `frame_${resolution}_${timestamp}.jpg`;
        const filePath = path.join(CAPTURES_DIR, filename);
        fs.writeFileSync(filePath, buffer);

        return {
          content: [
            {
              type: "image",
              data: base64,
              mimeType: "image/jpeg",
            },
            {
              type: "text",
              text: `Captured ${resolution} photo (${(buffer.length / 1024).toFixed(1)} KB).\nSaved to: ${filePath}`,
            },
          ],
        };
      }

      case "portal_capture_audio": {
        const duration = Math.max(1, Math.min(15, args?.duration_seconds || 3));
        const res = await fetchFromPortal(`/capture/audio?duration=${duration}`, { method: "POST" });
        const buffer = Buffer.from(await res.arrayBuffer());

        const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
        const filename = args?.save_filename || `audio_${duration}s_${timestamp}.wav`;
        const filePath = path.join(CAPTURES_DIR, filename);
        fs.writeFileSync(filePath, buffer);

        return {
          content: [
            {
              type: "text",
              text: `Recorded ${duration}s audio (${(buffer.length / 1024).toFixed(1)} KB, 16kHz 16-bit PCM WAV).\nSaved to: ${filePath}`,
            },
          ],
        };
      }

      case "portal_capture_video": {
        const duration = Math.max(1, Math.min(15, args?.duration_seconds || 5));
        const res = await fetchFromPortal(`/capture/video?duration=${duration}`, { method: "POST" });
        const buffer = Buffer.from(await res.arrayBuffer());

        const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
        const filename = args?.save_filename || `video_${duration}s_${timestamp}.mp4`;
        const filePath = path.join(CAPTURES_DIR, filename);
        fs.writeFileSync(filePath, buffer);

        return {
          content: [
            {
              type: "text",
              text: `Recorded ${duration}s H.264 video clip (${(buffer.length / 1024).toFixed(1)} KB, 1280x720 MP4).\nSaved to: ${filePath}`,
            },
          ],
        };
      }

      case "portal_display_overlay": {
        const payload = {
          message: args?.message || "AI AGENT NOTICE",
          subtext: args?.subtext || "",
          color: args?.color || "#00E5FF",
        };
        const res = await fetchFromPortal("/display/overlay", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        });
        const json = await res.json();
        return {
          content: [
            {
              type: "text",
              text: `Portal display updated: "${payload.message}" (${json.status})`,
            },
          ],
        };
      }

      default:
        throw new Error(`Unknown tool: ${name}`);
    }
  } catch (error) {
    return {
      isError: true,
      content: [
        {
          type: "text",
          text: `Error executing ${name}: ${error.message}`,
        },
      ],
    };
  }
});

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("Portal Sensory MCP server running on stdio");
}

main().catch((err) => {
  console.error("Fatal error in MCP server:", err);
  process.exit(1);
});
