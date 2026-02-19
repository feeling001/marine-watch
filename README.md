# Marine Watch — Wear OS BLE Client

A Wear OS application for Samsung Galaxy Watch 7 (Wear OS 4) that connects via
Bluetooth Low Energy to a **Marine Gateway ESP32** and displays real-time
navigation data on an always-on watch face.

## Displayed Data

| Tile  | Field | Source characteristic | Unit |
|-------|-------|-----------------------|------|
| STW   | Speed Through Water | `stw` in NavData | knots |
| DEPTH | Depth below transducer | `depth` in NavData | metres |
| COG   | Course Over Ground | `cog` in NavData | degrees |
| SOG   | Speed Over Ground | `sog` in NavData | knots |

Data is received via BLE NOTIFY from the Navigation service at 1 Hz.

---

## Project Structure

```
marine-watch/
├── Dockerfile                   # Android SDK build environment
├── docker-compose.yml           # Convenience wrappers
├── build.gradle                 # Root Gradle config
├── settings.gradle
├── gradlew                      # Gradle wrapper script
├── gradle/wrapper/
│   ├── gradle-wrapper.properties
│   └── gradle-wrapper.jar       # ← must be generated (see below)
└── app/
    ├── build.gradle             # App module — dependencies, SDK versions
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/marinewatch/app/
        │   ├── MainActivity.kt           # Entry point, permissions, ambient mode
        │   ├── MainViewModel.kt          # Lifecycle-safe BLE state holder
        │   ├── ble/
        │   │   ├── BleConstants.kt       # UUIDs, device name, timing
        │   │   ├── BleConnectionState.kt # Connection state enum
        │   │   └── BleManager.kt         # Scan → connect → notify → parse
        │   ├── data/
        │   │   └── NavData.kt            # JSON data model
        │   └── ui/
        │       └── MarineDisplay.kt      # Compose UI (interactive + ambient)
        └── res/
            ├── values/strings.xml
            └── mipmap-anydpi-v26/ic_launcher.xml
```

---

## Prerequisites

- **Docker** (Desktop or Engine) with Compose v2
- A **Samsung Galaxy Watch 7** with developer mode enabled
- The **Marine Gateway ESP32** advertising as `MarineGateway` with PIN `123456`

---

## Build Instructions

### Step 0 — Generate the Gradle wrapper JAR (once only)

The `gradle-wrapper.jar` binary is not included in the repository.
Run this once to generate it:

```bash
docker run --rm -v "$(pwd)":/workspace -w /workspace \
    gradle:8.6-jdk17 gradle wrapper
```

This creates `gradle/wrapper/gradle-wrapper.jar`.

### Step 1 — Build the Docker image (once, then cached)

```bash
docker compose build
```

### Step 2 — Build the debug APK

```bash
docker compose run --rm build
```

The APK is produced at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Optional — Build a release APK

```bash
docker compose run --rm build-release
```

> The release build uses the debug signing key by default.
> For a production-signed APK, see the *Signing* section below.

---

## Install on the Galaxy Watch 7

### Enable developer options on the watch

1. **Settings → About watch → Software** — tap *Software version* 5 times
2. **Settings → Developer options** — enable:
   - **ADB debugging**
   - **Debug over Wi-Fi** (note the IP address shown, e.g. `192.168.1.42:5555`)

### Connect ADB over Wi-Fi

On your computer (Docker host):

```bash
# Install adb if not present
# Ubuntu/Debian: sudo apt install adb
# macOS (Homebrew): brew install android-platform-tools

adb connect 192.168.1.42:5555
adb devices   # should list the watch
```

### Install the APK

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

The app appears in the watch launcher under **Marine Watch**.

### Re-install after a code change

```bash
docker compose run --rm build && \
    adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## BLE Pairing

1. Launch the app on the watch — it immediately starts scanning for `MarineGateway`.
2. When the watch finds the ESP32, the OS shows a pairing dialog.
3. The ESP32 displays a PIN on its interface (default: **123456**).
4. Confirm the pairing on the watch.
5. The bond is saved — subsequent reconnections are automatic.

> The app uses `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` permissions (Android 12+).
> On first launch, the OS permission dialog will appear on the watch.

---

## Always-On Display (Ambient Mode)

The app implements `AmbientLifecycleObserver` (Wear OS ambient API).
In ambient mode:
- The background stays black (OLED burn-in protection)
- Colours are dimmed
- The status bar / accent elements are hidden
- Data values are still updated every minute via `onUpdateAmbient`

---

## Signing for Production (optional)

To generate a proper release keystore:

```bash
keytool -genkeypair -v \
    -keystore marine-watch.jks \
    -keyalg RSA -keysize 2048 \
    -validity 10000 \
    -alias marine-watch
```

Then update `app/build.gradle`:

```groovy
android {
    signingConfigs {
        release {
            storeFile file("marine-watch.jks")
            storePassword "YOUR_STORE_PASSWORD"
            keyAlias "marine-watch"
            keyPassword "YOUR_KEY_PASSWORD"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
}
```

---

## Future Improvements (planned)

- Configurable tile layout (choose which fields to display)
- Autopilot control from the watch (heading adjustments via the `AutopilotCmd` characteristic)
- Wind data screen (apparent/true wind from the Wind BLE service)
- Settings screen for the PIN code and device name

---

## BLE Protocol Reference

See `doc/BLE_Client_Documentation.md` in the ESP32 firmware repository for the
complete GATT profile specification.

Key UUIDs used by this app:

| Element | UUID |
|---------|------|
| Navigation Service | `4d475743-0001-4e41-5649-474154494f4e` |
| NavData Characteristic | `4d475743-0101-4e41-5649-474154494f4e` |
| CCCD Descriptor | `00002902-0000-1000-8000-00805f9b34fb` |
