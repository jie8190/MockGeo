package com.mockgeo.module

import com.mockgeo.core.model.ControlPlane
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * LSPosed 模块入口（libxposed API 102）。
 *
 * 生命周期：LSPosed 实例化（无参构造 + attachFramework）→ onSystemServerStarting
 * → 安装 hook；按关键入口检查并在服务发布后重试（安装幂等）。
 *
 * fail-safe：system_server 每次重启，模块静态状态归零，enabled 默认 false
 * （docs/06 §4 第 5 条：宁可少模拟一秒，不多模拟一小时）。
 */
class MockGeoModule : XposedModule() {

    private var systemServer = false
    private val bootstrap by lazy {
        SystemHookBootstrap(
            localServiceLoader = { FrameworkAccess.localLocationService()?.javaClass?.classLoader },
            install = { loader ->
                markLoaded()
                HookInstaller.install(this, loader)
                report("install finished: ${ModuleConfig.hookDetail}; localService=${HookInstaller.liveServiceFound}")
                ModuleConfig.hooksReady
            },
            schedule = { work -> Thread({ work() }, "MockGeo-SysRetry").apply { isDaemon = true; start() } },
            report = { message, error -> report(message, error) },
        )
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        attachLogging()
        systemServer = param.isSystemServer
        val environment = runCatching {
            "$frameworkName $frameworkVersion ($frameworkVersionCode), API $apiVersion"
        }.getOrDefault("framework information unavailable")
        report("onModuleLoaded: module=${StatusWriter.MODULE_VERSION}, $environment, " +
            "sdk=${android.os.Build.VERSION.SDK_INT}, uid=${android.os.Process.myUid()}, " +
            "systemServer=$systemServer")
        if (systemServer) {
            markLoaded()
            bootstrap.discoverWhenReady()
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        attachLogging()
        systemServer = true
        markLoaded()
        report("onSystemServerStarting: installing hooks")
        bootstrap.onSystemServerStarting(param.classLoader)
    }

    /**
     * 应用进程入口：身份隐藏/传感器/NMEA 等只在目标进程内生效的钩子在这里安装。
     * 作用域由用户在 LSPosed 管理器中决定；模块不自带应用白名单。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        if (SystemHookBootstrap.isSystemHostCandidate(systemServer, android.os.Process.myUid(), param.packageName)) {
            report("system package ready; checking for a local location service")
            bootstrap.discoverWhenReady()
            return
        }
        if (!ProcessHookPolicy.allowClientHooks(systemServer, android.os.Process.myUid(), param.packageName)) return
        runCatching { AppScopeHooks.install(this, param.classLoader, param.packageName) }
            .onFailure { report("app-scope install failed", it) }
    }

    private fun markLoaded() {
        if (ModuleConfig.loadedAtMs == 0L) ModuleConfig.loadedAtMs = System.currentTimeMillis()
    }

    private fun report(message: String, error: Throwable? = null) {
        if (error == null) ModuleLog.i(message) else ModuleLog.e(message, error)
    }

    private fun attachLogging() {
        // android.util.Log alone is NOT the LSPosed module log shown by its manager.
        ModuleLog.bindFramework { level, message, error ->
            if (error == null) log(level, ControlPlane.MODULE_LOG_TAG, message)
            else log(level, ControlPlane.MODULE_LOG_TAG, message, error)
        }
    }

    companion object {
        /** 诊断用：控制面 provider 名（与 App 侧 ControlPlane 一致）。 */
        const val CONTROL_PROVIDER = ControlPlane.PROVIDER
    }
}
