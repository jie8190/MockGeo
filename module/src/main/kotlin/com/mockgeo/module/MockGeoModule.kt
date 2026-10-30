package com.mockgeo.module

import com.mockgeo.core.model.ControlPlane
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * LSPosed 模块入口（libxposed API 102）。
 *
 * 生命周期：LSPosed 实例化（无参构造 + attachFramework）→ onSystemServerStarting
 * → 安装 hook（失败 5s 重试，最多 3 次——HLocation 的 SysRetry 模式）。
 *
 * fail-safe：system_server 每次重启，模块静态状态归零，enabled 默认 false
 * （docs/06 §4 第 5 条：宁可少模拟一秒，不多模拟一小时）。
 */
class MockGeoModule : XposedModule() {

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        ModuleConfig.loadedAtMs = System.currentTimeMillis()
        ModuleLog.d("=== onSystemServerStarting: installing hooks ===")
        installWithRetry(param.classLoader, firstAttempt = true)
    }

    /**
     * 应用进程入口：身份隐藏/传感器/NMEA 等只在目标进程内生效的钩子在这里安装。
     * 作用域由用户在 LSPosed 管理器中决定；模块不自带应用白名单。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        runCatching { AppScopeHooks.install(this, param.classLoader, param.packageName) }
            .onFailure { ModuleLog.e("app-scope install failed", it) }
    }

    private fun installWithRetry(classLoader: ClassLoader, firstAttempt: Boolean) {
        val count = runCatching { HookInstaller.install(this, classLoader) }
            .getOrElse {
                ModuleLog.e("install crashed", it)
                0
            }
        if (count > 0) return
        if (!firstAttempt) return // 重试也失败：放弃（ROM 不兼容，日志已留证据）
        // 首装失败（类未就绪等）：5s 后重试一次
        Thread({
            try {
                Thread.sleep(5_000L)
            } catch (ie: InterruptedException) {
                return@Thread
            }
            ModuleLog.d("retry: installing hooks after 5s")
            runCatching { installWithRetry(classLoader, firstAttempt = false) }
        }, "MockGeo-SysRetry").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        /** 诊断用：控制面 provider 名（与 App 侧 ControlPlane 一致）。 */
        const val CONTROL_PROVIDER = ControlPlane.PROVIDER
    }
}
