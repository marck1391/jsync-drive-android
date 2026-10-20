# jsync Drive for Android

Your jsync Drive as storage on your phone, mounted the same way `jsync mount drive` does it on a desktop: the same NATS hub
(an embedded *leaf* node), the same signed handshake with X3DH + Double Ratchet, the same end-to-end encrypted folders.

## What it does

| Feature | How |
|---|---|
| **Drive as storage** | A `DocumentsProvider` (SAF): it shows up in "Files" and in every app's file picker. Range reads on demand (like Cloud Filter placeholders on Windows); create / write / rename / move / delete (deletes go to the Drive's trash); search; remote changes refresh the open folder. |
| **Browse and upload** | A Compose app: browse with image previews (grid or list), upload, new folder, rename, delete, open with another app, share, download. Folder colours, pins, and sharing a folder with another person. Also a target for Android's "Share" (to the Drive, or to another device). |
| **Encrypted folders** | Shown locked until you enter the vault password (checked on the phone, never sent). Read and write inside once open; they can lock themselves again (30 min / 4 h / never). |
| **Send to other devices** | Direct WebRTC between your devices (same protocol as the web app): a notification with Accept / Decline, trusted devices, progress and cancel. Tapping a request opens a window with a preview of the file (or its type icon) and the actions for it; received files go to `Downloads/jsync` (or Pictures / Movies / Music by type). |
| **Synced folder** | A folder on the phone (picked with the system picker) kept equal to a Drive folder, both ways. If both sides change the same file, both versions are kept; an empty or unreadable side never deletes the other (50 % limit). |
| **Updates from your own Drive** | The app looks for a newer build in `Apps/jsync-android` of your Drive, checks its SHA-256, and installs it. `publish-update.ps1` builds, signs and uploads one. |

English and Spanish, light and dark.

## How it is built

```
Kotlin app (Compose, DocumentsProvider, foreground service)
        │  HTTP on 127.0.0.1 + Bearer token, events over SSE
        ▼
libjsyncnode.so  = static Go node (no cgo or gomobile), a child process of the app
        ├─ embedded NATS leaf node ──▶ hub (jsyncd)
        ├─ mountfs.Client + VFS (tree, block cache, staged writes, encrypted folders)
        └─ P2P (pion/webrtc) + signalling over the Drive API's WebSocket
```

The Go code lives in the main jsync repository: `internal/androidnode` (node, local API, P2P), `cmd/jsyncnode` (the executable),
`internal/mountfs/vfs.go` (the "file system without a kernel" the API uses). Only the app is here.

## Build

```powershell
.\build-node.ps1                 # builds the Go node (arm64-v8a and x86_64) into app/src/main/jniLibs
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
.\gradlew.bat assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat testDebugUnitTest  # sync decisions (SyncPlanTest)
```

`build-node.ps1` expects the jsync Go repository in `..\filesharer` (not included here).

AGP 8.7.3 · Kotlin 2.1.0 · Gradle 8.11.1 · compileSdk 35 · minSdk 26.

Release builds are signed with `keystore/debug.keystore` (git-ignored). Keep a private copy: updates only install over an app
signed with the same key.

## Testing

- Go side (in the jsync repository): `go test ./internal/androidnode` runs a real hub and Drive API in-process: login, mount,
  read/write, events, an encrypted folder, P2P over real WebRTC.
- Interop with the web app: a real Chrome running the web app's own P2P modules exchanged files in both directions with the node (SHA-256 checked).
- The app itself was run on an x86_64 emulator against a local hub and a real server.

## Known limits / not verified

- **Real arm64**: only x86_64 (the emulator) was run. The arm64 node builds, but it was not run on a phone yet.
- **Server**: the Drive API change that lets device tokens register in `/api/v1/p2p/devices` and `/ice` must be deployed on
  `jsyncd` for the phone to register and list devices; without it, "Devices" shows a 403 error.
- **P2P**: without a TURN server it only works on the same network (or with reachable IPs); there is no Web Push (a phone without
  Google Services), so a request arrives while the foreground service keeps the socket open. mDNS candidates between a real phone
  and a browser: untested.
- **Synced folder**: SAF has no reliable change watching; local changes are noticed when the app opens, on "Sync now", from the
  `ContentObserver` when the provider reports them, and every 5 min while the service runs. Empty folders and their deletion are not propagated.
- Encrypted files larger than 256 MB and Drives with encryption at rest (`storage key`) are not supported in the Android node.
- No battery or mobile-data awareness: it syncs over whatever network there is.
- Protecting or unprotecting a folder (which needs client-side crypto and the vault setup) is not in the app; use the web app.
