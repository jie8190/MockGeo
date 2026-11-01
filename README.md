# MockGeo

<p align="center">
  <strong>Android 现代化高真实度位置与路线模拟工具</strong><br/>
  支持 <b>标准模式</b> · <b>LSPosed 系统级钩子</b> · <b>Root 直连（实验）</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026--36)-3DDC84?logo=android&logoColor=white" alt="Android 8.0+"/>
  <img src="https://img.shields.io/badge/Kotlin-2.2%20%2B%20Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/NDK-C%2B%2B23%20%2F%20LSPlant-00599C?logo=cplusplus&logoColor=white" alt="NDK C++23 / LSPlant"/>
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License MIT"/>
</p>

---

## ✨ 核心特性

### 1. 运行模式
- **LSPosed 模式 (`xposed`)**：需要支持 `libxposed API 102` 的框架，注入 `system_server`，在系统定位服务出口直接合成并分发位置，无需污染系统底层 Provider 缓存，**停止模拟后无需重启即可立即恢复真实物理位置**。
- **Root 直连 (`root`，实验)**：主应用内置 ARM64 加载器和系统定位载荷，通过 Root 加载并校验独立的系统进程回执，无需另装 MockGeo 模块 APK。Root **0.1.0** 已在 Xiaomi 15 / Android 16 / KernelSU 上完成隔离进程及真实系统定位验收；本版 **0.1.1** 更新了共用钩子，尚未单独完成纯 Root 真机复测。当前拒绝与驻留的 LSPosed ART 框架叠加运行，需要先停用 LSPosed 注入并手动重启，或继续使用 LSPosed 模式。Root 路径暂不覆盖应用进程内传感器/SDK 缓存钩子。
- **标准模式 (`provider`)**：无需 Root，通过 Android 系统「开发者选项 → 模拟位置信息应用」运行。

### 2. 深度无痕与多维环境证据链同步
LSPosed 模式提供下列可配置的模拟输出。系统接口验收不代表所有第三方 SDK 或业务都会接受结果，位置模拟也不会改变 IP 地区：
- **抹除 Mock 标记**：自动清除 `Location.isFromMockProvider` / `isMock()` 与 `Bundle("mockLocation")` 标记，拦截 `AppOpsManager` 的 `OP_MOCK_LOCATION` 审计。
- **全通道定位覆盖**：同时接管 `GPS`、`Network`、`Fused（GMS/厂商融合定位）`、`Passive` 的 `getLastLocation`、`getCurrentLocation`、`requestLocationUpdates`（含 MIUI / HyperOS `LocationProviderManager.registerAndHandleIdentity` 代理对象精确拦截）以及内核 `WebView navigator.geolocation`。
- **GNSS 卫星与 NMEA 报文合成**：动态合成 GPS (`L1 1575.42MHz`) + 北斗 Beidou (`B1I 1561.098MHz`) 双星座卫星状态（信噪比 `C/N0`、仰角、方位角、`usedInFix`）及 `$GPGGA` / `$GPRMC` 标准 NMEA 原始语句。
- **虚拟基站与 Wi-Fi 扫描对齐**：根据当前模拟坐标确定性派生 4G LTE 基站小区（`MCC/MNC/TAC/CI/PCI` 与 `RSRP/RSRQ` 信号强度，兼容小米 `getBlurryCellInfos`）与周边 Wi-Fi 热点扫描列表。
- **步频与运动传感器协同**：路线移动与摇杆控制期间同步驱动加速度计、陀螺仪、旋转矢量、地磁、线性加速度、重力共 6 类传感器微幅姿态响应。

### 3. 为日常高频使用打磨的交互体验
- **双地图引擎自由切换**：
  - **OpenStreetMap**：开箱即用，免任何密钥，支持全球地图浏览、Photon/Nominatim 搜索与 OSRM 道路规划。
  - **高德地图 3D**：支持标准/卫星图层、国内 POI 精准搜索与步行/骑行/驾车沿路规划；内置 **「个人高德 Key 自定义配置」**（一键查看并复制本机包名与签名 `SHA1`）。
- **坐标智能粘贴与火星坐标自动反算**：
  - 支持直接粘贴高德/百度坐标拾取器文本（自动识别 `经度,纬度` 顺序并调换）；
  - 支持 **`WGS 84`**、**`高德/腾讯 (GCJ-02)`**、**`百度 (BD-09)`** 三种输入坐标系自动反算为精准 WGS-84 坐标，彻底消除 300~600 米偏移。
- **人性化模拟参数面板**：
  - 所有参数滑块支持 **点击数值直接输入精确值**、**单项/全局一键恢复默认** 与 **常用场景快捷档位胶囊**（步行/跑步/骑行/驾车速度、平原/山地海拔、室内/常规/强卫星信号、高频/标准更新间隔、GPS 微幅坐标浮动）。
- **多模式移动与轨迹管理**：
  - 支持 **定点模拟**（收藏夹与历史记录支持 `▶` 一键立即模拟）、**多途经点沿路规划**（单程/往返/循环 + 起步终点正弦平滑加减速）、**全局悬浮窗摇杆**（360° 方向与实时调速）以及 **GPX 轨迹导入/导出**。

---

## 📦 下载与安装

当前版本：主应用 **0.4.5** · 内置 Root 载荷 **0.1.1** · 可选 LSPosed 模块 **0.3.13**。

[下载 v0.4.5](https://github.com/jie8190/MockGeo/releases/tag/v0.4.5) · [更新日志](CHANGELOG.md) · [诊断验证](docs/VERIFICATION_0.4.5.md) · [定位与后台验证](docs/VERIFICATION_0.4.4.md)

| 安装包文件 | 包名 | 说明 |
| :--- | :--- | :--- |
| **`MockGeo-v0.4.5-app.apk`** | `com.mockgeo.app` | 主应用；Root 载荷已内置 |
| **`MockGeo-v0.3.13-module.apk`** | `com.mockgeo.module` | 仅 LSPosed 模式需要；完整安装后手动重启 |
| `MockGeo-probe-v1.2.apk` | `com.mockgeo.probe` | 可选：独立定位检查，供测试使用 |
| `MockGeo-v0.4.5-androidTest.apk` | `com.mockgeo.app.test` | 可选：配合仓库自动化验收脚本使用，无桌面入口 |
| `SHA256SUMS.txt` | — | 四个 APK 的 SHA-256 校验值 |

从正式版 0.4.3 升级时，LSP 用户需同步更新模块 0.3.13；Root 用户若已加载旧载荷，需手动重启后再启动新载荷。已加载内部版模块 0.3.13 / Root 0.1.1 的用户，只更新主包即可。保持原签名覆盖安装，无需卸载应用。

### 快速上手

1. 安装主应用；仅在选择 LSPosed 模式时安装对应模块 APK。
2. **激活系统级模式**：
   - **使用 LSPosed**：在 LSPosed 管理器中启用 **`MockGeo 模块`**，勾选 **`系统框架 (System Framework)`** 作用域并重启手机（如需对特定第三方 App 启用客户端传感器/Wi-Fi/基站直调拦截，可同时勾选对应 App）。
   - **Root 直连（实验）**：在 KernelSU / Magisk 中授予主应用超级用户权限；首次开始时加载内置载荷。当前需无 LSPosed 驻留的系统环境，不能将旧 LSPosed 回执当成 Root 成功。
3. 打开 **MockGeo** $\rightarrow$ 点击右上角 **偏好设置（齿轮）**：
   - 在 **「运行模式」** 中选择 `标准模式`、`LSPosed 模式` 或 `Root 直连模式`；
   - 开启 **「深度无痕模式（推荐）」**。
4. 返回地图主页，点选目的地或规划路线，点击右下角 **「开始模拟」** 即可生效；点击 **「停止模拟」** 立即恢复手机真实物理定位。

### 配置个人高德地图 Key（可选）
公开版默认使用免配置的 **OpenStreetMap**。如需在国内使用高德 3D 地图、POI 搜索与步行/骑行路线规划：
1. 打开 `MockGeo` $\rightarrow$ **偏好设置** $\rightarrow$ **地图与图层** $\rightarrow$ 点击 **「自定义高德密钥 (可选)」**；
2. 点击弹窗内的 **「查看申请 Key 所需的包名与签名信息」**，一键复制 `应用包名 (com.mockgeo.app)` 与 `签名 SHA1`；
3. 前往 [高德开放平台控制台](https://lbs.amap.com/) 创建应用并绑定上述包名与 SHA1，获取免费的「Android 平台 SDK Key」粘贴回弹窗保存即可。

### 熄屏后模拟停止

如果亮屏正常、熄屏后十几秒停止，请检查 **MockGeo 的应用省电策略**。小米/HyperOS 可在应用详情的省电设置中选择 **不受限制**；仅加入 Android「忽略电池优化」名单不一定有效。本机对照曾确认：主应用虽然保持前台服务和 CPU 唤醒锁，仍被自动省电冻结，随后因心跳中断而自动停止；改为厂商的“不受限制”后，控制心跳恢复稳定。详细测试条件见 [0.4.4 验证说明](docs/VERIFICATION_0.4.4.md)。

使用墓碑/冻结管理功能时，也应允许 MockGeo 在模拟期间运行。其它 ROM 的设置名称和效果需按实际设备验证，不要把一台设备的结果视为全部机型已适配。

### 诊断与反馈（0.4.5 起）

在 **运行状态 → 运行环境 → 后台持续运行** 可查看省电设置步骤并打开本应用设置。

遇到问题后，先复现，再进入 **偏好设置 → 诊断与反馈**：选择 **保存诊断日志** 下载文本附件，或选择 **邮件反馈（附日志）** 打开邮件草稿。日志包含设备与运行状态、当前进程的近期事件；已过滤地图 Key 等敏感字段，可先保存查看再发送。应用重启前的内存日志不会保留。

联系邮箱：**kprxr.660@qq.com**。关于区域也提供邮件联系与复制邮箱。请在邮件中补充发生时间、现象和操作步骤；应用不会自动发送邮件。

---

## 🏗️ 工程架构

```text
MockGeo/
├── app/                  # Jetpack Compose 主界面、地图控制器 (OSM + AMap)、前台服务与悬浮摇杆
├── core/
│   ├── model/            # 核心领域模型 (LatLng, Route, SimConfig, EngineState, CheckResult)
│   ├── route/            # 路线插值采样器、正弦加减速曲线、GPX 解析与导出器
│   ├── data/             # Room 数据库 (收藏/历史/路线) 与 DataStore 偏好设置仓库
│   └── engine/           # 引擎路由器 (ProviderEngine / XposedEngine / RootEngine + 独立 Root 载荷)
├── rootpayload/          # 内置 Root Dex 载荷、ARM64 加载器及 LSPlant 适配器
├── module/               # 系统级钩子模块 (LocationDelivery, RecipientTable, GnssSynth, EvidenceHooks, SensorHooks)
└── probe/                # 真机多维定位与生命周期自动化验收探针
```

---

## 🛠️ 本地构建指引

### 环境要求
- **JDK**：17
- **Android SDK**：`compileSdk = 36`（Android 16），`NDK 27.0.12077973`；原有引擎用 `CMake 3.22.1`，Root 载荷需 `CMake >= 3.28` / `Ninja >= 1.11`，可设置 `ROOT_CMAKE` / `ROOT_NINJA`，详见 [Root 构建说明](rootpayload/README.md)。

### 编译命令
```bash
# 构建公开安装包，忽略本机环境与文件中的个人高德 Key
./gradlew :app:assembleDebug :module:assembleDebug -PpublicBuild=true

# 运行全模块单元测试
./gradlew :core:model:test :core:route:test :core:data:testDebugUnitTest :core:engine:testDebugUnitTest :module:testDebugUnitTest
```

> **可选**：若希望仅在个人本地构建中内置高德 Key，可省略 `-PpublicBuild=true`，再复制 [secrets.properties.example](secrets.properties.example) 为 `secrets.properties`（已被 [.gitignore](.gitignore) 排除，不会提交到 Git）：
> ```properties
> amap.apiKey=你的32位高德Key
> ```

---

## 📄 开源协议

本项目自有代码基于 [MIT License](LICENSE) 开源。Root 原生组件包含 LGPL-3.0 的 LSPlant，以及 Apache-2.0 / MIT 依赖；对应源码、固定版本及许可说明见 [rootpayload/README.md](rootpayload/README.md)。
