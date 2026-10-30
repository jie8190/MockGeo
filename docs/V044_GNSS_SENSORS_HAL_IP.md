# 0.3.6：GNSS 回调合成（系统侧）、传感器一致性、native HAL 评估、IP 风控诊断

> **后续核验勘误**：原 selftest 采用了错误的构造签名，不能据其 NoSuchMethodException 推断公开 GNSS Builder 受 hidden API 拦截；该 Builder 在 API 30+ 是公开 12 参数接口。原系统侧注册接线和回调名也有问题，见 `V045_214_GAP_MATRIX.md` §8 的修复。下文 IP 工具运行在电脑，结果仅代表该脚本的网络出口，不能直接断言手机或目标 App 使用同一出口。

> 承接 0.3.5。本轮原计划四项：GNSS 回调合成、native HAL、加速度/陀螺一致性、IP/服务端风控。
> 结果是：GNSS 与传感器已实现；native HAL 给出"为什么不在本模块做"的结论与替代路径；
> IP 侧交付诊断工具并跑出真实结论。**0.3.6 已装机并重启，但重启后 USB 掉线，系统侧推送未验证。**

## 1. GNSS 回调合成：从应用进程改到 system_server

### 1.1 真机发现（决定性）

先用独立 probe 在 API 36 真机上试了三种 `GnssStatus` 构造策略：

```
gnss-selftest builder FAIL java.lang.NoSuchMethodException: addSatellite
gnss-selftest ctor8   FAIL java.lang.NoSuchMethodException: <init> [int, int[], float[], ...]
gnss-selftest ctor7   FAIL java.lang.NoSuchMethodException: <init> [int, int[], float[], ...]
```

原因：应用进程受 **hidden API 限制**，`GnssStatus$Builder` 与私有构造器反射不可达。
因此 0.3.5 里"应用作用域 hook getter"只能改已有对象的读数，**无法凭空产生回调**。

### 1.2 新架构

- 在 **system_server**（不受 hidden API 限制）hook `LocationManagerService` 的
  `registerGnssStatusCallback` / `unregisterGnssStatusCallback` 与 NMEA 的
  `addNmeaListener` / `removeNmeaListener`，捕获 `IGnssStatusListener` / `INmeaListener` 代理；
- `GnssPusher` 按推送节奏调用 `onSatelliteStatusChanged(GnssStatus)`，每个监听器补一次
  `onStarted` / `onFirstFix`；NMEA 同步回灌 `$GPGGA` / `$GPRMC`；
- 失败 5 次自动剔除；注销时移除；STOP 后不再推送（`enabled` 门控）。
- 好处：**覆盖所有 App**，且只依赖已有的 `system` 作用域（不需要把每个目标 App 加进 LSPosed 作用域）。

## 2. 加速度 / 陀螺一致性（应用作用域）

`SensorSynth` 在 `SensorEventListener` 代理里改事件值（转发后恢复原数组）：
- 加速度：重力 9.81 + 行走竖向振荡（1.6 Hz，移动时）；
- 线性加速度：横向/前向小振荡；
- 陀螺：静止 ≈ 0，移动时叠加 0.08 rad/s 的偏航摆动；
- 旋转向量：按配置航向构造四元数；
- 磁力：按航向旋转的水平分量 + 固定垂直分量。

前提：目标 App 在 LSPosed 作用域内（应用进程钩子）。

## 3. native HAL：结论（本轮不做，理由明确）

本模块是 Java/Kotlin 的 LSPosed 模块，**没有 native 注入能力**。真正的 HAL 层伪造需要：
1. 一份 native `.so`（NDK 构建）注入 `android.hardware.gnss` HAL 进程或 `system_server`；
2. 与 2.1.4 的 `libnl.so` / 1.5.2 的 `libfl_*` 同层：ptrace + ART hook + 对 `IGnss` HAL 的
   `getExtensionGnss`/`start` 拦截；
3. 真机按 ROM 校准（不同厂商 GNSS HAL 差异很大）。

当前机器**没有 NDK**，且 0.3.6 重启后设备掉线，无法形成"改-装-验"的闭环。因此本轮把
**App 可见面**（Location/GnssStatus/NMEA/LocationAvailability）全部放在 system_server 覆盖，
HAL 层留作独立子系统，需要 NDK + 真机回归窗口后再做。替代收益：对绝大多数消费级 App，
它们读的是 Java 层 API，而不是 HAL。

## 4. IP / 服务端风控

- 定位模拟**无法改变出口 IP**：IP 归属地由网络出口决定，服务端用 IP 交叉校验时，
  坐标再怎么改都会被判"不一致"。
- 已交付诊断工具：`_tools/ip_region_check.py`，对比"出口 IP 归属地"与"模拟坐标"的直线距离，
  输出 一致 / 同省 / 明显不一致。实测本机：出口 IP 在日本东京，与北京模拟点相距 **2101 km**，
  判定"明显不一致"——这类目标必须配合 VPN/代理或更换出口网络，否则定位层无能为力。
- 应用侧如需处理，应在产品上提供"出口网络与模拟地区一致性提示"，而不是假装能改 IP。

## 5. 版本与测试

- 0.3.6（versionCode 9），`META-INF/xposed/module.prop` 同步。
- 单测 **36 项全绿**：CompatibilityRegressionTest 9 + EvidenceTemplatesTest 11 + ModuleConfigTest 10 + ProviderResolverTest 6。
- 产物：`rebuild/releases/0.4.1/MockGeo-Module-0.3.6.apk`
  SHA-256 `302715c6fa17d178090908fee9a252aa152162d2cac5c0b2f90e13b7ad324d7a`

## 6. 验证状态

- ✅ 构建通过；36 项单测通过；`GnssStatus` 构造在应用进程不可用这一结论有真机 selftest 证据。
- ✅ 0.3.6 已安装并重启。
- ❌ **重启后 USB 掉线**（`adb devices` 为空），系统侧 GNSS/NMEA 推送、传感器合成均未做运行期验证。
- ⚠️ 传感器/NMEA（应用作用域版）需要把目标 App 加入 LSPosed 作用域；GNSS 推送（系统侧）不需要。

## 7. 重连后的验证清单

```bash
# 1) 模块加载与系统钩子数
adb shell su -c "logcat -d -s MockGeoModule:* | grep -E 'install finished|evidence hooks installed|gnss synthesis strategy'"
# 2) 捕获到 GNSS 监听器（启动 probe 的 GNSS 检查页后）
adb shell am start -n com.mockgeo.probe/.GnssSensorProbeActivity
adb shell su -c "logcat -d -s MockGeoModule:* | grep -E 'gnss listener registered|nmea listener registered'"
# 3) 开启模拟后应看到合成回调
adb shell su -c "logcat -d -s MockGeoProbe:* | grep -E 'gnss status count|gnss started|gnss firstFix'"
# 4) 传感器（probe 需在作用域）
adb shell su -c "logcat -d -s MockGeoProbe:* | grep sensor"
```
