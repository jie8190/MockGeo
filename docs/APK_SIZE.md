# APK 体积优化

0.4.6 主包为 106,350,494 字节。其主要构成为约 59.6 MB 原生库、22.6 MB 压缩后的 DEX、12.6 MB Root 资产和 8.4 MB 地图等资源。旧包使用 Debug 构建，包含整套扩展图标及调试工具。

## 0.4.7 候选构建

主应用启用 R8 与资源裁剪，并关闭 debuggable。高德、osmdroid 和 JNI 入口有明确保留规则，系统钩子所在的 Root 载荷单独构建且不混淆。

首次 R8 检查发现高德 SDK 引用了未打包的 `net.jafama.FastMath`，已补齐其 2.3.2 依赖。仅针对未随公开 SDK 提供、且本应用不启用的 `GnssSoftLocator` RTK 扩展添加一条缺类警告豁免；没有使用全局 `-dontwarn`。

原生库使用标准 Android 安装时解压方式，降低 APK 下载体积。系统仍需存放解压后的当前架构原生库，因此 APK 的缩小比例不等于应用信息页中总安装空间的缩小比例。

Root 原生库使用 NDK `llvm-strip --strip-debug` 生成分发副本，完整文件留在本机构建目录。每次构建比较剥离前后的所有 `SHF_ALLOC` 节、动态符号和程序头；不允许改变执行代码、数据、重定位或展开表。当前 `.so` 从 10,571,192 字节降到 944,784 字节。DEX 载荷里不再重复放入这份原生库；加载器仍先载入独立 `.so`，再通过 JNI 启动 DEX 入口。

## 命令

默认通用 ARM 包（32 位与 64 位）：

```powershell
./gradlew.bat :app:assembleRelease -PpublicBuild=true
```

仅 ARM64 的较小包：

```powershell
./gradlew.bat :app:assembleRelease -PpublicBuild=true -PappAbis=arm64-v8a
```

输出均为 `app/build/outputs/apk/release/app-release.apk`，切换架构前应归档上一份。原签名密钥仍由本地签名配置提供，以保持已有安装的升级能力；密钥不提交到仓库。不要把 `assembleDebug` 的 APK 用作经过优化的发行包。

校验下载包、签名、架构、Root 资产与相对旧包的原生运行内容：

```powershell
python scripts/verify-release-apk.py app/build/outputs/apk/release/app-release.apk --baseline releases/github-v0.4.6/MockGeo-v0.4.6-app.apk --report build/reports/apk-size/universal.json
# ARM64 包额外传入 --abis arm64-v8a
```

该脚本需要 `JAVA_HOME` 与 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`），读取本机已知地图 Key 仅用于确认它们没有进入 APK，不输出 Key。不安装、不请求 Root，也不发布文件。

## 验证范围

最终候选归档在 `releases/optimized-0.4.7/`，主应用 0.4.7/code11，Root 0.1.3/code4；LSPosed 模块仍使用已发布的 0.3.14。

| 安装包 | 字节数 | 十进制 MB | 相比 0.4.6 减少 |
|---|---:|---:|---:|
| 通用 ARM 32/64 位 | 39,141,549 | 39.14 | 63.20% |
| ARM64 | 27,800,988 | 27.80 | 73.86% |

通用包中压缩后的 DEX 为 3.07 MB（旧版 22.64 MB），Root 资产为 1.61 MB（旧版 12.64 MB）。两种包的 DEX、Root 资产及 R8 映射完全一致；区别仅在随包的手机架构库。

- 通用包 SHA256：`7336c315718e66537976bee6236eeaa873ec6101c71bef3ccb0129a9183c03b6`。
- ARM64 包 SHA256：`ca6bd398d65e3415c6b2996e6fc0a507d555e8d580bcda6ccc33ab044d05f913`。
- 两包签名与 0.4.6 一致，均 `debuggable=false`，Zipalign 校验通过；实际 APK 的架构、压缩标记、模拟位置权限、Root 资产哈希/版本/JNI 类入口与私人 Key 排除检查通过。
- 高德、定位 SDK 与其他第三方原生库解压后和 0.4.6 逐字节相同；真正的 Root SO/loader 全部加载节和程序头同旧包。实验性 `mockgeo_art` 辅助库随主应用采用 Release 编译，不用于当前独立 Root 注入。
- 201 项 JVM 测试通过：model25、route47、data9、engine44、module64、app12。其中 app/data/engine 使用 Release 单测变体。app/rootpayload 的 Release Lint 通过。
- R8 映射及完整 Root 原生调试文件独立保存在候选目录的 `developer/` 下，不进入 APK，也不作为面向用户的 Release 附件。

记录：工作区 `_tools/size-047-universal.json`、`size-047-arm64.json`、`size-047-final.log`、`size-047-arm64-build.log`。后续已完成通用包的真机验收，已通过项目与未完成项见 [0.4.7 核验](VERIFICATION_0.4.7.md)。本次 Release 只发布通用主包、原有模块和校验文件，ARM64 专用包保留为本地构建对比。

已加载旧 Root 载荷时，使用 0.1.3 前需用户手动重启；只用 LSPosed 模式且系统已加载 0.3.14 时，不需要因本次主包体积优化更新模块。旧版标签保留，0.4.7 作为新的 Latest 下载入口。
