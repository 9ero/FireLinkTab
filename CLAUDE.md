# FireLinkTab — Claude Context

## Project summary
**FireLinkTab** — Android app that lets Chrome/Brave on a **desktop** cast any tab or screen to a Fire TV via WebRTC — without any browser extension.
Name: Fire (TV + sounds like "free") + Link (direct connection) + Tab (tab casting, shares letters with "cast").
License: PolyForm Noncommercial 1.0.0 (free for personal use, commercial requires negotiation).
The user visits `https://<firetv-ip>:8443`, accepts the self-signed cert warning once, clicks "Compartir pantalla", picks a tab or screen, and it appears on the TV.

Companion project: `../firecast` (WebRTC approach that requires a browser extension for discovery).

## Status — WORKING on desktop browsers
- ✅ Confirmed: Chrome, Brave, Edge (desktop)
- ✅ Expected to work: Firefox, Safari (desktop) — not yet tested
- ✅ WebRTC stream renders on Fire TV
- ✅ Auto-returns to idle state when stream stops
- ❌ Mobile browsers (any): `getDisplayMedia()` is a W3C desktop-only API — not fixable at app level
- ❌ Cast v2 auth: device appears in Cast dialog but greyed out (Google CA wall)

## Browser compatibility rationale
FireLinkTab deliberately avoids referencing Chrome in its name. `getDisplayMedia` is
supported by all major desktop browsers (Chrome, Brave, Edge, Firefox, Safari).
The controller page detects mobile user agents and shows a specific error rather
than the generic cert warning.

## Architecture — six servers

```
Chrome/Brave (desktop)          Fire TV (this app)
  │                               │
  │  mDNS _googlecast._tcp ──────►│ CastDiscovery.kt   (NsdManager)
  │  SSDP UDP:1900 ───────────────►│ SsdpServer.kt      (multicast, DIAL discovery)
  │  DIAL HTTP:8008 ──────────────►│ DialServer.kt      (device description, app launch)
  │  Cast v2 TLS:9009 ────────────►│ CastV2Server.kt    (auth wall — Google CA blocks)
  │                               │
  │  HTTPS:8443 (controller) ─────►│ ControllerServer.kt (HTTPS + WSS on same port)
  │  └─ WSS:8443 (signaling) ─────►│                    (relays WebRTC offer/answer)
  │                               │
  │  WebRTC ──────────────────────►│ WebView            (receiver.html from WebServer)
  │                               │ WebServer.kt:8080   (HTTP, serves receiver.html)
  │                               │ SignalingServer:8081 (WS plain, receiver signaling)
```

## User flow (desktop)
1. Install app on Fire TV, launch it
2. Visit `https://<firetv-ip>:8443` in Chrome/Brave
3. First visit: Chrome shows "Your connection is not private" → click **Advanced → Proceed** (self-signed cert, one-time)
4. Click **Compartir pantalla** → pick tab, window, or screen
5. Stream appears on Fire TV fullscreen
6. When stream stops (or click Detener), Fire TV returns to idle status screen

## Ports
| Port | Protocol | Component | Purpose |
|---|---|---|---|
| 8008 | HTTP | DialServer.kt | DIAL discovery (device-desc.xml, app status) |
| 8080 | HTTP | WebServer.kt | Serves receiver.html to internal WebView |
| 8081 | WS (plain) | SignalingServer.kt | WebRTC signaling for receiver (cleartext OK, internal) |
| 8443 | HTTPS+WSS | ControllerServer.kt | Controller page + signaling for Chrome desktop |
| 9009 | TLS | CastV2Server.kt | Cast v2 protocol (auth blocked by Google CA) |
| 1900 | UDP multicast | SsdpServer.kt | SSDP — Chrome's DIAL discovery uses this |

> Port 8009 was moved to 9009 because Amazon Fire TV's `com.amazon.dialservice` occupies 8009.

## Key files
| File | What it does |
|---|---|
| `MainActivity.kt` | Wires all 6 servers, hosts WebView receiver, relay between SignalingServer and ControllerServer |
| `ControllerServer.kt` | Combined HTTPS + WSS on port 8443. HTTP requests → controller.html. WS upgrade → signaling relay |
| `SignalingServer.kt` | Plain WS on port 8081. Receiver WebSocket signaling (no TLS needed — internal device) |
| `WebServer.kt` | Plain HTTP on port 8080. Serves receiver.html to the internal WebView |
| `CastDiscovery.kt` | NsdManager mDNS. TXT records: id, ve=05, ca=4101, fn, md, st, rs, bs, ic |
| `SsdpServer.kt` | UDP multicast 239.255.255.250:1900. Responds to M-SEARCH for `dial-multiscreen-org:service:dial:1` |
| `DialServer.kt` | DIAL HTTP: device-desc.xml, app status GET/POST/DELETE. No external library |
| `CastV2Server.kt` | Full Cast v2: auth + heartbeat + CONNECT + GET_STATUS/LAUNCH |
| `CertUtils.kt` | BouncyCastle RSA-2048 self-signed cert. Two socket factories: `createServerSocket` (Cast v2) and `createHttpsServerSocket` (HTTPS — uses Android native TLS provider) |
| `Proto.kt` | Manual varint + length-delimited protobuf codec |

## Critical implementation notes

### BouncyCastle vs native TLS (important)
`SSLContext.getInstance("TLS")` can return BouncyCastle's JSSE implementation when `bcpkix` is in the classpath. BouncyCastle's TLS **cannot parse Chrome's TLS 1.3 Client Hello** → `ERR_SSL_VERSION_OR_CIPHER_MISMATCH`.

Fix: `CertUtils.createHttpsServerSocket()` explicitly picks Android's native provider (AndroidOpenSSL/Conscrypt) via `Security.getProviders()`. Used only by ControllerServer. CastV2Server continues using the default (BouncyCastle TLS works for Cast v2 because Chrome's Cast client sends a compatible handshake).

### Android cleartext traffic
Android 9+ blocks cleartext HTTP by default. `res/xml/network_security_config.xml` with `<base-config cleartextTrafficPermitted="true"/>` is required so the WebView can connect to:
- `http://ip:8080` (receiver.html served by WebServer)
- `ws://ip:8081` (plain WebSocket to SignalingServer)

### WebView receiver loading
The receiver WebView uses `loadDataWithBaseURL("http://ip:8080/", html, ...)` instead of `loadUrl("http://ip:8080/receiver")`. This avoids the race condition where the WebView requests the page before the WebServer socket is bound, and it also sets `location.hostname` = device IP so `ws://ip:8081` resolves correctly.

### Same port for HTTPS and WSS (port 8443)
ControllerServer handles both HTTP and WebSocket on the same TLS port. When a user accepts the self-signed cert for `https://ip:8443`, Chrome's cert exception also covers `wss://ip:8443` — no second warning needed.

### getDisplayMedia — desktop only
`navigator.mediaDevices.getDisplayMedia()` is not available on mobile browsers (Chrome for Android, Safari Mobile, etc.). This is a browser API limitation, not fixable at the app level.

### SSDP required for DIAL discovery
Chrome's `DialMediaSinkServiceImpl` uses SSDP (UDP multicast) to discover DIAL devices, not just mDNS. Without SsdpServer, the device appears as `cast:xxxx` (greyed out, auth fails) but not as `dial:xxxx` (clickable for specific apps like YouTube).

## Build
```bash
export ANDROID_HOME=$HOME/Android
./gradlew assembleDebug
adb connect <firetv-ip>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.firecast.castv2/.MainActivity

# Monitor all servers
adb logcat -s CastDiscovery:V DialServer:V CastV2Server:V SsdpServer:V \
           WebServer:V SignalingServer:V ControllerServer:V ReceiverJS:V Relay:V
```

## Dependency note
- BouncyCastle `bcpkix-jdk15on:1.70` — for X.509 cert generation only
- META-INF exclusions in build.gradle required (BC signing conflict)
- No WebSocket library — implemented manually with raw sockets

## Cast v2 auth wall (still blocked)
Chrome's media router (`CastMediaSinkServiceImpl`) connects to port 9009, does TLS, sends AUTH_CHALLENGE. We respond with a self-signed cert. Chrome rejects it (not signed by Google's Cast CA) → device stays greyed out in the Cast dialog. The WebRTC approach bypasses this entirely.

## Known limitations / future work
- Mobile casting: would require a different approach (no getDisplayMedia)
- HTTPS cert warning: could be eliminated with a proper cert via a local CA the user installs once, or via mDNS .local domain tricks
- Latency: WebRTC with no TURN server works on LAN only; ICE candidates are local-only
