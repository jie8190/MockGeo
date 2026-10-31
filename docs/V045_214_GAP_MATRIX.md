# 0.3.7：对标 2.1.4 的 hook 面补齐（含按包豁免）

> **后续核验更正：0.3.7 有真实功能缺陷，下面 §1–7 仅是原实现记录，不能视为生效证明。0.3.8 修复与验证见 §8。**

> 本轮按用户要求"对标 2.1.4 补齐缺口"，不做真机验证，交给另一 AI 检查与验收。
> 验证方式与安装方式见 §5、§6。

## 1. 对账方法

从 2.1.4 的盲 hook 表（`sd1.smali` 的 `const-string` 方法名）提取全部目标方法，
与 `HookInstaller` 的 hook 集合逐项比对。2.1.4 的完整名单：

```
addGnssAntennaInfoListener, addGnssBatchingCallback, addGnssMeasurementsListener,
addGnssNavigationMessageListener, asList, chooseBestLocation, clearTestProviderEnabled,
clearTestProviderLocation, create, deliverOnLocationChanged, getBestProvider,
getBlurryCellInfos, getBlurryCellLocation, getCellType, getCidFromCellIdentity,
getCurrentLocation, getLastLocation, getLastLocationWithPackage, getLocationProviderManager,
gps, hasProvider, ILocationCallback, InternalState, is_mock, isProviderEnabledForUser,
isProviderPackage, LocationListenerTransport, LocationResult, mockProvider,
onReportLocation, onReportLocations, registerGnssNmeaCallback, registerGnssStatusCallback,
registerLocationPendingIntent, removeGnssAntennaInfoListener, removeGnssMeasurementsListener,
removeGnssNavigationMessageListener, removeGnssStatusListener, removeTestProvider,
reportLocation, reportLocations, requestPendingIntentFlush, sendExtraCommand,
setComplete, setExtraLocationControllerPackage, setExtraLocationControllerPackageEnabled,
setRefLocation, unregisterGnssNmeaCallback, unregisterGnssStatusCallback,
unregisterLocationPendingIntent, updateLocation, writeToParcel
```

## 2. 本轮补齐的缺口

| # | 2.1.4 目标 | 0.3.7 实现 | 说明 |
|---|---|---|---|
| 1 | `hasProvider` | `FORCE_PROVIDER` | 模拟中 `gps/network/fused/mockgeo` 恒 true |
| 2 | `removeTestProvider` / `clearTestProviderEnabled` / `clearTestProviderLocation` | `GUARD_PROVIDER` | provider 为 `mockgeo` 时吞掉调用，防第三方清理 |
| 3 | `requestPendingIntentFlush` | `FLUSH` | 调用后补发 `flushComplete` 到 PendingIntent |
| 4 | `reportLocations` / `deliverOnLocationChanged` / `setRefLocation` | 加入 `reportNames` | 上报/派发/参考位置替换 |
| 5 | `registerGnssNmeaCallback` / `unregisterGnssNmeaCallback` | NMEA 别名 | 新 API 的 NMEA 注册名 |
| 6 | `removeGnssStatusListener` | GNSS 注销别名 | 覆盖旧接口命名 |
| 7 | `getBlurryCellInfos` / `getBlurryCellLocation` | 基站模糊定位 | 与 `getAllCellInfo/getCellLocation` 同一套合成 |
| 8 | 按包黑/白名单（`ri` + `assets/blacklist.enc`） | `Exemptions` + `Settings.Global` 名单 | 名单内 App 继续拿真实数据 |

## 3. 按包豁免（对标 2.1.4 `ri`）

- 名单文件：`/data/local/tmp/mockgeo_exempt.txt`（每行一个包名，`#` 注释）。
- system_server：`Exemptions.isCallerExempt()` 用 `Binder.getCallingUid()` 反查包名；
  命中则所有非命令 hook 直通（`getLastLocation`、订阅、provider 查询、WiFi/基站等）。
- 应用作用域：system_server 把名单写进 `Settings.Global["mockgeo_state_v1"]` 的 `"x"` 字段，
  `AppScopeConfig` 解析后由 `ScopeState.activeFor(pkg)` 决定是否注入。
- 已知近似：provider 上报替换没有调用者身份（系统线程），这部分是全局行为，无法按包豁免——
  与 2.1.4 的限制相同；豁免只覆盖"应用发起的查询/订阅/推送注册"。

## 4. 仍未覆盖（明确记录）

| 项 | 原因 |
|---|---|
| native HAL（`libnl.so` 式 GNSS HAL/计步 hook） | 模块是纯 Java/Kotlin，无 native 注入能力；需 NDK + 注入 + ROM 校准 |
| `addGnssMeasurementsListener` / `addGnssNavigationMessageListener` / `addGnssAntennaInfoListener` / `addGnssBatchingCallback` | 只捕获不合成：生成 `GnssMeasurementsEvent`/`NavigationMessage`/`AntennaInfo` 需要构造隐藏事件对象，当前无真机验证窗口，先不做"假事件" |
| `setComplete` / `setExtraLocationControllerPackage*` / `getLocationProviderManager` / `getCellType` / `getCidFromCellIdentity` | 2.1.4 内部辅助/固件相关，非 App 可见注入面 |
| `getBlurryCellInfos` 之外的地理围栏/批量接口 | 超出本轮范围 |

## 5. 测试

- 单测 **38 项全绿**：`CompatibilityRegressionTest` 9 + `EvidenceTemplatesTest` 13
  （新增豁免名单解析/`activeFor` 2 项）+ `ModuleConfigTest` 10 + `ProviderResolverTest` 6。
- 未做真机验证（用户明确本轮不验）。

## 6. 安装方式（重要）

模块更新**必须**用 `gradlew :module:installDebug`（Android Studio 里需关闭部署优化），
直接 `adb install -r` 会让 LSPosed 记录指向旧 APK 路径，表现为"已安装但加载的是旧代码"。
已提供脚本：`rebuild/scripts/install-module.ps1`。装完必须重启。

## 7. 交给验收方的检查清单

```bash
# 1) 版本与加载
adb shell su -c "logcat -d -s MockGeoModule:* | grep -E 'install finished|evidence hooks installed'"
adb shell settings get global mockgeo_state_v1      # 应含 "x":"..." 名单
# 2) hasProvider / 开关
adb shell cmd location is-provider-enabled gps
# 3) 豁免名单
echo com.example.app | adb shell su -c "cat > /data/local/tmp/mockgeo_exempt.txt"
# 名单内 App 应继续拿到真实坐标；名单外拿到模拟坐标
# 4) flush / provider 保护
# 用 probe 的 LifecycleProbeActivity 走一次性+订阅+flush 路径，观察 logcat
```

## 8. 0.3.7 核验及 0.3.8 修复

本轮用户明确授权核验、修复及真机测试。手机起初安装并实际加载的是 **0.3.6**，不是工作区的 0.3.7；12 项基础引擎检查通过（`_tools/audit-037-installed-smoke.log`），不能据此声称 0.3.7 新功能通过。

### 8.1 已证实的问题与修复

| 问题 | 核验依据 | 0.3.8 修复 |
|---|---|---|
| GNSS 合成始终无法选择正确构造入口 | SDK 与真机 `GnssStatus.Builder.addSatellite` 是公开的 12 参数方法；0.3.7 找 7 参数，数组构造器类型也错误 | API 30+ 使用公开 Builder；旧 API 使用正确的打包标志/数组构造方式；尊重配置的 0–64 颗卫星 |
| 系统 GNSS/NMEA 根本没有接到注册入口 | `GnssPusher.register*` 没有调用方；HookInstaller 缺注册名单；实际 Binder 接口为 IGnssNmeaListener | 补齐 GNSS/NMEA 注册与注销；先由框架验证权限/注册结果，再记录接收者 |
| 系统回调方法名错误 | 真机是 onGnssStarted/onGnssStopped/onSvStatusChanged，原实现使用客户端 onStarted/onSatelliteStatusChanged | 按实际 Binder 接口调用；注销/死亡清理；STOP 后重启同一订阅重新发启动事件 |
| 两层 GNSS/NMEA 合成可能重复且客户端注册成功判断错误 | 应用层还保留额外 pump，且把 Boolean 注册结果按 null 判断 | 合成为系统侧一条路径，删除重复客户端泵和全局 GNSS getter 改写 |
| “豁免后拿真实定位”的承诺不成立 | 全局上报/asList/Parcel 改写会污染共享数据；推送没有保存接收者 UID；豁免还跳过注销 | 只改每位接收者的副本；保留原系统缓存；持续按接收者 UID 检查策略；注销始终处理。系统/定位硬件提供者不接收模拟输入，避免上游融合缓存污染 |
| 系统 AppOps/Wi-Fi 与应用 GMS/基站没有完整遵守豁免 | 原 Exemptions 判断只在 HookInstaller，其他安装器未接入 | 补齐两层策略；共享 UID 的包一起豁免。已经交付进应用缓存的旧结果无法追溯修改 |
| 豁免文件和镜像解析不可靠 | shell 临时目录不适合作为持久系统配置；重复 x 字段/多余逗号可能被接受；读错直接清空名单 | 安装时准备 `/data/misc/mockgeo/exempt.txt`，system 所有、目录 750、文件 640、正确 SELinux 标签；存在旧文件时迁移且不覆盖已有配置；严格解析，失败保留上次策略 |
| flush 回执类型错误且重复 | 系统原生回执应含整数 request code；0.3.7 在原调用后额外发送 boolean true | 保留框架原生 flush，不造第二份回执；独立探针检查每位 provider 的 request code 与次数 |
| hasProvider/清理保护宣称了不存在的 provider | mockgeo 是命令通道名，不是真正 Location provider；吞掉调用还跳过原检查 | 查询/清理尊重真实框架结果；不伪造可用 provider 或跳过权限检查 |
| 传感器数学不自洽 | 航向四元数绕 Y 轴，磁力相位不一致；加速度 ≠ 重力 + 线性加速度；恒定航向仍有陀螺摆动 | 使用 Android Z 轴航向约定，统一重力与线性加速度；陀螺根据真实配置航向变化；支持相关校准/未校准输出并恢复共享数组 |

签名证据：`_tools/audit-037-framework.log`。0.3.7 原代码备份：`_tools/review-037-original/`。

### 8.2 构建及安装验证

- `fix-038-release-check.log`：模块/探针构建与 Lint 通过，JVM/debug 共 **145 项测试，0 失败**；模块 46 项。
- `fix-038-runtime.log`：**23 项真机独立进程检查通过**，包括 0/1/12/32/64 卫星构造与 Parcel 往返，以及通过 Android 官方矩阵 API 验证航向。独立进程检查不等于系统钩子已经生效。
- 使用 `rebuild/scripts/install-module.ps1` / `:module:installDebug` 完整安装 **0.3.8 / versionCode 11**。模块 SHA256：`cfc895d778e24fa3623b0d584f250213cf1ab7de189da917e62d306fdd1e314d`，设备与构建文件一致。新探针与测试包也已安装。
- 更正 §6：完整 `adb install -r` 本身也是正常 APK 安装；Android Studio Apply Changes/部署优化与完整安装不同。无论用 Gradle 还是 ADB 安装，都不能替换已经加载在 system_server 中的旧类，仍需手动重启验证加载版本。

### 8.3 真机回归入口及当前状态

用户需手动重启恢复 USB/热点，并将**我们自己的** `com.mockgeo.probe` 加入模块作用域，才可验证应用内传感器。没有读取用户私有目标 App、网址或小程序。

```powershell
rebuild/scripts/verify-compatibility.ps1 -RequiredModule 0.3.8 -Extended -RequireSensors
```

先验证实际加载版本与基础定位/flush，再测 GNSS、NMEA、注销、同一批订阅的“正常→豁免→恢复→停止→重启模拟→注销”。豁免测试只临时添加 com.mockgeo.probe 并在 finally 恢复原文件，不修改其他包的策略。结果目录 `_tools/compat-038-device/`。

**当前仍待手动重启后的上述回归。** 不把旧 0.3.6 的基础通过或独立进程构造通过当作 0.3.8 注入生效。未增加 native HAL；未验证 GNSS 原始测量/导航消息/天线、GMS 全链路或用户私有业务接受结果。

### 8.4 0.3.8 重启后实测与后续修复

用户已手动重启并将 com.mockgeo.probe 加入作用域。实际加载 0.3.8，短引擎 12 项通过；公开 GPS/network/fused 与 WebView 通过，flush 的整数 request code 710/711/712 各收到一次。

但生命周期测试抓到 **GPS PendingIntent 首次回调（约 94ms）的一条非预期位置**，不能宣称整轮通过。0.3.9 增加按 PendingIntent 接收者 UID 处理发送载荷的补充路径，仍只修改位置副本并遵守动态豁免；不回退为全局缓存/Parcel 改写。此修复需再次手动重启后验证，编译通过不等于首帧问题已经实测消失。

独立 GNSS/NMEA/传感器检查在 0.3.8 上通过：9 份匹配的卫星状态、各 9 份正确的 GGA/RMC、6 类传感器各 3 个匹配样本，启动与 first-fix 各一次，注销后无残余回调。证据 `_tools/compat-038-device/extended/gnss-report.json`。

动态策略测试初稿还有两处测试缺陷：阶段标记晚于策略实际生效，且以 NMEA 字符串精确比较导致浮点舍入误报。已增加独立 transition 阶段并按距离校验 NMEA；初稿保留在 `initial-extended/`，不作为模块失败或通过的最终依据。

修正后的动态策略复测通过（`_tools/compat-038-device/extended-recheck.log`、`extended/policy-report.json`），并确认原豁免名单恢复、模拟停止。0.3.9 模块构建、46 项模块测试及 Lint 通过（`fix-039-build.log`）；补充发送载荷修复仍待新版本实际加载后复测。后续入口：`verify-compatibility.ps1 -RequiredModule 0.3.9 -Extended -RequireSensors`。

### 8.5 0.3.9 实测发现 ROM 监听器重建路径，修复为 0.3.10

0.3.9 已加载并通过 12 项引擎检查，但公开探针的 GPS 连续更新首帧在约 154ms 收到非预期位置（accuracy=30），之后才收到正确结果。动态策略复测也在重新开始模拟后观察到 network/fused 的原生与合成结果交错。证据：`_tools/compat-039-device/acceptance.log`、`normal-app/compatibility/report.json`、`extended/policy-report.json`。本轮 GNSS/NMEA/六类传感器仍通过，不能据此掩盖定位回调失败。

读取本机系统 JAR 后确认原因：

- `LocationProviderManager.registerAndHandleIdentity` 从 `listener.asBinder()` 调用 `ILocationListener.Stub.asInterface` 创建了新的原始接口，再交给小米的 `LocationProviderManagerStub.registerLocationRequest`，此前的 Java 代理被绕过。
- 小米实现再交给 `GnssBackgroundUsageOptStub` 和 `GnssPowerOptimizeStub`，用于后台恢复与快速定位。`LocationManagerServiceImpl` 的恢复回调也会把该接口重新注册。
- 因此只包装 LMS 的入参、或只补 PendingIntent 发送路径都不足以覆盖这些原生监听器回调。

本机静态证据保留在 `_tools/rom-location-audit/`（仅系统代码，没有用户应用数据）。0.3.10 在经过 LMS 校验的 provider 注册边界保存 Binder 接收者与共享回调额度，在 `ILocationListener.Stub.Proxy.onLocationChanged` 的实际发送出口应用同一策略。原代理与底层出口之间有重入保护，不会把同一次单次定位计数两遍；注册失败可回滚，注销与 Binder 死亡清理记录。没有恢复全局 provider 缓存改写。

新增 4 项回归测试覆盖接口重建共享额度、多 provider、失败回滚及并发新订阅。最终 JVM/debug **149 项通过**（模块 50 项），构建与 Lint 通过，见 `fix-0310-final-check.log`。**0.3.10 补丁仍须实际加载后完成真机回归**；不得沿用 0.3.8/0.3.9 的通过部分来声称全通过。

0.3.10 已完整安装并核对设备哈希：`d6ffeaaf054fcb2db9b076ce682dd0ec964f069b745f850e8c891e61557f9cdd`。用户选择稍后手动重启、先保存进度。当前模拟关闭、原豁免策略已恢复。下次运行 `verify-compatibility.ps1 -RequiredModule 0.3.10 -Extended -RequireSensors`，先确认实际加载版本，再复测；本轮没有自动重启或热点操作。


### 8.6 2026-10-31 最新工作区复核：0.4.2 / 0.3.11 候选

后续工作已产生 `compat-0310-device` 报告，本次重新运行四个现有校验器，公开定位 API、生命周期/flush、GNSS/NMEA/传感器、动态策略均通过。因此 §8.5 的“0.3.10 等待重启”是历史状态；本次开始时 USB 列表为空，未重新连接、重启或操作热点。历史报告不构成这次新补丁的真机验收。

| 已确认问题 | 修复与验证范围 |
|---|---|
| Root 模式只是复制 SO、初始化应用进程中的 ART 库，然后使用 LSPosed 的控制通道；没有调用跨进程注入器。自检还允许没有 Root、仅系统模块可达时成功 | RootEngine 保留旧配置 ID，但自检/启动明确失败，UI 标记开发中；删除自检中的 su 部署和全局 hidden_api_policy 修改；不再借用 LSPosed 的回执。`root-check` 不再给出假阳性的端到端通过。**Root 独立注入仍未实现** |
| RootXposedBridge 无桥接槽或 Native 返回 false 时仍返回成功 Handle，且先改原生入口、后发布回调表，存在首个回调查不到记录的窗口 | 无槽/Native 失败抛出错误；先发布 dispatch 元数据、失败回滚/释放槽位；同一方法跨 Bridge 实例共享安装，槽位按 Method（含 ClassLoader 身份）索引；类初始化 Hook 明确报告不支持 |
| RecipientTable 以 Subscription 对象判断回滚归属；同一对象再次发布后，旧回滚可删掉新注册；外层 remove 与内层 publish 也不是一个原子操作 | 每次发布有独立身份，按 Binder 原子更新不可变 provider 映射；旧回滚不能删除或复活新一轮订阅。两项新回归覆盖复用订阅对象和注销后重注册 |
| SettingsRepository 缺省把高德授权读作 true；设置页直接写 true，跳过已有授权弹窗；缺 Key 入口保存后不切图 | 默认未授权，HomeScreen 统一处理一个授权弹窗；保存 Key 后继续用户原来的切图意图；切换 Key/地图后重新定位到已选点；没有内置 Key 时不再提示“已恢复默认服务” |

测试：`RootEngineTest`、`RootXposedBridgeTest`、`RecipientTableTest`、`SettingsRepositoryTest` 及现有回归。完整验证日志在工作区 `_tools/audit-latest-check.log`，最后增量日志 `_tools/audit-latest-final-check.log`。新候选必须另行安装和真机复测，不能继承 0.3.10 的运行结论。

后续真机顺序：完整安装 0.4.2 主应用、0.3.11 模块及新版测试包；用户手动重启并恢复热点/USB后，运行：

```powershell
rebuild/scripts/verify-compatibility.ps1 -RequiredModule 0.3.11 -Extended -RequireSensors
```

继续保留系统框架和自有 `com.mockgeo.probe` 作用域。重点复查 GPS 首帧、停止/恢复订阅、单次配额、取消/注销；单独检查高德首次授权、个人 Key 保存/清除/更换、选点保持，以及旧 Root 配置能明确报错并切回可用模式。不得自动重启或恢复热点，不读取用户私有目标。


本轮后续设备已连接，最终安装主应用 0.4.2 与模块 0.3.11，并核对设备 APK 哈希。160 项 JVM/debug 测试、构建及 Lint 通过。0.4.2 的 12 项定位真机检查在 **加载中的 0.3.10** 上通过；高德保持前台后 19 项通过，首次后台超时记录保留。详见工作区 `WORKLOG.md` §21.1 和 `_tools/audit-042-*-device.log`。当前模拟关闭；**0.3.11 模块仍待手动重启后的独立验收**。完整配置/更换 Key 的 UI 流程未在本轮真机完整走完。


### 8.7 0.4.3 的纯 Root 系统定位验收

Root载荷0.1.0在实际system_server（PID3465、UID1000）加载24个钩子并取得独立回执；LSPosed无驻留。8项基础检查、独立探针GPS/network/fused与WebView、PendingIntent首帧/单次/持续、取消/注销及flush通过。GNSS/NMEA与动态豁免、停止、恢复同一订阅也通过。结束时原豁免策略已恢复、模拟关闭，系统PID不变，SELinux Enforcing。

完整证据在工作区 `_tools/root-010-device/` 和 `WORKLOG.md` §24。这里没有将应用进程传感器模拟计入Root通过项，也没有验证用户私有业务入口或其他ROM。已安装主应用仍为0.4.3，不需要再次安装APK。
