package com.mockgeo.core.engine

import com.mockgeo.core.model.InjectionEngine

interface SystemHookControl : InjectionEngine {
    suspend fun pingModule(): Boolean
    fun statusSnapshot(): XposedEngine.ModuleStatus?
}

data class RootReceipt(val pid: Int, val nonce: String, val version: String) {
    fun matches(status: XposedEngine.ModuleStatus?): Boolean = pid > 1 && version.isNotBlank() && status != null &&
        status.backend == "root" && status.hooksInstalled && status.processId == pid &&
        status.instanceNonce == nonce && nonce.matches(Regex("[0-9a-f]{32}")) &&
        status.moduleVersion == version
}

interface RootEnvironment {
    val version: String
    suspend fun readiness(): List<com.mockgeo.core.model.CheckResult>
    suspend fun load(): RootReceipt
}
