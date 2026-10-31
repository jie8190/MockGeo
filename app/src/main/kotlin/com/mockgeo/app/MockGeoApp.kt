package com.mockgeo.app

import android.app.Application
import com.mockgeo.app.di.appModule
import com.mockgeo.core.engine.SimulationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import com.mockgeo.core.data.prefs.SettingsRepository
import com.mockgeo.core.engine.EngineRouter
import com.mockgeo.app.sim.SimController
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.osmdroid.config.Configuration as OsmConfig

class MockGeoApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session: SimulationSession by inject()
    val ready = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        // osmdroid 全局配置：只初始化一次（docs/08 §15.7）
        OsmConfig.getInstance().apply {
            userAgentValue = "MockGeo/${BuildConfig.VERSION_NAME} ($packageName; Android personal location testing app)"
            tileDownloadThreads = 2
            tileDownloadMaxQueueSize = 40
            cacheMapTileCount = 48
            cacheMapTileOvershoot = 16
            osmdroidBasePath = getDir("osmdroid", MODE_PRIVATE)
            osmdroidTileCache = getDir("osmdroid_tiles", MODE_PRIVATE)
        }
        startKoin {
            androidContext(this@MockGeoApp)
            modules(appModule)
        }
        // 启动自愈（docs/08 §4 规则 5 + docs/06 §3.3）：
        // 进程重启 → 一律回 Idle 并确保引擎关闭。
        // 对 LSPosed 引擎：若模块侧残留 enabled=true（上次进程被杀），这里发 STOP 收敛；
        // 对 provider 引擎：test provider 随进程死亡已被系统移除，stop 幂等无害。
        appScope.launch {
            try {
                session.resetToIdle()
                val settings = org.koin.core.context.GlobalContext.get().get<SettingsRepository>()
                val router = org.koin.core.context.GlobalContext.get().get<EngineRouter>()
                val controller = org.koin.core.context.GlobalContext.get().get<SimController>()
                val initial = settings.settings.first()
                controller.updateSettings(initial)
                router.switch(initial.engineId).onFailure {
                    android.util.Log.w("MockGeoApp", "engine selection pending cleanup", it)
                }
                ready.complete(Unit)
                settings.settings.collect { controller.updateSettings(it) }
            } catch (error: Exception) {
                android.util.Log.w("MockGeoApp", "initialization failed", error)
                ready.complete(Unit) // Session retains any cleanup error for the next explicit stop.
            }
        }
    }
}
