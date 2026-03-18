# Friday A11y Service

Android Accessibility Service that turns a phone into a remotely controllable agent peripheral. Built for [OpenClaw](https://github.com/openclaw/openclaw) — gives AI agents eyes and hands on a real Android device.

## What It Does

- **Screen reading** — Full accessibility tree as JSON via HTTP API
- **UI automation** — Click, type, scroll, swipe, long press, navigate
- **Notification capture** — Real-time notification stream with history
- **Remote control** — Gateway relay for controlling the phone over the internet
- **Local API** — HTTP server on port 7333 for LAN access

## Architecture

```
Agent (Mac/Cloud)
    ↓ command
Relay Server (relay/server.mjs, port 7334)
    ↓ poll/result
A11y Service (Android, port 7333)
    ↓ accessibility APIs
Android UI
```

The A11y Service runs on the phone and exposes a local HTTP API. For remote access, the relay server acts as a command queue — the phone polls it, executes commands, and posts results back.

## Local HTTP API (port 7333)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/screen` | Full UI tree as JSON |
| GET | `/ping` | Health check |
| GET | `/currentApp` | Current foreground app & activity |
| GET | `/getNotifications?limit=20&package=...` | Recent notifications |
| GET | `/getActiveNotifications` | Currently active notifications |
| POST | `/click` | `{"text":"OK"}` or `{"x":540,"y":1200}` |
| POST | `/type` | `{"text":"hello"}` — type into focused field |
| POST | `/setText` | `{"text":"hello"}` — set text on any editable field |
| POST | `/longPress` | `{"x":540,"y":1200,"durationMs":1000}` |
| POST | `/scroll` | `{"direction":"down"}` (up/down/left/right) |
| POST | `/swipe` | `{"startX":540,"startY":1800,"endX":540,"endY":600,"durationMs":300}` |
| POST | `/back` | Press back button |
| POST | `/home` | Press home button |
| POST | `/recents` | Open recents |
| POST | `/notifications` | Pull down notification shade |
| POST | `/findText` | `{"text":"search term"}` — find nodes containing text |
| POST | `/waitForChange` | `{"timeoutMs":5000}` — block until UI changes |

## Relay Server

The relay (`relay/server.mjs`) runs on your Mac/server and bridges commands between the agent and the phone:

```bash
A11Y_RELAY_TOKEN=your-secret node relay/server.mjs
```

Endpoints for the agent (POST `/command`), phone polling (GET `/poll`), and notification stream (GET `/notifications`, GET `/notifications/wait`).

## Setup

1. Build & install the APK on your Android device
2. Enable the Accessibility Service in Android Settings
3. Enable Notification Listener access
4. (Optional) Configure gateway URL and token in the app for remote access

### Build

```bash
ANDROID_HOME=~/Library/Android/sdk JAVA_HOME=$(/usr/libexec/java_home) ./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- Android 7.0+ (API 24)
- Accessibility Service permission
- Notification Listener permission (for notification features)
- Network access (for relay/remote control)

## License

MIT
