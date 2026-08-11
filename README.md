# LynkRPMReader

一个面向 Android Automotive 车机的开源发动机转速仪表。当前版本仅在 2023 款和 2025 款领克 08、Flyme Auto 2.0.0 系统上完成适配验证。应用优先读取兼容车机提供的本地 APVP 信号，在不可用时依次尝试标准 Android Car API 和可选的 Root Car API 通道。

> 本项目为非官方社区项目，与领克、吉利、魅族及其关联公司无隶属、授权或背书关系。品牌及产品名称仅用于说明已验证的设备兼容性。实际使用要求车机提供相应 APVP 服务或 Android Automotive 车辆属性权限。

## 已验证兼容性

| 车型 | 年款 | 车机系统 | 状态 |
| --- | --- | --- | --- |
| 领克 08 | 2023 款 | Flyme Auto 2.0.0 | 已验证 |
| 领克 08 | 2025 款 | Flyme Auto 2.0.0 | 已验证 |

其他车型、其他年款以及 Flyme Auto 的其他版本目前均未验证。应用不依赖特定 CPU；兼容性取决于车辆信号、本地服务和系统权限，请勿仅根据座舱芯片推断兼容性。

## 功能

- 横屏实时转速仪表及启动动画
- 兼容车机 APVP 本地 gRPC 转速读取
- 标准 Android Automotive `ENGINE_RPM` 兼容通道
- 可选 Root Car API 后备通道
- JVM 零依赖协议与仪表逻辑测试

## 系统要求

- Android 9（API 28）或更高版本
- 实际转速读取需要兼容的车机服务或 Android Automotive 权限
- Root 后备通道需要设备已 Root，并由用户主动授予权限

普通 Android 手机可以安装和启动，但通常没有车辆数据服务，因此不会显示真实转速。请勿在驾驶过程中安装、调试或操作本应用。

## 构建

需要 JDK 17 和 Android SDK 34：

```powershell
.\gradlew.bat :rpmreader:assembleDebug
```

Linux/macOS：

```bash
./gradlew :rpmreader:assembleDebug
```

APK 输出：

```text
rpmreader/build/outputs/apk/debug/rpmreader-debug.apk
```

运行纯 JVM 逻辑测试：

```bash
./gradlew :rpmreader:rpmLogicTest
```

Release 构建默认不包含发布签名。请在本地或私有 CI 中配置自己的签名材料，切勿提交密钥、证书密码或 `local.properties`。

## 数据读取顺序

```text
APVP local gRPC -> Android Car API -> Root Car API
```

APVP 兼容层仅连接车机本机回环地址，不连接互联网。实现中的接口名称与信号标识用于设备互操作。

## 安全与兼容性

- 本应用只读取车辆属性，不发送车辆控制指令。
- 车机固件升级后，私有 APVP 接口可能发生变化。
- Root 会扩大应用权限范围；不了解风险时不要启用。
- 不保证兼容所有车型、地区版本或车机固件。

## 隐私与法律边界

- 应用只读取发动机转速及其状态，不采集 VIN、位置、音视频、账号或车辆控制数据。
- 转速仅在设备内存和系统日志中处理，不上传到外部服务器。
- `INTERNET` 权限仅用于连接车机本机回环地址上的 APVP 服务。
- 只能在本人所有或获得明确授权的车辆与车机上使用；不得用于绕过访问控制或获取无权访问的数据。
- 仓库只包含项目原创代码及按各自许可证使用的第三方依赖，不包含无权分发的第三方代码、固件、平台签名或专有 SDK。

详细说明见 [PRIVACY.md](PRIVACY.md)、[PROVENANCE.md](PROVENANCE.md) 和 [LEGAL_NOTICE.md](LEGAL_NOTICE.md)。

## 许可证

项目原创代码以 [Apache License 2.0](LICENSE) 发布。第三方依赖及说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。Apache-2.0 不授予任何第三方商标的使用权。
