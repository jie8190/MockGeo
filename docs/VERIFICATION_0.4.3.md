# 0.4.3 版本验证说明

主应用 0.4.3，Root 载荷 0.1.0；可选 LSPosed 模块 0.3.12。

## 自动检查

166 项 JVM/debug 测试通过（model 23、route 47、data 7、engine 31、module 52、app 6），构建及主应用、模块、Root 载荷 Lint 通过。

公开 APK 使用 `-PpublicBuild=true` 构建，移除个人高德 Key。发布时重新执行主应用测试与 Lint，并检查公开包内三个 Root 组件的 SHA-256 与已验收 APK 一致。这样公开构建不依赖开发者的私有地图配置。

## 真机结果

环境：Xiaomi 15、Android 16、ARM64、KernelSU；LSPosed 注入已停用并重启，进程内无其驻留映射。

| 范围 | 结果 |
|---|---|
| 隔离进程注入 | 两轮通过：真实 Hook、混合参数、400 次并发调用、异常传播、去优化及原方法恢复 |
| 系统进程加载 | 独立 Root 回执确认系统进程身份、载荷版本、随机标识及 24 个已安装钩子 |
| Root 基础检查 | 8 项通过 |
| GPS / network / fused | 最后位置、单次定位、持续回调通过；每个 provider 的连续更新均验证 3 帧 |
| WebView | geolocation 取得预期位置 |
| 生命周期 | 48 条事件；PendingIntent 首帧与持续投递、单次额度、取消、注销通过 |
| flush | 710 / 711 / 712 三个整数 request code 各收到一次 |
| GNSS / NMEA | 9 份匹配卫星状态、9 份 GGA、9 份 RMC；启动/first-fix 各一次，注销后无残留 |
| 动态策略 | 401 条事件；正常→豁免→恢复→停止→重新开始→注销通过 |
| 收尾 | 模拟关闭、测试豁免还原、备份清理；系统进程未重启，SELinux 保持 Enforcing |

这些结果来自自有主应用与独立测试应用，不包含用户私有 App、网站或小程序。原始设备日志含运行环境信息，不随公开仓库发布。

## 复现入口

先构建并安装主应用、测试包和自有探针，给主应用与探针必要的定位权限；授予主应用 Root 权限。在 Root 模式下，设备须为 ARM64，且系统进程内没有 LSPosed 驻留。测试启动后会改变模拟位置，结束时尝试恢复测试豁免并停止模拟。

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebug -PpublicBuild=true
# 完整安装以上 APK 后，在主应用选择 Root 直连。
./scripts/verify-compatibility.ps1 -Engine root -Serial <设备序列号> -Extended
```

只验证加载器、避免对真实系统进程安装定位钩子：

```powershell
./gradlew.bat :rootpayload:assembleDebug
python scripts/verify-root-probe.py --adb <adb路径> --serial <设备序列号>
```

设备测试结果保存在仓库的 `build/reports/device/` 或 `build/reports/root-probe/` 下。

## 实际限制

- 当前 Root 实现不支持与驻留的 LSPosed ART 框架同时运行；停用注入模块后须手动重启，其他 LSPosed 模块会暂时失效。
- Root 不包含应用进程内传感器和独立 SDK 缓存 Hook，故 Root 验收不传 `-RequireSensors`。
- 其他 ROM、其他 Root 实现以及第三方业务的采信结果未获得相同验证；系统位置模拟不改变 IP 地区。
- LSPosed 模块 0.3.12 的编译、单测和 Lint 已通过；上述系统定位结论针对内置 Root 载荷，不冒充该模块版本的单独真机验收结论。
