# MockGeo

<p align="center">
  <strong>Android 现代化高真实度位置与路线模拟工具</strong><br/>
  支持 <b>标准模式</b> · <b>LSPosed 系统级钩子</b> · <b>Root 直连原生 ART 注入</b> 三擎驱动
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026--36)-3DDC84?logo=android&logoColor=white" alt="Android 8.0+"/>
  <img src="https://img.shields.io/badge/Kotlin-2.1%20%2B%20Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/NDK-C%2B%2B17%20ART%20Trampoline-00599C?logo=cplusplus&logoColor=white" alt="NDK C++17"/>
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License MIT"/>
</p>

---

## ✨ 核心特性

### 1. 三种运行引擎，覆盖从免 Root 到系统级深度无痕
- **LSPosed 模式 (`xposed`)**：基于 `libxposed API 100+` 注入 `system_server`，在系统定位服务出口直接合成并分发位置，无需污染系统底层 Provider 缓存，**停止模拟后无需重启即可立即恢复真实物理位置**。
- **Root 直连模式 (`root`)**：仅需 Root 权限（KernelSU / APatch / Magisk），基于自研 NDK `libmockgeo_art.so`（ARM64 BTI 兼容 `ArtMethod` 蹦床 + 零 Java 栈帧 `Domain::kPlatform` 隐藏 API 解锁 + 模块化 `service-wifi.jar` 复合类加载器），零侵入复用全套系统级定位钩子。
- **标准模式 (`provider`)**：无需 Root，通过 Android 系统「开发者选项 → 模拟位置信息应用」运行。

### 2. 深度无痕与多维环境证据链同步
开启 **「深度无痕模式」** 后，自动完成全链路反检测对齐：
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

请前往 [**Releases 页面**](../../releases) 下载最新发行版安装包：

| 安装包文件 | 包名 | 说明 |
| :--- | :--- | :--- |
| **`MockGeo-v0.4.1-app.apk`** | `com.mockgeo.app` | **主控端应用**（地图选点、路线规划、悬浮摇杆、参数与模式配置） |
| **`MockGeo-v0.3.10-module.apk`** | `com.mockgeo.module` | **系统级注入模块 (`v0.3.10`)**（用于 LSPosed / 系统层无痕接管） |

### 快速上手

1. 安装 `MockGeo-v0.4.1-app.apk` 与 `MockGeo-v0.3.10-module.apk`。
2. **激活系统级模式**：
   - **使用 LSPosed**：在 LSPosed 管理器中启用 **`MockGeo 模块`**，勾选 **`系统框架 (System Framework)`** 作用域并重启手机（如需对特定第三方 App 启用客户端传感器/Wi-Fi/基站直调拦截，可同时勾选对应 App）。
   - **使用 Root 直连**：在 KernelSU / APatch / Magisk 中授予 `MockGeo` 超级用户权限。
3. 打开 **MockGeo** $\rightarrow$ 点击右上角 **偏好设置（齿轮）**：
   - 在 **「运行模式」** 中选择 `LSPosed 模式` 或 `Root 直连模式`；
   - 开启 **「深度无痕模式（推荐）」**。
4. 返回地图主页，点选目的地或规划路线，点击右下角 **「开始模拟」** 即可生效；点击 **「停止模拟」** 立即恢复手机真实物理定位。

### 配置个人高德地图 Key（可选）
公开版默认使用免配置的 **OpenStreetMap**。如需在国内使用高德 3D 地图、POI 搜索与步行/骑行路线规划：
1. 打开 `MockGeo` $\rightarrow$ **偏好设置** $\rightarrow$ **地图与图层** $\rightarrow$ 点击 **「自定义高德密钥 (可选)」**；
2. 点击弹窗内的 **「查看申请 Key 所需的包名与签名信息」**，一键复制 `应用包名 (com.mockgeo.app)` 与 `签名 SHA1`；
3. 前往 [高德开放平台控制台](https://lbs.amap.com/) 创建应用并绑定上述包名与 SHA1，获取免费的「Android 平台 SDK Key」粘贴回弹窗保存即可。

---

## 🏗️ 工程架构

```text
MockGeo/
├── app/                  # Jetpack Compose 主界面、地图控制器 (OSM + AMap)、前台服务与悬浮摇杆
├── core/
│   ├── model/            # 核心领域模型 (LatLng, Route, SimConfig, EngineState, CheckResult)
│   ├── route/            # 路线插值采样器、正弦加减速曲线、GPX 解析与导出器
│   ├── data/             # Room 数据库 (收藏/历史/路线) 与 DataStore 偏好设置仓库
│   └── engine/           # 三擎路由器 (ProviderEngine / XposedEngine / RootEngine + NDK libmockgeo_art.so)
├── module/               # 系统级钩子模块 (LocationDelivery, RecipientTable, GnssSynth, EvidenceHooks, SensorHooks)
└── probe/                # 真机多维定位与生命周期自动化验收探针
```

---

## 🛠️ 本地构建指引

### 环境要求
- **JDK**：17
- **Android SDK**：`compileSdk = 36`（Android 16），`NDK 27.0.12077973`，`CMake 3.22.1`

### 编译命令
```bash
# 编译主应用与系统模块安装包
./gradlew :app:assembleDebug :module:assembleDebug

# 运行全模块单元测试
./gradlew :core:model:test :core:route:test :core:data:testDebugUnitTest :core:engine:testDebugUnitTest :module:testDebugUnitTest
```

> **可选**：若希望在本地编译时直接内置高德地图 Key，可复制 [secrets.properties.example](secrets.properties.example) 为 `secrets.properties`（已被 [.gitignore](.gitignore) 排除，不会提交到 Git）：
> ```properties
> amap.apiKey=你的32位高德Key
> ```

---

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。
