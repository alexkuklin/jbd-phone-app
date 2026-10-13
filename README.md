# JBD Battery Monitor

Android app (Kotlin + Jetpack Compose) that reads a JBD / Xiaoxiang BMS over Bluetooth LE and shows:

- battery current on a logarithmic ±200 A gauge (charge right/green, discharge left/orange)
- pack voltage, individual cell voltages, min/max and cell delta, balancing indicators
- power (V × I)
- temperature sensors reported by the BMS
- reported SoC and remaining/nominal Ah
- projected time to full (charging) or empty (discharging), from a ~30 s smoothed current
- current graph for the last up to 30 minutes since connecting

Protocol: GATT service `FF00`, notify `FF01`, write `FF02`; polls command `0x03` (basic info) and `0x04` (cell voltages) about once a second.
The BMS accepts only one BLE connection, so close other JBD apps first.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
