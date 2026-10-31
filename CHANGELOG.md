# 更新日志

## 0.4.3 — 2026-10-31

主应用 **0.4.3** · 内置 Root 载荷 **0.1.0** · 可选 LSPosed 模块 **0.3.12**。GitHub Release 从本版起按主应用版本标记为 `v0.4.3`。

### 新增与修复

- 实现独立 Root 系统定位：内置 ARM64 加载器和系统端载荷，无需单独安装 MockGeo 模块 APK。
- 通过独立控制通道核对系统进程、载荷版本和随机标识，避免借用 LSPosed 回执误报 Root 成功。
- 为首次 Root 初始化提供有上限的等待时间；丢失启动回执时保留停止清理责任，未确认停止不返回成功。
- 修复 Hook 安装失败仍计成功、回调元数据发布时序及重复安装问题。
- 修复旧注册回滚可能误删新订阅的并发问题。
- 高德默认未授权，统一授权确认入口；保存 Key 后继续切图，地图重建保留选点，修复无内置 Key 时的提示。
- 增加不读取个人内置 Key 的公开构建开关 `-PpublicBuild=true`、Root 第三方源码与许可、仓库内的设备验收脚本。

### 验收与限制

- 166 项 JVM/debug 测试与构建、Lint 通过；在 Xiaomi 15 / Android 16 / KernelSU、LSPosed 无驻留的环境完成纯 Root 系统定位验收。
- 覆盖 GPS/network/fused、WebView、PendingIntent、取消/注销/flush、GNSS/NMEA、动态豁免与停止恢复。详见 [验证说明](docs/VERIFICATION_0.4.3.md)。
- Root 目前仅支持 ARM64，不能与驻留的 LSPosed 同时运行；应用进程内传感器/SDK 缓存 Hook、其他 ROM 与第三方业务判定不在本轮通过范围内。
- LSPosed 模块 0.3.12 已通过构建、单测和 Lint，本轮未完成该模块版本的单独真机验收。Root 用户无需安装它。
- 公开 APK 不包含个人高德 Key，可使用 OpenStreetMap，或在设置中填入自己的高德 Android SDK Key。

## 0.4.2 — 内部验证版本，未单独发布

Root 能力误报修正、订阅回滚及地图授权修复经内部验证后，随 0.4.3 发布。

## 0.4.1 / Module 0.3.10 — 2026-10-31

历史 Release 标签：[`v0.3.10`](https://github.com/jie8190/MockGeo/releases/tag/v0.3.10)。

- 发布地图选点、路线、收藏/历史、悬浮摇杆和个人高德 Key 配置等功能。
- 模块补充处理 ROM 重建监听器后的回调路径。
- **更正：该版 Root 代码仅包含进程内实验，尚未完成独立系统注入。独立 Root 系统定位在 0.4.3 实现并验收。**
