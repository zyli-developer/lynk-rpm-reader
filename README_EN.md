<div align="center">
  <img src="docs/assets/lynk-rpm-reader-icon.png" width="144" height="144" alt="LynkRPMReader icon">
  <h1>LynkRPMReader</h1>
  <p>An open-source engine RPM gauge for Android Automotive head units</p>

  [![Android CI](https://img.shields.io/github/actions/workflow/status/zyli-developer/lynk-rpm-reader/android.yml?branch=main&style=flat-square&label=build)](https://github.com/zyli-developer/lynk-rpm-reader/actions/workflows/android.yml)
  [![License](https://img.shields.io/badge/license-Apache--2.0-blue?style=flat-square)](LICENSE)
  ![Android](https://img.shields.io/badge/Android-9%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
  ![JDK](https://img.shields.io/badge/JDK-17-ED8B00?style=flat-square&logo=openjdk&logoColor=white)

  [简体中文](README.md) | [English](README_EN.md)

  <p>⭐ If this project helps you, please click <strong>Star</strong> in the upper-right corner to support its continued maintenance and improvement!</p>
</div>

> [!IMPORTANT]
> The current version has only been validated on the **2023 and 2025 Lynk & Co 08** running **Flyme Auto 2.0.0**. Other models, model years, and system versions have not been verified.

## 🚗 Overview

LynkRPMReader displays live engine RPM on a landscape head-unit screen and includes a startup gauge-sweep animation. It first reads the local APVP signal exposed by a compatible head unit, then falls back to the standard Android Car API and the optional Root Car API path when necessary.

The current source uses package name `com.lynk.rpmreader`, version `1.7.3`, and `versionCode 14`.

This is an unofficial community project. It is not affiliated with, authorized by, or endorsed by Lynk & Co, Geely, Meizu, or any of their affiliates. Brand and product names are used solely to identify verified device compatibility.

## 🖼️ App interface

<div align="center">
  <img src="docs/assets/lynk-rpm-reader-ui-20260811.png" width="100%" alt="LynkRPMReader running on an actual vehicle head unit">
  <p><sub>LynkRPMReader running on an actual vehicle head unit</sub></p>
</div>

## ✅ Verified compatibility

| Vehicle | Model year | Head-unit system | Status |
| --- | --- | --- | --- |
| Lynk & Co 08 | 2023 | Flyme Auto 2.0.0 | ✅ Verified |
| Lynk & Co 08 | 2025 | Flyme Auto 2.0.0 | ✅ Verified |

The app does not depend on a specific CPU. Compatibility depends on vehicle signals, local services, and system permissions; do not determine compatibility from the cockpit processor alone.

## ✨ Features

- 📈 Live landscape RPM gauge
- 🚀 One-shot startup animation and gauge self-test sweep
- 🔌 Local APVP gRPC engine-speed reader for compatible head units
- 🚘 Standard Android Automotive `ENGINE_RPM` fallback
- 🔐 Optional Root Car API fallback
- 🧪 Zero-dependency JVM tests for protocol and gauge logic
- 🏠 On-device data processing with no external application server

## 📱 Requirements

- Android 9 (API 28) or later
- A compatible local vehicle service or permission to access Android Automotive vehicle properties
- The optional Root fallback only connects to a local helper at `127.0.0.1:38605`; the APK does not request Root access or start that service automatically

The app can be installed and launched on a regular Android phone, but phones normally lack vehicle data services and therefore cannot display actual engine RPM. Do not install, debug, or operate the app while driving.

## 🛠️ Build and test

The build requires JDK 17 and an Android SDK installation containing the Android 34 platform. The source compatibility level is Java 8; JDK 17 is required to run the current Android Gradle Plugin.

Before building, make the Android SDK discoverable to Gradle. Android Studio can generate `local.properties` in the repository root, or you can set `ANDROID_HOME` in the current terminal.

Typical Windows PowerShell configuration:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

Typical Linux configuration:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
```

Typical macOS configuration:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

Windows:

```powershell
.\gradlew.bat :rpmreader:rpmLogicTest :rpmreader:assembleDebug
```

Linux / macOS:

```bash
./gradlew :rpmreader:rpmLogicTest :rpmreader:assembleDebug
```

After a successful build, the APK is located at:

```text
rpmreader/build/outputs/apk/debug/rpmreader-debug.apk
```

The Debug APK is intended for testing only. Release builds are unsigned by default. Configure your own signing material locally or in a private CI environment, and never commit signing keys, certificate passwords, or `local.properties`.

## 🔄 Data-source order

```text
APVP local gRPC → Android Car API → Root Car API
```

The APVP compatibility layer connects only to loopback addresses on the head unit and does not connect to the internet. Interface names and signal identifiers in the implementation are used solely for device interoperability.

## 🛡️ Security, privacy, and compatibility

- The app only reads engine RPM and its status; it does not send vehicle-control commands.
- It does not collect the VIN, location, audio, video, account information, or other vehicle-control data.
- RPM values are processed only in device memory and system logs and are not uploaded to an external server.
- The `INTERNET` permission is used only for the local APVP services (`localhost:40005/40007`) and the optional Root helper (`127.0.0.1:38605`) on the head unit's loopback interface.
- Use the app only on vehicles and head units that you own or are explicitly authorized to use. Do not bypass access controls or obtain data without authorization.
- Local interfaces may change after a head-unit firmware update.
- The Root helper runs with elevated privileges and increases the overall security risk. Do not enable it unless you understand the implications.
- Compatibility with every vehicle, regional variant, or head-unit firmware is not guaranteed.
- The repository contains original project code and third-party dependencies used under their respective licenses. It does not contain third-party material that the project is not authorized to distribute.

More information:

- [Privacy notice](PRIVACY.md)
- [Development and provenance](PROVENANCE.md)
- [Legal and usage boundaries](LEGAL_NOTICE.md)
- [Security policy](SECURITY.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## 🤝 Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before contributing, and ensure that all new code, dependencies, and assets have a clear source and appropriate authorization.

## 📄 License

Original project code is released under the [Apache License 2.0](LICENSE). Third-party components remain subject to their respective licenses. Apache-2.0 does not grant rights to use third-party trademarks.
