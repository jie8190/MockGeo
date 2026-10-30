package com.mockgeo.module

import android.content.Context
import io.github.libxposed.api.XposedInterface

internal object AppScopeHooks {
    private val installed = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())

    /** 当前进程包名（onPackageLoaded 时写入），用于豁免名单判断。 */
    @Volatile
    internal var selfPackage: String? = null

    /** 当前包是否应被注入：模拟开启且不在豁免名单。 */
    @Volatile private var processPackages: List<String>? = null
    internal fun isActive(state: ScopeState): Boolean {
        if (!state.enabled) return false
        val packages = processPackages ?: runCatching {
            appContext()?.packageManager?.getPackagesForUid(android.os.Process.myUid())?.toList()
        }.getOrNull()?.also { processPackages = it } ?: listOfNotNull(selfPackage)
        return packages.isNotEmpty() && packages.none { it in state.exempt }
    }

    @Synchronized
    fun install(api: XposedInterface, loader: ClassLoader, packageName: String) {
        if (packageName == com.mockgeo.core.model.ControlPlane.APP_PACKAGE || packageName == "android" || !installed.add(loader)) return
        selfPackage = packageName
        val location = Class.forName("android.location.Location", false, loader)
        for (method in location.declaredMethods.filter { it.name in setOf("isMock", "isFromMockProvider") && it.parameterCount == 0 }) {
            runCatching { api.hook(method).intercept(XposedInterface.Hooker { chain ->
                val state = AppScopeConfig.snapshot(appContext())
                if (isActive(state) && state.hideMock) false else chain.proceed()
            }) }.onFailure { ModuleLog.w("location flag hook failed", it) }
        }
        val secure = Class.forName("android.provider.Settings\$Secure", false, loader)
        secure.declaredMethods.filter { it.name in setOf("getString", "getStringForUser", "getInt", "getIntForUser") }.forEach { method ->
            runCatching { api.hook(method).intercept(XposedInterface.Hooker { chain ->
                val key = chain.args.getOrNull(1) as? String
                val state = AppScopeConfig.snapshot(appContext())
                if (isActive(state) && state.hideMock && key in setOf("mock_location", "allow_mock_location")) {
                    if (method.returnType == Int::class.javaPrimitiveType) 0 else null
                } else chain.proceed()
            }) }.onFailure { ModuleLog.w("Secure query hook failed", it) }
        }
        // This is a Play Services SDK class in the client, not android.location.*.
        runCatching {
            val cls = Class.forName("com.google.android.gms.location.LocationAvailability", false, loader)
            api.hook(cls.getDeclaredMethod("isLocationAvailable")).intercept(XposedInterface.Hooker { chain ->
                if (isActive(AppScopeConfig.snapshot(appContext()))) true else chain.proceed()
            })
        }.onFailure { ModuleLog.d("GMS LocationAvailability unavailable in this scoped process") }
        SensorHooks.install(api)
        EvidenceHooks.installClient(api, loader)
        ModuleLog.d("app-scope hooks installed") // no target identity in logs
    }

    internal fun appContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()
}
