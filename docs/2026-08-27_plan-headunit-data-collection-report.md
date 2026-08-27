# 车机数据定向采集与 RPM APK 完善计划

本文档规定后续通过 ADB 对领克 DX11 车机进行只读数据采集时的重点、顺序、证据保存方式和安全边界，用于判断标准 Car API、APVP 及亿咖通厂商接口的可用性，并为 `lynk-rpm-reader` 后续开发提供可复现依据。

> 状态：执行中；2026-08-27 已完成首轮静态采集、兼容签名诊断 APK 覆盖安装、APVP 实际配置发现、用户确认的“怠速 → 发动机停止”窗口，以及普通 APK 的标准 Car API 权限异常取证。特权身份标准属性对照仍待完成。

## 关键约束

- 默认只读取车机，不修改系统分区、车辆属性、服务配置或 APK 数据。
- 不通过 ADB 控制油门、挡位、灯光、车门、空调或其他车辆执行器。
- 发动机状态变化只能由用户在安全停车条件下手动完成。
- 不清空车机日志；使用短时间窗口或有限行数快照。
- 不复制账号、联系人、短信、导航历史、语音内容、密钥或令牌。
- 涉及 root 身份、启动临时辅助进程或扩大 `/data` 读取范围前，单独确认必要性和授权。
- 每次采集使用独立时间戳目录，并生成文件清单和 SHA-256。

## 已知基线

| 项目 | 已确认结果 | 证据 |
| --- | --- | --- |
| 车机系统 | Android 11，SDK 30 | 私有本地采集归档（未提交） |
| 标准转速属性 | `ENGINE_RPM = 291504901 / 0x11600305` | [CarApiRpmClient.java](../rpmreader/src/main/java/com/lynk/rpmreader/CarApiRpmClient.java) |
| 标准属性权限 | `CAR_ENGINE_DETAILED` 为 `signature\|privileged` | 私有本地采集归档（未提交） |
| RPM Reader 实际权限 | 获得 `CAR_POWERTRAIN`，未获得 `CAR_ENGINE_DETAILED` | 同上 |
| APVP 静态配置基线 | `EngNSafeEngN = 308282774 / 0x12600596`，VDDM、只读 | 私有本地采集归档（未提交） |
| APVP 车机实际配置 | `EngNSafeEngN = 308282775 / 0x12600597`，transfer `268435456` | 私有本地采集归档（未提交） |
| APVP 服务 | 本地 gRPC `40005` 读取、`40007` 配置和 `setReady` | [ApvpGrpcRpmClient.java](../rpmreader/src/main/java/com/lynk/rpmreader/ApvpGrpcRpmClient.java) |
| 旧版实测 | APVP 曾记录约 `1292–1303 RPM`，标准属性此前固定为 0 | 历史实车记录（私有归档） |
| 当前待确认 | 标准属性是受权限拦截，还是 VHAL 未接入真实转速 | 后续 P0 采集 |
| 已确认差异 | 目标车机实际 APVP ID 比静态配置基线增加 1，应用必须使用运行时返回的 ID | E-011、F-005 |

## 2026-08-27 首轮采集与诊断部署执行记录

原始日志、截图、反编译产物及设备清单保存在私有本地采集归档中，不随公共仓库提交。首轮采集只执行只读查询、启动既有 RPM Reader、截取该应用界面和定向复制已确认的系统文件。随后经用户明确授权，使用与现装版本相同的发布证书对诊断 APK 签名，并通过 `adb install -r` 覆盖安装；未卸载应用、未清除应用数据、未使用 root、未清空日志，也未改变车辆属性。

| 项目 | 本轮结果 | 状态 |
| --- | --- | --- |
| 目标设备 | 领克 DX11 测试车机（设备标识已脱敏） | 已确认 |
| 运行身份 | `uid=2000(shell)`，SELinux `Permissive`，当前 Android 用户 `11` | 已保存 |
| 标准 RPM 配置 | `ENGINE_RPM / 0x11600305` 存在；READ、CONTINUOUS、Float/global 编码、1–10 Hz | 已确认 |
| 普通 APK 权限 | 请求 `CAR_ENGINE_DETAILED`，实际仅授予 `CAR_POWERTRAIN` | 已确认 |
| APVP 服务 | `127.0.0.1:40005` 和 `127.0.0.1:40007` 均在监听 | 已确认 |
| APVP 同步窗口 | 用户确认怠速阶段读到 `313–1318 rpm`，发动机停止后回到 `0.0 rpm`；均为 `mode=0` | 已确认 |
| APVP 真实配置 | 新版动态发现 `transfer=268435456`、`signal_id=308282775 / 0x12600597`、`name=EngNSafeEngN` | 已确认 |
| 诊断 APK 部署 | `versionName=1.9.3-display10-diagnostic`、`versionCode=19`；相同发布证书覆盖安装，用户 11 冷启动正常 | 已确认 |
| 标准 Car API 实测 | `CarPropertyManager` 连接成功，`getProperty(ENGINE_RPM)` 返回需要 `CAR_ENGINE_DETAILED` 的 `SecurityException` | 普通 APK 权限拒绝已确认 |
| 亿咖通 AdaptAPI | 厂商键 `(type=3, function=1050880/0x100900)` 映射到 `291504901` | 已确认 |
| 厂商接口权限 | AdaptBinderProvider 的 READ/WRITE 均为 `signature`；EAS Core 另有 license/签名授权校验 | 普通侧载 APK 当前不可直接采用 |
| 怠速同步对照 | 必须由用户确认安全停车并手动启动车辆 | 待执行 |

## 执行总览

```mermaid
flowchart TD
    connect[连接与版本基线] --> car[标准 Car API 与 VHAL]
    car --> compare{ENGINE_RPM 结果}
    compare -- 权限拒绝 --> identity[普通应用与特权身份对照]
    compare -- 属性为 0 --> mapping[VHAL 映射验证]
    compare -- 返回真实值 --> standard[评估标准 API 主路径]
    identity --> apvp[APVP 信号发现与同步采样]
    mapping --> apvp
    standard --> apvp
    apvp --> vendor[亿咖通与领克厂商 API]
    vendor --> display[显示、后台与多用户能力]
    display --> decision[形成 APK 数据源决策]
```

## 采集优先级

| 优先级 | 采集主题 | 要回答的问题 | 完成条件 |
| --- | --- | --- | --- |
| P0 | 标准 Car API/VHAL | `ENGINE_RPM` 是否存在、谁能读、是否为真实值 | 明确权限、属性配置和熄火/怠速值 |
| P0 | APVP 对照 | APVP 实际 ID、类型、状态和更新频率是否稳定 | 与标准属性在同一时间窗口完成对照 |
| P1 | 汽车框架文件 | 车机实际如何定义属性权限和服务映射 | 保存相关 APK/JAR/XML/VINTF 及哈希 |
| P1 | 亿咖通厂商 API | 是否有比 APVP 更正式的第三方只读接口 | 找到明确接口或形成不可用证据 |
| P2 | 运行可靠性 | 休眠、唤醒、多用户、前后台是否影响读取 | 覆盖关键生命周期状态 |
| P2 | 扩展车辆信号 | 后续 APK 可以安全读取哪些数据 | 建立信号目录和推荐数据源 |
| P2 | 多屏显示 | RPM 是否可稳定展示在目标 Display | 确认 Display ID、窗口限制和投屏服务 |

## 1. 建立连接与采集目录

### 目标

确认设备身份、版本和当前 Android 用户，并把本轮输出与既有备份隔离。

### 本机准备

以下 PowerShell 命令只在本机创建采集目录并查询设备：

```powershell
$adbExe = 'D:\AndroidWatch_ADB_ToolBox\adb\adb.exe'
$captureStamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$captureRoot = "D:\evcc\backups\headunit_followup_$captureStamp"
New-Item -ItemType Directory -Path $captureRoot | Out-Null

& $adbExe devices -l |
    Tee-Object -FilePath (Join-Path $captureRoot 'adb_devices.txt')
```

如果没有显示 `device` 状态，停止后续步骤，先解决 USB、授权或网络 ADB 连接问题。

### 基线命令

```powershell
& $adbExe shell getprop |
    Set-Content -LiteralPath (Join-Path $captureRoot 'getprop.txt') -Encoding utf8

& $adbExe shell 'id; whoami; getenforce; am get-current-user; uptime' |
    Set-Content -LiteralPath (Join-Path $captureRoot 'runtime_identity.txt') -Encoding utf8

& $adbExe shell 'pm list users; wm size; wm density' |
    Set-Content -LiteralPath (Join-Path $captureRoot 'users_display_baseline.txt') -Encoding utf8
```

### 停止条件

- 序列号与既有车机不一致。
- 设备状态为 `unauthorized`、`offline` 或空列表。
- 当前车辆状态不适合进行静态读取或用户无法确认车辆安全停放。

## 2. 验证标准 Car API 和 VHAL

这是下一轮采集的最高优先级。

### 采集服务和属性配置

```powershell
& $adbExe shell dumpsys car_service |
    Set-Content -LiteralPath (Join-Path $captureRoot 'dumpsys_car_service.txt') -Encoding utf8

& $adbExe shell dumpsys package com.android.car |
    Set-Content -LiteralPath (Join-Path $captureRoot 'package_com.android.car.txt') -Encoding utf8

& $adbExe shell dumpsys package com.lynk.rpmreader |
    Set-Content -LiteralPath (Join-Path $captureRoot 'package_com.lynk.rpmreader.txt') -Encoding utf8

& $adbExe shell service list |
    Set-Content -LiteralPath (Join-Path $captureRoot 'service_list.txt') -Encoding utf8

& $adbExe shell cmd -l |
    Set-Content -LiteralPath (Join-Path $captureRoot 'cmd_services.txt') -Encoding utf8

& $adbExe shell lshal |
    Set-Content -LiteralPath (Join-Path $captureRoot 'lshal.txt') -Encoding utf8
```

`lshal` 在部分版本可能只向 root 或 shell 暴露有限信息；命令失败也应保留错误输出，作为权限证据。

### 必须确认的字段

- `0x11600305` 或 `291504901` 是否出现在属性配置中。
- 属性类型是否为 Float。
- area 是否为 global/0。
- change mode 是否为 continuous。
- 支持的最小、最大采样率。
- 当前值的 status、timestamp 和 value。
- 普通 APK 调用时是否出现 `SecurityException` 或权限拒绝。
- `CAR_ENGINE_DETAILED` 是否出现在应用的实际 granted permissions 中。

### 判定规则

| 观察结果 | 判断 | 后续方向 |
| --- | --- | --- |
| 属性配置中不存在 `ENGINE_RPM` | 标准 VHAL 未提供该属性 | APVP 作为主路径 |
| 属性存在，普通 APK 报权限拒绝 | 接口存在但第三方权限不足 | 评估平台签名、特权安装或厂商代理 API |
| 属性存在且普通 APK读取为 0 | 可能是权限返回、状态无效或映射缺失 | 检查 status，并与 APVP 同步对照 |
| 特权身份读取真实值，普通 APK失败 | 主要是身份/权限问题 | 标准 API 只适用于系统集成版本 |
| 特权身份同样固定为 0 | VHAL 很可能未映射真实转速 | 不把标准 API 作为主数据源 |
| 普通 APK可读取真实值 | 标准 API 可成为主路径 | 改为 callback 订阅并保留 APVP 后备 |

## 3. 进行 APVP 同步对照

### 目标

确认实际车机版本使用的 `EngNSafeEngN` ID，并与标准 `ENGINE_RPM` 在相同车辆状态下比较。

### 采集进程和端口

```powershell
& $adbExe shell ps -A |
    Set-Content -LiteralPath (Join-Path $captureRoot 'processes.txt') -Encoding utf8

& $adbExe shell ss -lntp |
    Set-Content -LiteralPath (Join-Path $captureRoot 'tcp_listeners.txt') -Encoding utf8
```

重点检查：

- `localhost:40005`
- `localhost:40007`
- 端口对应的进程、UID 和启动来源
- `getAllTransfer` 返回的 transfer 列表
- `getTransferSignalConfig` 中名称为 `EngNSafeEngN` 的完整配置
- `setReady` 前后 value/status 的变化
- `readSignal` 响应中的实际 ID，而不是只检查代码内硬编码 ID

### 状态对照

| 采样状态 | 用户操作 | 标准 Car API | APVP | 目的 |
| --- | --- | --- | --- | --- |
| 熄火 | 车辆保持安全停放和熄火 | 记录 value/status | 记录 value/mode/ID | 建立零值基线 |
| 通电未启动 | 用户手动切换电源状态 | 同上 | 同上 | 区分系统在线与发动机运行 |
| 正常怠速 | 用户手动启动车辆并保持 P 挡 | 同上 | 同上 | 验证真实 RPM |
| 可选轻微变化 | 仅在用户确认安全且主动操作时 | 同上 | 同上 | 比较响应速度和缩放关系 |

如果不具备安全条件，只采集熄火和正常怠速，不需要为了测试主动改变转速。

### 短窗口日志

不清空车机日志，只提取有限行数：

```powershell
& $adbExe logcat -b all -d -v threadtime -t 5000 |
    Set-Content -LiteralPath (Join-Path $captureRoot 'logcat_recent_5000.txt') -Encoding utf8

Get-Content -LiteralPath (Join-Path $captureRoot 'logcat_recent_5000.txt') |
    Select-String -Pattern 'RpmReader|ENGINE_RPM|EngNSafeEngN|CarProperty|VehicleHal|APVP|SecurityException' |
    Set-Content -LiteralPath (Join-Path $captureRoot 'logcat_rpm_filtered.txt') -Encoding utf8
```

## 4. 定向复制汽车框架文件

先查询真实安装路径，再逐个复制；不要假设不同固件中的系统路径相同。

### 查询目标包路径

```powershell
$vehiclePackages = @(
    'com.android.car',
    'com.ecarx.eas.carservice',
    'com.ecarx.sdk.openapi',
    'com.ecarx.dfe.service',
    'com.flyme.auto.carservice'
)

foreach ($vehiclePackage in $vehiclePackages) {
    "PACKAGE=$vehiclePackage"
    & $adbExe shell pm path $vehiclePackage
} | Set-Content -LiteralPath (Join-Path $captureRoot 'vehicle_package_paths.txt') -Encoding utf8
```

### 复制顺序

1. 包清单中确认存在的 APK。
2. `android.car.jar` 和 Car Service 相关 JAR/APK。
3. `/system/etc/permissions` 中汽车权限 XML。
4. `/system/etc/permissions`、`/system/etc/sysconfig` 中相关特权白名单。
5. `/vendor/etc/vintf`、`/system/etc/vintf` 中 Vehicle HAL 声明。
6. APVP signal 配置及 protobuf/AIDL/JAR。
7. 只有静态分析仍缺证据时，才追加 OAT/VDEX 或 native 库。

实际执行 `adb pull` 前，应先检查每个绝对路径、目标大小和内容类型；确认后按文件逐个复制到本轮目录，禁止对 `/system`、`/vendor` 或 `/data` 做无差别递归拉取。

## 5. 分析亿咖通和领克厂商 API

### 目标

判断是否存在比 APVP 更正式、允许普通第三方应用使用的只读车辆数据接口。

### 静态检查重点

- exported Service、Provider、Receiver 和绑定 action。
- AIDL/Binder 接口和客户端 SDK。
- RPM、engine speed、powertrain、VDDM 等关键字。
- 回调或订阅接口，而非只寻找轮询读取。
- 调用方签名、UID、包名白名单和自定义权限。
- 接口是否只在 `system` UID 或特权应用中可用。
- 不同 Android 用户下服务是否独立运行。

### 采用条件

厂商接口只有同时满足以下条件，才应优先于 APVP：

- 明确提供只读 RPM。
- 普通安装 APK 可以取得所需权限或完成合法绑定。
- 数据在实车怠速状态下得到验证。
- 接口具备稳定的版本或能力发现机制。
- 不依赖 root、内存读取或直接 CAN 帧解析。

## 6. 验证运行可靠性和显示能力

在转速数据源确定后执行，避免显示问题与数据问题相互干扰。

### 生命周期场景

- APK 首次启动和再次启动。
- 前台切后台、后台回前台。
- 车机休眠和唤醒。
- APVP/Car Service 暂时不可用后的恢复。
- Android 用户 0、10、11 的安装和运行差异。
- 网络变化对 localhost gRPC 的影响。

### 显示场景

- `dumpsys display` 中全部 Display ID 和类型。
- `dumpsys window` 中目标 Activity 的 display、windowing mode 和限制。
- `com.ecarx.dfe.service` 的投屏/副屏能力与权限。
- 主屏与副屏切换后 RPM 数据线程是否保持单实例。

```powershell
& $adbExe shell dumpsys display |
    Set-Content -LiteralPath (Join-Path $captureRoot 'dumpsys_display.txt') -Encoding utf8

& $adbExe shell dumpsys window |
    Set-Content -LiteralPath (Join-Path $captureRoot 'dumpsys_window.txt') -Encoding utf8

& $adbExe shell dumpsys activity processes |
    Set-Content -LiteralPath (Join-Path $captureRoot 'dumpsys_activity_processes.txt') -Encoding utf8
```

## 7. 扩展车辆信号目录

RPM 主链路完成后，再按使用价值依次调查：

1. 车速。
2. 挡位。
3. 发动机/电机运行状态。
4. 冷却液温度。
5. SOC、电池功率和充放电状态。
6. 驾驶模式。
7. 车门、灯光等只读状态。

每个信号使用以下记录字段：

| 字段 | 说明 |
| --- | --- |
| 功能名称 | 用户可理解的名称 |
| 标准属性 | `VehiclePropertyIds` 名称、十进制和十六进制 ID |
| 厂商信号 | APVP/XSF 名称、ID 和模块 |
| 类型与单位 | Float/Int/Boolean、RPM、m/s、℃ 等 |
| area | global、seat、wheel 等 |
| 权限 | 声明权限和实际 granted 状态 |
| 普通 APK | 是否可读及错误表现 |
| 特权身份 | 是否可读及与普通 APK 的差异 |
| 实车证据 | 状态、时间、value、status/mode |
| 推荐数据源 | 标准 Car API、厂商 API、APVP 或不采用 |

## 证据、判断与开发路径

### Evidence

#### E-001

- 标题：车机将 `CAR_ENGINE_DETAILED` 定义为特权权限。
- 来源类型：file。
- 来源：`dumpsys_package.txt`（私有本地采集归档，未提交）。
- 关键观察：权限保护级别为 `signature|privileged`。
- 复现方式：下一次连接后执行 `adb shell dumpsys package com.android.car` 和 `adb shell dumpsys package com.lynk.rpmreader`。

#### E-002

- 标题：当前 RPM Reader 未实际获得 `CAR_ENGINE_DETAILED`。
- 来源类型：file。
- 来源：同 E-001。
- 关键观察：安装权限中仅见 `INTERNET`、`CAR_POWERTRAIN`、`ACCESS_NETWORK_STATE`。
- 复现方式：执行 `adb shell dumpsys package com.lynk.rpmreader`。

#### E-003

- 标题：车机配置存在只读 APVP 转速信号。
- 来源类型：file。
- 来源：`dx11_cn_apvp_signal_config.json`（私有本地采集归档，未提交）。
- 关键观察：`EngNSafeEngN`、`308282774`、`VDDM`、`readOnly=true`。
- 复现方式：下一轮从实际车机 transfer 配置重新发现名称和 ID。

#### E-004

- 标题：应用没有进程内存读取逻辑。
- 来源类型：source review。
- 来源：[ApvpGrpcRpmClient.java](../rpmreader/src/main/java/com/lynk/rpmreader/ApvpGrpcRpmClient.java) 和 [CarApiRpmClient.java](../rpmreader/src/main/java/com/lynk/rpmreader/CarApiRpmClient.java)。
- 关键观察：读取路径分别为 localhost gRPC 和 `CarPropertyManager.getProperty()`。
- 复现方式：搜索 `/dev/mem`、`/proc/*/mem`、`ptrace`、JNI/native memory API。

#### E-005

- 标题：当前固件的标准 VHAL 声明了 `ENGINE_RPM`。
- 来源类型：runtime dump。
- 来源：`dumpsys_car_service.txt`（私有本地采集归档，未提交）。
- 关键观察：`0x11600305 / 291504901`，READ、CONTINUOUS、最小 1 Hz、最大 10 Hz，并由 `PropertyHalService` 处理。
- 复现方式：执行 `adb shell dumpsys car_service` 并搜索十六进制或十进制属性 ID。

#### E-006

- 标题：普通 RPM Reader 当前仍未获得 `CAR_ENGINE_DETAILED`。
- 来源类型：runtime dump。
- 来源：`package_com.lynk.rpmreader.txt`（私有本地采集归档，未提交）。
- 关键观察：Manifest 请求该权限，但 install permissions 中只有 `INTERNET`、`CAR_POWERTRAIN` 和 `ACCESS_NETWORK_STATE`。
- 复现方式：执行 `adb shell dumpsys package com.lynk.rpmreader`。

#### E-007

- 标题：当前 APVP 本地读取链路返回 0 RPM。
- 来源类型：runtime log and screenshot。
- 来源：`rpmreader_after_launch_filtered.txt` 和 `rpmreader_screen.png`（私有本地采集归档，未提交）。
- 关键观察：`APVP EngNSafeEngN=0.0 rpm, mode=0`，界面显示 `APVP LIVE`。
- 限制：本轮未独立记录车辆电源状态，不能仅凭 0 RPM 将该样本标记为“熄火”。

#### E-008

- 标题：亿咖通 AdaptAPI 内部存在标准 RPM 属性映射，但直接 Provider 受签名权限保护。
- 来源类型：decompiled APK and package dump。
- 来源：反编译的 `b.java`、`AndroidManifest.xml` 和 `package_com_ecarx_eas_carservice.txt`（私有本地采集归档，未提交）。
- 关键观察：`(3, 1050880)` 映射到 `291504901`；Provider 的 READ/WRITE 权限均为 `signature`，服务运行于 `android.uid.system`。

#### E-009

- 标题：EAS Core 的公开 Binder 服务仍执行包名、PID/UID、license operation 和应用签名授权。
- 来源类型：decompiled APK。
- 来源：反编译的 `f.java` 和 `e.java`（私有本地采集归档，未提交）。
- 关键观察：`getService()` 调用 `checkPermissionNotThrow()`；授权列表来自应用 `assets/license.txt` 或应用商店 license provider，并校验目标应用签名。

#### E-010

- 标题：当前 user build 禁止通过 `cmd car_service` 直接读取属性值。
- 来源类型：runtime command failure。
- 来源：`car_service_get_engine_rpm.txt`（私有本地采集归档，未提交）。
- 关键观察：只读命令 `get-property-value 291504901 0` 返回 `SecurityException: requires non-user build`。
- 影响：普通身份的标准属性运行值必须由 APK 内 Car API 诊断记录，不能由 shell 命令替代。

#### E-011

- 标题：目标车机运行时返回的 APVP 转速信号 ID 为 `0x12600597`。
- 来源类型：runtime log。
- 来源：`rpmreader_after_update_logcat_filtered.txt` 和 `rpm_observations.csv`（私有本地采集归档，未提交）。
- 关键观察：配置服务返回 transfer `268435456`、`signal_id=308282775 / 0x12600597`、`name=EngNSafeEngN`；应用以同一 ID 调用 `setReady` 和读取，首包响应身份一致，值为 `0.0 rpm`、`mode=0`。
- 限制：车辆电源状态仍未独立确认，因此该值只标记为 `unconfirmed_power_state`。

#### E-012

- 标题：新版诊断 APK 已使用现装版本的同一证书完成保留数据覆盖安装。
- 来源类型：APK verification and runtime deployment。
- 来源：`install_rpmreader_final.txt`、`package_after_update.txt` 和 `activity_after_update.txt`（私有本地采集归档，未提交）。
- 关键观察：诊断 APK 与现装版本证书一致；`adb install -r` 返回 `Success`；用户 11 中 RPM Reader 冷启动并保持 resumed。
- 安全说明：未卸载旧包、未清除应用数据；ADB 在增量安装不被允许后自动回退到流式安装。

#### E-013

- 标题：动态发现的 `0x12600597` 在实车窗口返回连续非零转速并最终回到 0。
- 来源类型：runtime log and observation summary。
- 来源：`rpmreader_nonzero_observation_logcat.txt` 和 `rpm_window_summary.csv`（私有本地采集归档，未提交）。
- 关键观察：20.906 秒窗口中取得 186 个非零样本，范围 `313–1318 rpm`、平均 `1279.58 rpm`、`mode=0`，随后在 `12:53:46.363` 返回 `0.0 rpm`。
- 状态确认：用户于本轮采集后确认，该窗口对应手动保持怠速并随后停止发动机；未通过 ADB 控制任何车辆执行器。

#### E-014

- 标题：普通 APK 连接标准 Car API 成功，但读取 `ENGINE_RPM` 被特权权限拒绝。
- 来源类型：runtime log。
- 来源：`rpmreader_final_logcat_filtered.txt` 和 `property_rpm_observations.csv`（私有本地采集归档，未提交）。
- 关键观察：`CarPropertyManager connected` 后，`getProperty(Float.class, 0x11600305, 0)` 返回 `SecurityException: requires android.car.permission.CAR_ENGINE_DETAILED`；同一启动窗口的 APVP 读取继续正常。
- 影响：可以把普通 APK 的标准属性结果明确归类为权限拒绝，而不是 0 值或无效状态。

#### E-015

- 标题：Flyme Auto 的导出 CarService 不是普通 APK 可用的无权限代理。
- 来源类型：decompiled system APK。
- 来源：反编译的 `CarService.java`（私有本地采集归档，未提交）。
- 关键观察：服务虽为 `exported=true`，但 `getPropertyList`、读取、订阅和权限查询等每个 Binder 方法都会先执行 `checkSignatures(Process.myUid(), Binder.getCallingUid())`，签名不一致时抛出 `SecurityException`。

#### E-016

- 标题：车机官方 APVP 客户端也采用信号名称，并对低 16 位 ID 变化提供一定兼容。
- 来源类型：decompiled cluster APK。
- 来源：反编译的 `APVPSignalManager.java`、`SignalIdentify.java` 和 `TransferClient.java`（私有本地采集归档，未提交）。
- 关键观察：生成代码固定声明 `EngNSafeEngN=308282775`，但身份相等与哈希使用名称和 ID 高 16 位；客户端支持指定信号订阅及 `REGISTER_ALL_READABLE_SIGNALS`。
- 限制：全量订阅会读取大量无关车辆信号，不符合最小采集原则，不作为默认后备。

#### E-017

- 标题：厂商 SDK 常量中的第二 RPM 属性未在当前 VHAL 发布。
- 来源类型：SDK constant and runtime dump comparison。
- 来源：`ECarXVehicleProperty.java` 和 `dumpsys_car_service.txt`（私有本地采集归档，未提交）。
- 关键观察：SDK 定义 `SENSOR_TYPE_RPM=559969008 / 0x216072F0`，但当前属性配置不存在该 ID；唯一带 RPM 语义的配置仍为标准 `ENGINE_RPM / 0x11600305`。

#### E-018

- 标题：EAS 导出 ContentProvider 不提供车辆信号。
- 来源类型：decompiled system APK。
- 来源：反编译的 `OpenAPIContentProvider.java`（私有本地采集归档，未提交）。
- 关键观察：Provider 只处理 SDK 版本和 core service 版本查询；车辆服务 Binder 仍经过既有 license、UID/包名和签名授权链。

### Findings

#### F-001：标准 RPM 属性对普通 APK 存在权限障碍

- 状态：已验证。
- 置信度：高。
- 证据：E-001、E-002。
- 影响：Manifest 声明本身不能让侧载 APK 读取标准 `ENGINE_RPM`。
- 后续：验证特权身份读取结果，区分纯权限问题与 VHAL 映射问题。

#### F-002：APVP ID 是信号标识而非内存地址

- 状态：已验证。
- 置信度：高。
- 证据：E-003、E-004。
- 影响：APVP 当前是无 root、只读、本地读取真实转速的已知可行路径。
- 后续：把信号名称发现、实际 ID 和版本兼容性纳入动态验证。

#### F-003：标准 VHAL 是否映射真实 RPM 尚未完成验证

- 状态：候选判断。
- 置信度：中。
- 证据：旧版实测记录及 E-001、E-002。
- 影响：如果特权身份也只能读取 0，则标准 API 不适合作为当前固件的主数据源。
- 后续：完成熄火/怠速、普通/特权身份、标准/APVP 四组对照。

#### F-004：当前固件配置了标准 `ENGINE_RPM`，普通 APK 运行时被权限拒绝

- 状态：已验证。
- 置信度：高。
- 证据：E-005、E-006、E-010、E-014。
- 影响：可以排除“属性完全不存在”和“普通 APK 读到 0”；当前普通侧载身份在取值前即被 `CAR_ENGINE_DETAILED` 拒绝。
- 后续：只有在另行授权的特权身份下读取 value/status，才能继续判断 VHAL 是否映射真实 RPM。

#### F-005：目标车机实际 APVP ID 为 `0x12600597`，必须动态发现

- 状态：已验证并完成实机修复验证。
- 置信度：高。
- 证据：E-007、E-011。
- 影响：静态配置中的 `0x12600596` 与当前车机实际 `0x12600597` 不一致；固定 ID 会造成固件兼容性风险。
- 结论：应用已改为保存配置返回的 `(transferId, signalId, signalName)`，并使用真实 ID 执行 `setReady` 和读取；响应身份校验已在实机通过。

#### F-006：亿咖通正式接口当前不优先于 APVP

- 状态：已验证静态约束。
- 置信度：高。
- 证据：E-008、E-009。
- 影响：虽然厂商层明确映射 RPM，但普通侧载 APK 没有 signature 权限和 EAS license/签名授权，不能把它视为可直接采用的第三方接口。
- 后续：只有取得正式 SDK license 或厂商授权后才进行动态绑定验证；当前主路径继续评估 APVP。

#### F-007：APVP 动态发现路径已通过“怠速 → 发动机停止”过程验证

- 状态：已验证。
- 置信度：高。
- 证据：E-011、E-013。
- 影响：`0x12600597` 不仅能返回静态 0 值，还能连续反映明显的转速变化，当前可作为普通侧载 APK 的主数据源候选。
- 结论：该窗口已具备用户确认的车辆状态标签；继续保持禁止 ADB 控制车辆执行器的边界。

#### F-008：普通侧载 APK 暂无比“APVP 语义动态发现”更稳定且合法可用的第二路径

- 状态：已完成本轮静态横向验证。
- 置信度：高。
- 证据：E-015、E-016、E-017、E-018，以及 E-009、E-014。
- 影响：系统 CarService 代理、EAS Provider、厂商 VHAL 常量和仪表服务均未形成普通 APK 可直接采用的新 RPM 接口。
- 决策：保持 APVP 动态发现为普通应用主路径；标准 Car API 为权限能力后备；EAS/AdaptAPI 只在取得正式 OEM license/签名授权后加入。
- 详细比较保存在私有本地采集归档的 `update_resilient_rpm_paths.md` 中，未提交到公共仓库。

### Path P-001：RPM 数据源决策路径

1. 从 `car_service` 获取 `ENGINE_RPM` property config，关联 E-001。
2. 用普通 RPM Reader 读取并记录 value/status/exception，关联 E-002。
3. 在单独授权后用特权身份读取同一属性，验证 F-003。
4. 同时发现并读取 APVP `EngNSafeEngN`，关联 E-003。
5. 在熄火和怠速状态下对齐时间并比较两路结果。
6. 根据判定表选择标准 Car API、厂商正式 API 或 APVP 作为主路径。
7. 保留另一条已验证路径作为受控后备，不引入内存读取和直接 CAN 解析。

## 采集结果目录结构

每轮目录保持以下结构；不存在的数据类型可以省略对应子目录：

```text
headunit_followup_YYYYMMDD_HHMMSS/
├── adb_devices.txt
├── getprop.txt
├── runtime_identity.txt
├── users_display_baseline.txt
├── car/
│   ├── dumpsys_car_service.txt
│   ├── package_com.android.car.txt
│   └── property_rpm_observations.csv
├── apvp/
│   ├── transfer_list.txt
│   ├── EngNSafeEngN_config.json
│   └── rpm_observations.csv
├── packages/
│   ├── vehicle_package_paths.txt
│   └── copied_files/
├── runtime/
│   ├── logcat_recent_5000.txt
│   ├── logcat_rpm_filtered.txt
│   ├── processes.txt
│   └── tcp_listeners.txt
├── display/
│   ├── dumpsys_display.txt
│   └── dumpsys_window.txt
├── inventory.csv
└── sha256.txt
```

`YYYYMMDD_HHMMSS` 表示由脚本自动生成的实际时间戳，不是需要手工创建的固定目录名。

## 完成本轮采集后的校验

```powershell
Get-ChildItem -LiteralPath $captureRoot -File -Recurse |
    ForEach-Object {
        $relativePath = $_.FullName.Substring($captureRoot.Length + 1)
        $fileHash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        [PSCustomObject]@{
            Path = $relativePath
            Length = $_.Length
            SHA256 = $fileHash.Hash
        }
    } |
    Export-Csv -LiteralPath (Join-Path $captureRoot 'inventory.csv') -NoTypeInformation -Encoding utf8
```

本轮只有同时满足以下条件才算完成：

- 保存设备、版本、用户和车辆状态基线。
- 明确 `ENGINE_RPM` 是否存在及其 property config。
- 保存普通 APK 的权限和读取结果。
- 保存 APVP 实际信号 ID、配置和读取结果。
- 至少完成熄火与怠速两种状态的同步比较；不具备安全条件时，在报告中明确只完成熄火状态。
- 所有复制文件有来源路径、大小和 SHA-256。
- 给出 APK 主数据源、后备数据源和不采用方案的明确结论。

## 下一次连接后的最短执行清单

1. 确认 `adb devices -l` 显示目标车机为 `device`。
2. 创建新的时间戳采集目录。
3. 保存系统、用户和车辆状态基线。
4. 采集 `dumpsys car_service`、相关包权限和 Vehicle HAL 清单。
5. 启动 RPM Reader，保存普通应用读取日志。
6. 发现 APVP transfer、`EngNSafeEngN` 实际 ID，并保存同步数据。
7. 在安全条件下完成熄火和正常怠速对照。
8. 根据结果决定是否需要单独授权特权身份验证。
9. 查询并定向复制 Car Service、亿咖通和 Flyme Auto 相关 APK/JAR/XML。
10. 生成文件清单和 SHA-256，更新本报告的 Evidence、Findings 和最终数据源决策。
