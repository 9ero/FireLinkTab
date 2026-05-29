# FireLink

App Android que convierte un **Fire TV / Android TV** en un receptor de pantalla al que cualquier Chrome/Brave de escritorio puede transmitir sin instalar ninguna extensión.

**FireLink** = *Fire* (TV + suena a *free*) + *Link* (enlace directo con cualquier app del ordenador)

## Compatibilidad

| Navegador | Escritorio | Móvil |
|---|---|---|
| Chrome | ✅ | ❌ |
| Brave | ✅ | ❌ |
| Edge | ✅ | ❌ |
| Firefox | 🔲 sin probar | ❌ |
| Safari | 🔲 sin probar | ❌ |

> **¿Por qué no funciona en móvil?** `getDisplayMedia()` es una API exclusiva de navegadores de escritorio. No es una limitación de FireLink — ninguna app puede capturar pantalla desde el navegador en iOS o Android.
>
> El nombre **FireLink** no referencia Chrome deliberadamente: funciona en cualquier navegador de escritorio que soporte `getDisplayMedia`.

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
- Cualquier navegador de escritorio con soporte `getDisplayMedia`: Chrome, Brave, Edge, Firefox o Safari

### 1 — Instalar la app en el Fire TV

Activa ADB en el Fire TV: `Ajustes → Mi Fire TV → Opciones de desarrollador → Depuración ADB: ON`

```bash
adb connect <ip-del-firetv>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

Lanza la app desde el launcher del Fire TV. La pantalla mostrará la IP y los servicios activos.

### 2 — (Opcional) Eliminar el aviso de certificado con mkcert

Por defecto la app usa un certificado autofirmado — el navegador muestra una advertencia de seguridad la primera vez. Con **mkcert** se elimina completamente:

**En tu computadora** (una sola vez por navegador):
```bash
# Instalar mkcert
brew install mkcert        # macOS
choco install mkcert       # Windows
apt install mkcert         # Linux (o descarga desde github.com/FiloSottile/mkcert)

mkcert -install            # instala la CA raíz en el navegador
mkcert 192.168.50.12       # genera cert para la IP del Fire TV
# genera: 192.168.50.12.pem  y  192.168.50.12-key.pem
```

**Copiar al Fire TV** (con ADB):
```bash
CERT_DIR="/sdcard/Android/data/dev.firecast.castv2/files"
adb push 192.168.50.12.pem     $CERT_DIR/cert.pem
adb push 192.168.50.12-key.pem $CERT_DIR/key.pem
```

Reinicia la app — la pantalla del Fire TV mostrará `🔒 Certificado mkcert — sin advertencias`.

> Si la IP del Fire TV cambia (DHCP), repite `mkcert <nueva-ip>` y el `adb push`.
> Para evitarlo, configura una reserva DHCP estática en tu router.

---

### 3 — Primera visita desde Chrome (aceptar certificado)

Abre Chrome o Brave y ve a:
```
https://<ip-del-firetv>:8443
```

Chrome mostrará **"Tu conexión no es privada"** porque el certificado es autofirmado por la propia app.
Haz clic en **Avanzado → Acceder a \<ip\> (sitio no seguro)**.

> Esta advertencia aparece una sola vez. Chrome recuerda la excepción para ese host.

### 3 — Transmitir

1. Haz clic en **Compartir pantalla**
2. Elige **Una ventana** o **Pantalla completa** — funciona con cualquier app del ordenador (juegos, streaming, editores…)
3. El contenido aparece en el Fire TV al instante

**Para incluir audio del sistema:**

| Sistema | Cómo activarlo |
|---|---|
| **Linux** | Comparte pantalla → haz clic en **Agregar audio del sistema** → en el selector del navegador elige **Monitor of Built-in Audio** (PulseAudio) o el monitor equivalente de PipeWire. Si eliges el micrófono por error, captura el micrófono. |
| **Windows — pantalla completa** | En el diálogo del navegador activa el checkbox **Compartir audio del sistema** antes de confirmar. |
| **Windows — ventana** | La captura de ventana no incluye audio en Windows. La página detecta automáticamente si tienes Stereo Mix o un cable virtual (VB-Audio); si no, muestra una guía para activarlo. |
| **macOS** | No disponible — `getDisplayMedia` en macOS no expone audio del sistema. |

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
`getDisplayMedia()` no está disponible en ningún navegador móvil (Chrome Android, Safari iOS, Firefox Android…). Es una restricción de la especificación W3C, no de esta app. La página muestra un aviso específico cuando detecta un dispositivo móvil.

### Audio del sistema en Linux
El botón "Agregar audio del sistema" usa `getUserMedia`, que abre el **selector de dispositivos de audio del navegador**. El usuario debe elegir explícitamente **Monitor of Built-in Audio** (PulseAudio) o el monitor equivalente en PipeWire. Si se selecciona el micrófono, se capturará el micrófono en lugar del sistema. La app no puede preseleccionar el dispositivo — el selector es controlado por el navegador.

### Audio del sistema en macOS
`getDisplayMedia` en macOS no expone audio del sistema. Requeriría una extensión de kernel de terceros (BlackHole, Soundflower) que el usuario debería instalar por separado.

### Advertencia de certificado
El certificado TLS es autofirmado y generado en tiempo de ejecución. Chrome muestra una advertencia la primera vez. Esto es esperable — el certificado protege la conexión pero no está firmado por una CA pública.

### Cast v2 (botón nativo de Chrome)
El botón Cast nativo de Chrome descubre el dispositivo (aparece en el diálogo con los demás Chromecasts) pero no puede conectarse porque Google valida el certificado de dispositivo contra su propia CA. Este proyecto implementa el protocolo completo pero carece del certificado de dispositivo emitido por Google.

### Calidad baja al inicio (primeros 20-40 segundos)
WebRTC arranca con una tasa de bits conservadora y la va aumentando a medida que su algoritmo de estimación de ancho de banda (BWE) mide la capacidad real del enlace. En LAN la calidad sube sola en ese intervalo hasta estabilizarse en alta resolución. Es comportamiento esperado, no un problema de la app.

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

[firecast](../firecast) — solución funcional con extensión de Chrome (el botón Cast nativo funciona; requiere instalar la extensión una vez).

---

## Licencia

[PolyForm Noncommercial 1.0.0](LICENSE) — uso personal y doméstico libre. Uso comercial requiere acuerdo con el autor: juan.fernadez.araya@gmail.com
