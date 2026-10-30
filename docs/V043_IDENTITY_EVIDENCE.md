# 模块 0.3.3：身份隐藏 + GNSS/NMEA/WiFi/基站/传感器证据 + 单测补齐

> **2026-10-30 复核更正：0.3.3 未通过验收。下文 §1–9 是原实现记录，不能作为生效证明。真实缺陷、0.3.4 修复和验证状态见 §10。**

> 承接 0.3.2（PendingIntent / 一次性取消 / 开关与 provider 查询）。本轮按用户要求"依次全做"，
> 覆盖：隐藏模拟身份、GNSS/NMEA、WiFi、基站、传感器，并把纯逻辑测试补到 27 项。
> **构建通过、单测全绿；设备离线，运行期未验证。** 应用进程钩子要求目标 App 在 LSPosed 作用域内。

## 1. 身份隐藏（identity hiding）

| 层 | 做法 | 位置 |
|---|---|---|
| 产出侧（system_server） | 生成/替换 Location 时按 `reportMock` 决定是否带标记；`reportMock=false`（兼容性测试格式）时所有 parcelled 结果不带 mock 标记 | `FakeLocationFactory.kt`、`HookInstaller`（已有） |
| AppOps | hook `AppOpsService.checkOperation/noteOperation`：`OP_MOCK_LOCATION` 读成 **MODE_IGNORED** | `EvidenceHooks.kt` |
| 应用进程读数 | hook `Location.isMock()` / `isFromMockProvider()` → false；`Settings.Secure.getString/getInt("mock_location"/"allow_mock_location")` → null/0 | `AppScopeHooks.kt` |
| 状态同步 | system_server 把 `enabled/hideMock/lat/lng/spd/sat` 写到 `Settings.Global["mockgeo_state_v1"]`，应用进程每 2s 内复用快照 | `StatusWriter.kt`、`AppScopeConfig.kt` |

生效条件：**模拟开启 + 兼容性测试格式（reportMock=false）**。这是设计选择——标准模式仍然显式标记模拟，便于透明测试；隐藏身份是显式开关。

## 2. GNSS / NMEA

- **NMEA 回灌（应用作用域）**：包装 `LocationManager.addNmeaListener` 的监听器，模拟期间每秒回灌 `$GPGGA` / `$GPRMC`（坐标确定性生成、校验和正确、速度换算成节）。室内/GPS 关闭时原本没有语句，SDK 会判定"无卫星证据"，这里补上节奏。
- **GnssStatus 读数（应用作用域）**：hook `getSatelliteCount/getSvid/getCn0DbHz/getConstellationType/getAzimuthDegrees/getElevationDegrees/hasAlmanacData/hasEphemerisData`，与配置的卫星数一致。
- **不伪造回调**：构造 `GnssStatus` 需要 native 支撑，本层不合成 `onSatelliteStatusChanged` 回调——这是与 native HAL 的边界，留给下一个层级。

## 3. WiFi / 基站（system_server）

| 目标 | hook | 合成 |
|---|---|---|
| `WifiServiceImpl.getScanResults` | 返回 | 7 个 AP（SSID 固定、BSSID 由坐标派生、level/frequency/capabilities/timestamp） |
| `WifiServiceImpl.getConnectionInfo` | 返回 | `WifiInfo`：SSID/BSSID/RSSI/链路速率/频段 |
| `PhoneInterfaceManager.getAllCellInfo` | 返回 | `CellInfoLte` + `CellIdentityLte`（mcc=460，mnc/ci/pci/tac 由坐标派生）+ 信号强度 |
| `PhoneInterfaceManager.getCellLocation` | 返回 | `GsmCellLocation` + Bundle(lac/cid) |

全部反射构造、独立 try/catch：ROM 类名/构造器不匹配时只记日志，不影响 system_server。坐标派生保证**同一位置每次一致**（避免"每次扫描换一批 AP"）。

## 4. 传感器（应用作用域）

包装 `SensorManager.registerListener` 的 `SensorEventListener`（动态代理）：
- `TYPE_STEP_COUNTER`：`values[0]` 改为按会话时长累计的步数（速度>0 时每秒 1 步）；
- `TYPE_STEP_DETECTOR`：按 500ms 节奏注入 `1`；
- 注销时把原 listener 映射回代理，避免残留。
只改事件值、不伪造事件本身，`onAccuracyChanged` 等其余回调原样转发。

## 5. LocationAvailability

- system_server：hook `LocationAvailability.writeToParcel`，模拟中把 `mIsLocationAvailable` 置 true 后再 parcel；
- 应用作用域：hook `LocationAvailability.isLocationAvailable()` → true（模拟中）。

## 6. 测试

| 测试 | 数量 | 覆盖 |
|---|---|---|
| `ModuleConfigTest` | 10 | revision 单调、fail-safe |
| `ProviderResolverTest` | 6 | provider 解析 |
| `EvidenceTemplatesTest`（本轮新增） | 11 | 身份判定、NMEA 校验和/格式/速度换算、BSSID 确定性、小区取值范围、步数、状态 JSON 解析 |
| 合计 | **27** | 全绿 |

仍缺：PendingIntent 注册表、取消时序、AppOps/开关钩子的**运行时**测试（需要 Robolectric 或真机）。

## 7. 验证状态

- ✅ `:module:assembleDebug`、`:module:testDebugUnitTest` 通过（27 项）。
- ❌ 设备离线（`adb devices` 为空），**所有运行期行为未验证**：AppOps 返回值、应用进程钩子是否装载、WiFi/基站反射构造是否匹配本机 ROM、NMEA 回灌是否被目标接受。
- ⚠️ 应用进程钩子依赖 LSPosed 作用域：默认 `scope.list` 只有 `system`，需要在 LSPosed 管理器里把目标 App 加入作用域。

## 8. 设备重连后的验收

```bash
# 1) 确认加载 0.3.3 + 两层钩子都装了
adb shell su -c "logcat -d -s MockGeoModule:* | grep -E 'install finished|evidence hooks installed|app-scope hooks installed'"
# 2) AppOps 隐藏（模拟中查询应看到 MODE_IGNORED）
adb shell su -c "cmd appops get com.mockgeo.app android:mock_location"
# 3) 状态镜像
adb shell settings get global mockgeo_state_v1
# 4) WiFi/基站
adb shell su -c "dumpsys wifi | grep -i scan" ; adb shell su -c "dumpsys telephony.registry | head -40"
# 5) NMEA（目标进程日志里应出现 $GPGGA/$GPRMC）
```

判定顺序：**先确认 0.3.3 已加载** → 应用加入作用域 → 开兼容性格式 → 用 probe 校准 `isMock`/`Settings.Secure` → 再看目标 App。

## 9. 边界

- 不合成 `GnssStatus` 回调、不做 native HAL、不改传感器原始事件（只改步数类 values）。
- WiFi/基站的类名与构造器随 ROM 变化，本轮按 AOSP 名称尝试，失败会记录到 logcat。
- 扩大覆盖 ≠ 目标接受：IP、服务端风控、SDK 自算仍可能让"看起来没变"。

## 10. 0.3.3 复核与 0.3.4 修复（2026-10-30）

用户先要求核验，再明确“核验有 bug 就修复”。本轮没有改动兼容性开关默认值，不读取用户私有目标的名称、页面或定位数据。

### 10.1 真机发现，不只是静态推断

Xiaomi 15 / Android 16（API 36）连接正常。开始验收时主应用安装 0.4.1，模块安装 0.3.1，但 instrumentation 回执确认 **system_server 仍加载 0.3.0**。旧版定点与 GPS 监听成功，GPS 一次性定位返回 null；finally 已确认 enabled=false。证据：`_tools/gap-audit-device-smoke.log`。

将 0.3.3 APK **仅复制到临时目录，在独立 app_process 中读取代码与系统签名**，没有把它安装或加载进 system_server。被核验 APK SHA256：`41696836005e4f8b1866604d8ea948d75cbfac842c318825235f67a975bcf012`。证据：`_tools/gap-audit-runtime-033.log`。

| 0.3.3 缺陷 | 真实证据/后果 | 0.3.4 处理 |
|---|---|---|
| PendingIntent hook 方法名遗漏 | 本机调用 `registerLocationPendingIntent/unregisterLocationPendingIntent`，原 capture/release 表没有它们 | 补齐真实入口，注册/注销均走框架校验 |
| AppOps 返回类型错误 | `noteOperation` 返回 `SyncNotedAppOp`，不能返回整数 1 | 保留 note 操作原行为，只处理返回 int 的 checkOperation；先执行原方法 |
| Wi-Fi 返回类型错误 | 本机扫描服务返回 `com.android.wifi.x.com.android.modules.utils.ParceledListSlice`，不是 List | 适配原返回类型，不匹配则回退；不跳过原权限检查 |
| 基站类名和进程错误 | 本机是 `com.android.phone.PhoneInterfaceManager`，不在 system_server；getCellLocation 返回 CellIdentity | 改为作用域内 TelephonyManager 公开 API 路径，不盲挂 system_server 类名 |
| 基站构造必然失败 | 对 CellIdentityLte 查找不存在的 setCellIdentity；GsmCellLocation 不是 Parcelable | 在 CellInfoLte 上设置 identity，返回正确的 CellLocation 对象；补时间、注册状态、信号字段 |
| LocationAvailability 类名错误 | `android.location.LocationAvailability` 本机不存在 | 仅在含 GMS SDK 的作用域进程尝试 `com.google.android.gms.location.LocationAvailability`；不冒称系统层已覆盖 |
| 客户端停止与重启保护缺失 | 旧 ts=1 的状态仍解析 enabled=true，2 秒缓存 TTL 不等于租约过期 | 镜像加入单调时间、boot count、会话号；严格解析、15 秒过期、重启后失效；STOP 镜像独立于状态 provider 写入 |
| NMEA 无注销/错误线程/重复包装 | 原代码先加入永久列表、再执行可能失败的注册；没有 remove hook；无 executor 调度 | 成功注册后才调度；稳定代理、重载重入保护、匹配注销；按指定线程投递，队列消费前重查停止状态 |
| NMEA 内容不一致 | RMC 日期固定 010126；德国区域数字使用逗号；卫星固定 08、海拔与航向被写死 | UTC 日期与 Locale.US；卫星、海拔、航向来自同一配置 |
| GNSS 索引不完整 | 改卫星数却没有覆盖 usedInFix/carrier/baseband，可能读原短数组越界 | 所有现有公开索引 getter 使用同一合成集合，并检查索引 |
| 传感器停止后仍改值 | 代理回调不检查 enabled；只 hook 单参数注销；重载可多次包装；静止累计步数归零 | 一个 listener 一个代理；两类注销和 flush；停止直通；暂停保持累计值；回调后恢复共享事件数组 |
| 一次性生命周期判断不适配 | 本机 ICancellationSignal 是返回值，原取消观察器扫描参数永远为空 | 保留框架客户端 CancellationSignal，桥接回调后取消原请求；实际取消语义由独立探针验证 |
| 单次监听仍可能重复投递 | 现代 requestSingleUpdate 变成 maxUpdates=1 的普通注册，服务没有同名方法 | 从 LocationRequest 读取次数/时限/间隔/距离；单次回调共用门控，结束时清理原订阅 |
| API 兼容检查失败 | module Lint 对 defaultClassLoader 报 2 个 NewApi 错误（最低 API 26） | 使用 onPackageReady 的实际 classLoader；不压制或忽略 Lint |

### 10.2 验证方法

- JVM：原有模块测试 + `CompatibilityRegressionTest`，包含状态过期/重启、严格解析、真实日期与区域格式、返回包装类型、单次/时限/间隔/距离、暂停步数。
- 真机隔离检查：`_tools/device-probe/Run-Audit.ps1`，检查真实 ROM 类/方法及 0.3.4 的数据构造与 Parcel；不注入 system_server。
- 安装后系统验收：先确认实际加载 **0.3.4**。`rebuild/scripts/verify-compatibility.ps1` 强制检查加载版本，再分别开关兼容格式，以独立 UID 的公开 API 探针与生命周期探针验证。每次试验正常结束自动 STOP；不会重启手机或操作热点。
- 新增 `LifecycleProbeActivity` 检查 gps/network/fused 的 PendingIntent 连续回调、移除后停发、单次监听以及预取消/排队取消。报告仅含与测试坐标的匹配、标记、provider 与时序，不记录真实坐标。

### 10.3 仍需明确的能力边界

Wi-Fi 与基站值是确定性的测试样本，**不代表真实地理数据库里该坐标的 AP/小区**，不能据此保证网络定位 SDK 接受或反算到模拟坐标。该层只在兼容格式开启时使用，应用进程路径要求作用域授权。没有伪造 Wi-Fi 连接、基带注册或网络流量。

GNSS getter、NMEA 与步数改写不会凭空提供原始 GNSS 测量、没有订阅时的回调或传感器原始事件；本轮没有增加 native HAL。`GnssStatus.Builder` 在本机存在，因此原文“构造一定需要 native”的说法不成立；但主动卫星回调仍不属于本轮已验证功能。

GMS availability getter 不是完整的 FusedLocationProviderClient 回调适配。PendingIntent 的框架原生推送与合成推送仍须用真机验证交互，不能仅凭注册表和单测断言所有批处理/地理围栏场景通过。用户私有业务入口由用户自行测试；IP/服务端地区不由这些坐标接口决定。

### 10.4 本轮验证结果

- `gap-fix-034-release-check.log`：最终模块/测试包/独立探针构建，模块和探针 Lint、Android debug 单测通过。模块共 36 项单测，其中新增回归 9 项。
- `gap-fix-034-jvm-model-route.log`：模型与路线 JVM 测试也通过；与本轮 Android debug 测试合计 **135 项、0 失败**（不计旧的 release 测试报告）。
- `gap-fix-034-runtime.log`：12 项真机隔离检查通过，包括真实 Wi-Fi ParceledListSlice 包装、LTE 构造和 Parcel 往返。小米数字参数信号构造器会调用缺失的 MiuiSignalStrengthStub；已改用默认构造器并填写样本字段，复测通过。
- 0.3.4（versionCode 7）已完整覆盖安装；安装 APK SHA256 与发布包一致：`59e387116f7cee31456b33ccebee38a15b1c7a0c5bcfca27816cc8cb67bdda38`。新版测试包与独立探针也已安装。
- 发布目录：`rebuild/releases/module-0.3.4/`，含三份 APK 与 SHA256SUMS。主应用仍使用 0.4.1。
- **仍待用户手动重启后验收**：当前最后确认的 system_server 加载版本是 0.3.0；未将“安装成功”当作“0.3.4 已实际运行”。已请用户手动重启、恢复热点和 USB 后继续 `verify-compatibility.ps1`。本轮没有自动重启、网络/热点操作，也没有读取用户私有目标应用。
- 未记录为通过的系统注入/外部业务验收项目不得沿用旧版结果。上面的隔离检查只验证 API 适配与数据对象，不代表 0.3.4 已在 system_server 生效。
- 用户随后明确选择“稍后重启，先保留当前进度”。本轮停止需要重启的验证，保留已安装版本与测试入口；没有再请求或尝试重启。下次从确认运行版本开始，不重复旧版基线。

### 10.5 用户手动重启后的实际验收（2026-10-30）

**0.3.4 已确认加载。以下是本版真机结果，取代 §10.4 的待重启状态。** 主应用仍为 0.4.1，模块 APK 未再改动或重装，无额外重启。

1. `_tools/compat-034-device/smoke-after-reboot.log`：12 项引擎检查全部通过，包括 GPS/network/fused 一次性回调、GPS 监听、路线移动、暂停/恢复及停止回执。修复前 GPS 一次性返回 null 的问题，本版在该设备上不再复现。
2. 使用主应用正常前台服务，分别在设置界面关闭/开启兼容性格式；独立 UID=10321 的探针通过公开 API 消费位置，不从控制面取坐标。每次测试期间额外检查模块状态持续更新，结束后确认 STOP。

| 项目 | 标准格式 | 兼容格式 |
|---|---|---|
| gps/network/fused last/current/updates | 各接口均返回预期坐标，provider 一致 | 同左 |
| 公开 API 的 15 条原生 Location | isMock 全部 true | isMock 全部 false |
| WebView Geolocation | 返回预期坐标 | 返回预期坐标 |
| PendingIntent | 三个 provider 均收到多次正确位置 | 同左 |
| 单次监听 | 每个 provider 恰好一条正确位置 | 同左 |
| 预取消 / 排队后取消 | 无取消后的回调；预取消正常抛 OperationCanceledException | 同左 |
| 注销 | 排空允许的一秒在途窗口后无新回调 | 同左 |
| 停止 | enabled=false 已确认 | 同左 |

WebView API 不暴露 Android mock 标记，报告中的 null 不能解释为 false。PendingIntent 的计数含原生与合成事件，这些通过记录不代表承诺固定回调频率。

有效证据：`_tools/compat-034-device/normal-app/standard/` 与 `normal-app/compatibility/` 的 `report.json`、`lifecycle-report.json`，以及 `normal-app-run.log`、`normal-app-compatibility.log`。汇总为 `normal-app/summary.json`。

**无效实验的处理：**最初把 instrumentation 长时间保持在后台，同时切换探针，控制进程曾整进程暂停、续约过期。这些报告里的非预期位置发生在模拟已失效之后，不能据此认定 0.3.4 回调替换缺陷。已保留原始记录用于排障，没有拿它们当通过证据。探针误把预取消的 OperationCanceledException 记作错误，也已修正。

当前 `rebuild/scripts/verify-compatibility.ps1` 先执行短引擎版本检查，再由 `_tools/run_normal_probe.py` 启动真实前台服务并监视租约，以当前设置执行验证。`-Format standard/compatibility` 只校验所选格式，不偷偷改设置。旧 `_tools/verify_compatibility.py` 仅保留为长 instrumentation 暂停问题的诊断复现器，不是验收入口。

为用户下一轮手测，**兼容性测试格式保留开启，模拟已停止**。尚未验证用户私有网站/小程序/App 的业务接受结果；这些入口的 IP、缓存、服务端决策以及未加入作用域的客户端 SDK，不由本次公开 API 测试保证。应用进程 GNSS/NMEA/GMS 等钩子的端到端验证范围也没有因此扩大。

新验收入口 `verify-compatibility.ps1 -Format compatibility` 已实际完整执行通过，日志 `final-wrapper-check.log`；其中短引擎检查再次确认 0.3.4 并通过 12 项。测试 APK 与独立探针的最新构建已安装并更新至 `releases/module-0.3.4/`；模块仍为此前同一 SHA256，无须再次重启。
