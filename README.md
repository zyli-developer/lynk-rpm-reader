<div align="center">
  <img src="docs/assets/lynk-rpm-reader-icon.png" width="144" height="144" alt="LynkRPMReader 图标">
  <h1>LynkRPMReader</h1>
  <p>面向 Android Automotive 车机的开源发动机转速仪表</p>

  [![Android CI](https://img.shields.io/github/actions/workflow/status/zyli-developer/lynk-rpm-reader/android.yml?branch=main&style=flat-square&label=build)](https://github.com/zyli-developer/lynk-rpm-reader/actions/workflows/android.yml)
  [![License](https://img.shields.io/badge/license-Apache--2.0-blue?style=flat-square)](LICENSE)
  ![Android](https://img.shields.io/badge/Android-9%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
  ![JDK](https://img.shields.io/badge/JDK-17-ED8B00?style=flat-square&logo=openjdk&logoColor=white)

  [简体中文](README.md) | [English](README_EN.md)

  <p>⭐ 如果这个项目对你有帮助，欢迎点击右上角的 <strong>Star</strong>，支持项目持续维护与改进！</p>
</div>

> [!IMPORTANT]
> 当前版本已在 **2023 款和 2025 款领克 08 / Flyme Auto 2.0.0**，以及本次连接的 **领克 08 / Flyme Auto 2.5.0** 上完成实机验证。其他车型、年款和系统版本尚未验证。

## 🚗 项目简介

LynkRPMReader 在车机横屏上实时显示发动机转速，并提供启动扫表动画。应用优先读取兼容车机提供的本地 APVP 信号，在不可用时依次尝试标准 Android Car API 和可选的 Root Car API 通道。

当前源码包名为 `com.lynk.rpmreader`，版本为 `2.0.3`（`versionCode 23`）。

本项目为非官方社区项目，与领克、吉利、魅族及其关联公司无隶属、授权或背书关系。品牌及产品名称仅用于如实说明已验证的设备兼容性。

## 🖼️ 应用界面

<div align="center">
  <img src="docs/assets/lynk-rpm-reader-ui-20260811.png" width="100%" alt="LynkRPMReader 实际车机运行界面">
  <p><sub>LynkRPMReader 实际车机运行界面</sub></p>
</div>

## ✅ 已验证兼容性

| 车型 | 年款 | 车机系统 | 验证状态 |
| --- | --- | --- | --- |
| 领克 08 | 2023 款 | Flyme Auto 2.0.0 | ✅ 已验证 |
| 领克 08 | 2025 款 | Flyme Auto 2.0.0 | ✅ 已验证 |
| 领克 08 | 2025 款 | Flyme Auto 2.5.0 | ✅ 已实机验证 |

应用不依赖特定 CPU。兼容性取决于车辆信号、本地服务和系统权限，请勿仅根据座舱芯片判断是否兼容。

## ✨ 功能亮点

- 📈 横屏实时转速仪表
- 🚀 一次性启动动画与自检扫表
- 🔌 兼容车机 APVP 本地 gRPC 转速读取
- 🖥️ 实验性转速位置四选一：关闭、左侧速度区、右侧转速卡片、HUD 左下角
- 🛣️ HUD 模式在 D/N 挡显示数值，并启动到现有 520×280 HUD 地图显示器
- 🚘 Android Automotive 标准 `ENGINE_RPM` 与 E245 vendor 属性 `0x21408066` 后备通道（按实车可读取情况选择）
- 🔐 可选 Root Car API 后备通道
- 🧪 JVM 零依赖协议与仪表逻辑测试
- 🏠 数据仅在车机本地处理，不连接外部业务服务器

## 📱 系统要求

- Android 9（API 28）或更高版本
- 兼容的车机本地服务或 Android Automotive 车辆属性权限
- HUD 模式可使用 APK 内置 shell 服务，或 Shizuku API 12+。内置服务需要先通过 ADB shell 启动；普通 APK 无法自行获得 shell 权限。车机重启或升级 APK 后需重新启动服务。已有 Shizuku 安装仍可作为后备；其 ADB 模式服务通常也会在重启后停止
- 可选 Root 后备通道只会连接 `127.0.0.1:38605` 上的本机辅助服务；APK 不会自动申请 Root 权限或启动该服务

普通 Android 手机可以安装和启动，但通常没有车辆数据服务，因此不会显示真实转速。请勿在驾驶过程中安装、调试或操作本应用。

### 单 APK HUD 模式（开发车机）

安装本项目 APK 后，在已授权的 ADB 连接下执行：

```powershell
.\scripts\start-embedded-hud.ps1
```

脚本从车机上的已安装 APK 启动本项目自带的固定功能 shell 服务。它只接受本应用 UID 的请求，只能查找 520×280 的 `Ex Share Display` 并启动本应用的 HUD Activity；不提供任意命令执行接口。手机或电脑上不必另装 Shizuku APK。车机每次重启及本应用更新后须重新运行脚本；没有 ADB 或 Root 授权时，单靠普通 APK 无法自动恢复 shell 权限。

转速仍优先走已验证的 APVP 通道。EVCC 资料中的 `0x21408066` 是 E245 平台的 Int32 转速属性，本项目仅在标准 `ENGINE_RPM` 读取失败且车机实际返回该属性时使用。25 款领克 08 是否暴露该属性尚未验证。EVCC 的专有 VHAL gRPC 接入依赖其 native 参数与车端服务，本项目没有复用该协议。

## 🛠️ 构建与测试

需要 JDK 17 和包含 Android 34 平台的 Android SDK。项目源码兼容级别为 Java 8，JDK 17 用于运行当前 Android Gradle Plugin。

构建前需让 Gradle 能够找到 Android SDK。可以通过 Android Studio 生成仓库根目录下的 `local.properties`，也可以在当前终端设置 `ANDROID_HOME`。

Windows PowerShell 的常见配置：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

Linux 的常见配置：

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
```

macOS 的常见配置：

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

Windows：

```powershell
.\gradlew.bat :rpmreader:rpmLogicTest :rpmreader:assembleDebug
```

Linux / macOS：

```bash
./gradlew :rpmreader:rpmLogicTest :rpmreader:assembleDebug
```

构建成功后，APK 位于：

```text
rpmreader/build/outputs/apk/debug/rpmreader-debug.apk
```

Debug APK 仅用于测试。Release 构建默认不包含发布签名，请在本地或私有 CI 中配置自己的签名材料，切勿提交密钥、证书密码或 `local.properties`。

## 🔄 数据读取顺序

```text
APVP local gRPC → Android Car API → Root Car API
```

APVP 兼容层仅连接车机本机回环地址，不连接互联网。实现中的接口名称与信号标识只用于设备互操作。

## 🛡️ 安全、隐私与兼容性

- 应用只读取发动机转速及其状态，不发送车辆控制指令。
- 不采集 VIN、位置、音视频、账号或其他车辆控制数据。
- 转速只在设备内存和系统日志中处理，不上传到外部服务器。
- `INTERNET` 权限只用于连接车机本机回环地址上的 APVP 服务（`localhost:40005/40007`）和可选 Root 辅助服务（`127.0.0.1:38605`）。
- 只能在本人所有或获得明确授权的车辆与车机上使用，不得绕过访问控制或获取无权访问的数据。
- 车机固件升级后，本地接口可能发生变化。
- Root 辅助服务以更高权限运行，会扩大整体安全风险面；不了解风险时请勿启用。
- HUD 的 Shizuku UserService 只允许查找 520×280 的 Flyme Auto HUD 并启动本应用的转速 Activity，不向应用或外部组件暴露任意 shell 命令。
- 可选的开机助手属于独立且明确的系统修改，只负责调用已安装的 Shizuku 官方启动器，可使用 `tools/shizuku-autostart/uninstall.ps1` 撤销。转速 APK 仅在显示位置仍选择 HUD 且之前已在前台成功启动过 HUD 时安排恢复。
- HUD 位置属于实验功能；停车状态下完成授权和显示验证后再使用，若遮挡原车导航或提示内容应立即关闭。
- 不保证兼容所有车型、地区版本或车机固件。
- 仓库只包含项目原创代码及按各自许可证使用的第三方依赖，不包含无权分发的第三方内容。

更多信息：

- [隐私说明](PRIVACY.md)
- [开发与来源说明](PROVENANCE.md)
- [法律与使用边界](LEGAL_NOTICE.md)
- [安全策略](SECURITY.md)
- [第三方依赖说明](THIRD_PARTY_NOTICES.md)

## 🤝 参与贡献

欢迎提交 Issue 和 Pull Request。贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，并确保新增代码、依赖和资源具有清晰的来源与授权。

## 📄 许可证

项目原创代码以 [Apache License 2.0](LICENSE) 发布。第三方组件继续适用各自的许可证；Apache-2.0 不授予任何第三方商标使用权。
