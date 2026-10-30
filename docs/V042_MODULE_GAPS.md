# 模块 0.3.2：PendingIntent 订阅、一次性请求生命周期、开关/provider 查询一致性

> 接手人：Lead（本次由我改模块代码）。范围严格限定为用户指定的三块；客户端融合定位与原生 HAL 不在本轮。
> 结论先行：**构建通过、16 项单测通过；设备离线，未做运行期验证**。本轮是"扩大覆盖"，不是"保证目标 App 接受位置"。

## 1. 本轮改了什么

| # | 缺口 | 实现 | 关键文件 |
|---|---|---|---|
| 1 | `requestLocationUpdates(..., PendingIntent)` 没有合成节奏：真实 provider 不产帧时订阅方永远收不到 | 新增 `PendingIntentPusher`：注册 `PendingIntent → provider`，由现有推送线程按 `reportIntervalMs` 主动 `send(ctx, 0, Intent().putExtra(KEY_LOCATION_CHANGED, fake))`；注销时移除；连续 3 次失败（`CanceledException` 等）自动剔除；STOP 时清空 | `module/.../PendingIntentPusher.kt`（新）、`ListenerPusher.kt`、`HookInstaller.kt` |
| 2 | 一次性定位请求的取消与生命周期不完整：调用方已取消仍可能收到合成结果；投递后真实结果晚到会覆盖假位置 | 新增 `CancellationWatcher`：包一层 `ICancellationSignal` Proxy，记录 `cancel()`；投递前检查、投递后主动 `cancel()` 底层请求；`getCurrentLocation + PendingIntent` 走 `deliverOnce` 只发一帧 | `module/.../CancellationWatcher.kt`（新）、`CurrentLocationBridge.kt`、`HookInstaller.kt` |
| 3 | `requestSingleUpdate` 被当成持续订阅，每 tick 都投 | `ListenerPusher` 增加 `oneShot` 标记：投递成功即从注册表移除 | `ListenerPusher.kt`、`HookInstaller.kt` |
| 4 | 定位总开关：模拟中 `isLocationEnabled*` 仍可能返回 false，App 先判开关就放弃 | 新增 `SWITCH_ON` 行为：`isLocationEnabled` / `isLocationEnabledForUser` 在 enabled 时恒 true | `HookInstaller.kt` |
| 5 | provider 查询不一致：`getProviders`/`getAllProviders` 缺 gps/network、`getBestProvider` 返回 null | 新增 `POST_PROVIDERS`：列表补齐 `gps`+`network`（去重、保持原顺序）；`getBestProvider` 为 null 时返回 `gps` | `HookInstaller.kt` |
| 6 | 30+ 的 provider 在 `LocationRequest`/`ProviderRequest` 里，旧代码一律回落 `gps`（旧基线观测到 network/fused 被标成 gps） | 新增 `ProviderResolver`：从实参的 `LocationRequest.getProvider()`、`ProviderRequest.getProvider()`、方法名依次解析；默认 gps | `module/.../ProviderResolver.kt`（新） |

版本：模块 `0.3.2`（versionCode 5），`META-INF/xposed/module.prop` 同步；`StatusWriter.MODULE_VERSION` 同步，便于诊断区分"已安装/已加载"。

## 1.1 顺带修掉的一个连带缺陷

`ListenerPusher.pushOnce()` 原本在 `registry.isEmpty()`（没有 ILocationListener）时直接 return，
导致**只有 PendingIntent 订阅**的场景永远不会进入推送循环。已改为：先推 PendingIntent 订阅，再处理监听器。
这一条是改 PendingIntent 时暴露出来的，属于原有代码路径的边界问题。

## 2. 与参考实现的对照

| 行为 | 2.1.4 的做法 | 本实现 |
|---|---|---|
| PendingIntent 订阅 | LMS 原生路径本身会 send；注入侧靠替换 `LocationResult` 让真实帧变假 | 不依赖真实帧：主动合成，覆盖"室内无帧" |
| 一次性取消 | `ILocationCallback` 桥 + 取消信号（`es/rd1` 一带） | `CancellationWatcher` 观测 + 主动 cancel |
| 单次订阅 | `requestSingleUpdate` 只回一帧 | `oneShot` 注册表项，投递后移除 |
| 开关/provider 观感 | 2.1.4 有 `isProviderEnabled` 替换 | 增加 `isLocationEnabled*`、provider 列表、最佳 provider |

## 3. 验证状态（重要）

- ✅ `:module:assembleDebug` 通过；`:module:testDebugUnitTest` 16 项全绿（`ModuleConfigTest` 10 + `ProviderResolverTest` 6）。
- ❌ **未运行验证**：`adb devices` 为空，设备离线。本轮的 PendingIntent 投递、取消时序、开关一致性都**没有真机证据**。
- ⚠️ 已知取舍：`getCurrentLocation + PendingIntent` 没有可用的取消信号，合成帧发出后框架仍可能再投一帧真实结果（可能重复）。是否需要在真机上做去重，取决于该路径的实际表现，本轮未处理。
- ❌ 未改默认的模拟标记策略（`compatibilityMode` 默认仍 false），也未做 `isMock` 洗白、GNSS/NMEA、`LocationAvailability`、客户端 hook、native HAL——这些是不同层，按用户安排另行确定。

## 4. 产物

| 文件 | SHA-256 |
|---|---|
| `rebuild/releases/0.4.1/MockGeo-Module-0.3.2.apk` | `317fc3e78490c23d4e1bfbe1aaabb2407e7ad8880a5b2e0e3e2022f86894fdc2` |

安装后**必须重启**（system_server 只在启动时加载模块），否则诊断页会显示"已安装 0.3.2 / 系统加载 0.3.x"。

## 5. 设备重连后的验收步骤

```bash
# 1) 确认系统加载的是 0.3.2（而不是旧版）
adb shell su -c "logcat -d -s MockGeoModule:* | grep -E 'onSystemServerStarting|install finished|LMS hooks|report hooks'"
# 2) PendingIntent 订阅：注册/推送/注销
adb shell su -c "logcat -d -s MockGeoModule:* | grep -E 'pending-intent (registered|unregistered|dropped|send failed)'"
# 3) 一次性取消：调用方 cancel 后不应有合成投递
adb shell su -c "logcat -d -s MockGeoModule:* | grep -E 'one-shot (skipped|delivered)'"
# 4) 开关/provider 查询
adb shell dumpsys location | grep -E "Location Providers|enabled"
```

配合 `:probe`（独立 UID）做同一入口的 A/B：兼容格式关 / 开各一次，记录"原位置/定位失败/位置变了但被拒/地区未变"四分类。

## 6. 边界

本轮只扩大覆盖：即使 PendingIntent、一次性请求、开关查询都正确，目标 App 仍可能因为自身 SDK 的融合/缓存/服务端判定而不接受位置。要判断是否"目标侧问题"，需要第 5 步的四分类结果，而不是继续增加 hook 数量。
