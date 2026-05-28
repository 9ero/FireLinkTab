# FireLinkTab

App Android que convierte un **Fire TV / Android TV** en un receptor de pantalla al que cualquier Chrome/Brave de escritorio puede transmitir sin instalar ninguna extensión.

**FireLinkTab** = *Fire* (TV + suena a *free*) + *Link* (enlace directo) + *Tab* (tab casting, comparte letras con *cast*)

> **Estado:** ✅ Funcional en Chrome/Brave de escritorio (Windows, macOS, Linux).
> ❌ Navegadores móviles no soportados (`getDisplayMedia` es API de escritorio).

---

## Cómo funciona

```
Chrome/Brave (escritorio)          Fire TV (esta app)
┌─────────────────────────┐       ┌──────────────────────────────────────┐
│  1. Visita              │ HTTPS │  ControllerServer :8443              │
│  https://firetv:8443 ───┼───────┼→ Sirve la página de control         │
│                         │  WSS  │                                      │
│  2. Captura pantalla    │◄──────┼→ Señalización WebRTC (mismo puerto)  │
│     (getDisplayMedia)   │       │                                      │
│                         │ WebRTC│  WebView (receptor)                  │
│  3. Transmite ──────────┼───────┼→ Muestra el stream en pantalla       │
└─────────────────────────┘       └──────────────────────────────────────┘
```

El dispositivo también se anuncia como receptor Cast en la red local (mDNS + SSDP + DIAL), aunque el botón Cast nativo de Chrome requiere un certificado firmado por Google que esta app no tiene.

---

## Uso

### Requisitos
- Fire TV / Android TV en la misma red WiFi que el ordenador
- Chrome o Brave en escritorio (Windows, macOS, Linux)

### 1 — Instalar la app en el Fire TV

Activa ADB en el Fire TV: `Ajustes → Mi Fire TV → Opciones de desarrollador → Depuración ADB: ON`

```bash
adb connect <ip-del-firetv>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

Lanza la app desde el launcher del Fire TV. La pantalla mostrará la IP y los servicios activos.

### 2 — Primera visita desde Chrome (aceptar certificado)

Abre Chrome o Brave y ve a:
```
https://<ip-del-firetv>:8443
```

Chrome mostrará **"Tu conexión no es privada"** porque el certificado es autofirmado por la propia app.
Haz clic en **Avanzado → Acceder a \<ip\> (sitio no seguro)**.

> Esta advertencia aparece una sola vez. Chrome recuerda la excepción para ese host.

### 3 — Transmitir

1. Haz clic en **Compartir pantalla**
2. Selecciona una pestaña, ventana o pantalla completa
3. El contenido aparece en el Fire TV al instante

Para detener: haz clic en **Detener** en la página, cierra la pestaña que estabas compartiendo, o presiona **Atrás** en el mando del Fire TV.

---

## Compilar desde el código fuente

### Requisitos

| Herramienta | Versión | Notas |
|---|---|---|
| JDK | 17 | `sudo apt install openjdk-17-jdk` |
| Android SDK | 34 | Ver configuración abajo |
| ADB | cualquiera | Incluido en platform-tools del SDK |

### Android SDK (primera vez)

```bash
# Descarga las command-line tools desde developer.android.com/studio
# Extrae en ~/Android/cmdline-tools/latest/

export ANDROID_HOME=$HOME/Android
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

yes | sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

### Compilar e instalar

```bash
export ANDROID_HOME=$HOME/Android
./gradlew assembleDebug
adb connect <ip-del-firetv>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Arquitectura interna

La app levanta seis servidores al arrancar:

| Puerto | Protocolo | Servidor | Propósito |
|---|---|---|---|
| 8008 | HTTP | `DialServer.kt` | Protocolo DIAL (descripción de dispositivo, estado de apps) |
| 8080 | HTTP | `WebServer.kt` | Sirve `receiver.html` al WebView interno |
| 8081 | WS plano | `SignalingServer.kt` | Señalización WebRTC para el receptor (red interna) |
| 8443 | HTTPS + WSS | `ControllerServer.kt` | Página de control + señalización WebRTC para Chrome |
| 9009 | TLS | `CastV2Server.kt` | Protocolo Cast v2 (bloqueado por CA de Google) |
| 1900 UDP | Multicast | `SsdpServer.kt` | SSDP — descubrimiento DIAL de Chrome |

> El puerto original 8009 para Cast v2 fue desplazado a 9009 porque el servicio nativo de Amazon (`com.amazon.dialservice`) ocupa el 8009 en los Fire TV.

### Flujo de señalización WebRTC

```
Chrome (controller)          Fire TV
ws → ControllerServer:8443   WebView (receiver)
     │                       ws → SignalingServer:8081
     │← relay (MainActivity) →│
     │  {offer/answer/ice}    │
     └── WebRTC directo ──────┘
```

La `MainActivity` actúa como puente entre el `SignalingServer` (WS) y el `ControllerServer` (WSS), reenviando los mensajes de señalización entre el receptor y el controlador.

### Archivos clave

| Archivo | Función |
|---|---|
| `MainActivity.kt` | Orquesta los 6 servidores, aloja el WebView receptor, relay de señalización |
| `ControllerServer.kt` | HTTPS + WSS en el mismo puerto — HTTP sirve la página, WS hace la señalización |
| `SignalingServer.kt` | WS plano para el WebView interno (cleartext permitido por configuración) |
| `WebServer.kt` | HTTP — sirve `receiver.html` al WebView |
| `CastDiscovery.kt` | mDNS `_googlecast._tcp` (NsdManager) |
| `SsdpServer.kt` | SSDP UDP multicast para descubrimiento DIAL |
| `DialServer.kt` | Protocolo DIAL completo (device-desc.xml, app status, launch) |
| `CastV2Server.kt` | Protocolo Cast v2 completo (auth, heartbeat, receiver) |
| `CertUtils.kt` | Certificado RSA-2048 autofirmado. Dos factories: una para Cast v2 (BouncyCastle TLS), otra para HTTPS (provider nativo de Android, necesario para TLS 1.3) |
| `Proto.kt` | Codificador/decodificador protobuf manual (sin plugin de Gradle) |

---

## Limitaciones conocidas

### Navegadores móviles
`getDisplayMedia()` no está disponible en Chrome para Android, Safari Mobile ni ningún navegador móvil. Es una restricción de la API del navegador, no de esta app.

### Advertencia de certificado
El certificado TLS es autofirmado y generado en tiempo de ejecución. Chrome muestra una advertencia la primera vez. Esto es esperable — el certificado protege la conexión pero no está firmado por una CA pública.

### Cast v2 (botón nativo de Chrome)
El botón Cast nativo de Chrome descubre el dispositivo (aparece en el diálogo con los demás Chromecasts) pero no puede conectarse porque Google valida el certificado de dispositivo contra su propia CA. Este proyecto implementa el protocolo completo pero carece del certificado de dispositivo emitido por Google.

### LAN únicamente
La señalización y el stream WebRTC usan candidatos ICE locales. Funciona en la misma red local; no funciona a través de internet sin un servidor TURN.

---

## Diagnóstico

```bash
# Ver todos los servidores en tiempo real
adb logcat -s CastDiscovery:V DialServer:V CastV2Server:V SsdpServer:V \
           WebServer:V SignalingServer:V ControllerServer:V ReceiverJS:V Relay:V

# Ver solo la señalización WebRTC
adb logcat -s SignalingServer:V ControllerServer:V ReceiverJS:V Relay:V
```

Logs esperados al arrancar:
```
I/SignalingServer: Signaling server (WS) on :8081
I/WebServer:       Web server (HTTP) on :8080
I/DialServer:      DIAL server on :8008
I/SsdpServer:      SSDP listening on 239.255.255.250:1900
I/CastDiscovery:   mDNS registered: FireCast TV
I/ControllerServer: Controller server (HTTPS+WSS) on :8443
I/ReceiverJS:       Receiver WS connected
I/ReceiverJS:       Esperando controlador…
```

---

## Proyecto relacionado

## Licencia

[PolyForm Noncommercial 1.0.0](LICENSE) — uso personal y doméstico libre. Uso comercial requiere acuerdo con el autor: juan.fernadez.araya@gmail.com

---

## Proyecto relacionado

[firecast](../firecast) — solución funcional con extensión de Chrome (el botón Cast nativo funciona; requiere instalar la extensión una vez).
