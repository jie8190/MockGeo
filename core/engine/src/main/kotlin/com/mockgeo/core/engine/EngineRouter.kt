package com.mockgeo.core.engine

import com.mockgeo.core.model.CheckResult
import com.mockgeo.core.model.EngineCapability
import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.InjectionEngine
import com.mockgeo.core.model.SimConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 引擎路由器：UI 可切换引擎（provider / xposed），会话与 UI 始终面对同一个对象。
 *
 * 规则：
 * - 仅在 Idle 态允许切换（运行中切换必须先停止——避免两个引擎同时注入）；
 * - 自持 StateFlow 镜像当前引擎状态（切换不破坏 UI 收集器）；
 * - capability 在构造期缓存（引擎的 capability 是纯数据，无 IO）。
 */
class EngineRouter(
    engines: List<InjectionEngine>,
    private val scope: CoroutineScope,
    defaultEngineId: String,
) : InjectionEngine {

    private val caps: Map<String, EngineCapability>
    private val byId: Map<String, InjectionEngine>

    init {
        // capability() 是纯数据返回（两个引擎实现均无 IO），构造期一次性读取
        val c = mutableMapOf<String, EngineCapability>()
        val m = mutableMapOf<String, InjectionEngine>()
        runBlocking {
            for (e in engines) {
                val cap = e.capability()
                c[cap.engineId] = cap
                m[cap.engineId] = e
            }
        }
        caps = c
        byId = m
    }

    @Volatile
    private var currentId: String = byId[defaultEngineId]?.let { idOf(it) } ?: idOf(engines.first())

    @Volatile
    private var current: InjectionEngine = byId[currentId] ?: engines.first()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    private var mirrorJob: Job? = null

    init {
        mirrorState()
    }

    val currentEngineId: String get() = currentId

    val availableEngineIds: Set<String> get() = caps.keys

    fun capabilityOf(engineId: String): EngineCapability? = caps[engineId]

    /** 切换引擎。仅 Idle 允许；否则返回失败。 */
    fun switch(engineId: String): Result<Unit> = runCatching {
        val next = byId[engineId] ?: throw IllegalArgumentException("unknown engine: $engineId")
        if (_state.value != EngineState.Idle || current.state().value != EngineState.Idle) {
            throw IllegalStateException("cannot switch engine while not idle (stop first)")
        }
        current = next
        currentId = engineId
        mirrorState()
    }

    override suspend fun capability(): EngineCapability = caps.getValue(currentId)

    override suspend fun start(cfg: SimConfig): Result<Unit> = current.start(cfg)

    override suspend fun update(cfg: SimConfig): Result<Unit> = current.update(cfg)

    override suspend fun stop(): Result<Unit> {
        var failure: Throwable? = null
        // A previous process may have selected a different engine. Close every
        // reachable backend before claiming that simulation has stopped.
        for (engine in byId.values) {
            if (engine !== current && engine is XposedEngine && !engine.pingModule()) continue
            if (engine !== current && engine is RootEngine && !engine.pingModule()) continue
            // The default provider can be selected after process recreation while
            // the module is absent; only reachable modules need a cleanup command.
            if (engine === current && engine is XposedEngine && !engine.pingModule()) {
                failure = IllegalStateException("LSPosed module unreachable; stop not confirmed")
                continue
            }
            if (engine === current && engine is RootEngine && !engine.pingModule()) {
                failure = IllegalStateException("Root system hooks unreachable; stop not confirmed")
                continue
            }
            val result = engine.stop()
            if (result.isFailure && failure == null) failure = result.exceptionOrNull()
        }
        if (failure == null) _state.value = EngineState.Idle
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun selfCheck(): List<CheckResult> = current.selfCheck()

    suspend fun selfCheck(engineId: String): List<CheckResult> =
        byId[engineId]?.selfCheck() ?: listOf(CheckResult("引擎", false, "引擎不存在"))

    fun moduleSnapshot(): XposedEngine.ModuleStatus? =
        (byId[currentId] as? RootEngine)?.statusSnapshot()
            ?: (byId["xposed"] as? XposedEngine)?.statusSnapshot()
            ?: (byId["root"] as? RootEngine)?.statusSnapshot()

    override fun state(): StateFlow<EngineState> = _state

    private fun mirrorState() {
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            current.state().collect { _state.value = it }
        }
    }

    private fun idOf(engine: InjectionEngine): String = runBlocking { engine.capability().engineId }
}
