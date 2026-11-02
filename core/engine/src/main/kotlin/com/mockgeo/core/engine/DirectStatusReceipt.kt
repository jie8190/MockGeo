package com.mockgeo.core.engine

import com.mockgeo.core.model.ControlPlane as C

/** A direct reply is usable only for the command that requested it. No Pong-only success. */
internal object DirectStatusReceipt {
    fun decode(fields: Map<String, Any?>, nonce: String, backend: String, now: Long): XposedEngine.ModuleStatus? = runCatching {
        fun n(key: String) = fields[key] as Number
        fun s(key: String) = fields[key] as String
        fun b(key: String) = fields[key] as Boolean
        val status = XposedEngine.ModuleStatus(
            enabled = b(C.COL_ENABLED), revision = n(C.COL_REVISION).toLong(),
            lat = n(C.COL_LAT).toDouble(), lng = n(C.COL_LNG).toDouble(),
            hooksInstalled = b(C.COL_HOOKS), uptimeMs = n(C.COL_UPTIME_MS).toLong(),
            updatedAt = n(C.COL_UPDATED_AT).toLong(), moduleVersion = s(C.COL_MODULE_VERSION),
            backend = s(C.COL_BACKEND), processId = n(C.COL_PROCESS_ID).toInt(),
            instanceNonce = s(C.COL_INSTANCE_NONCE), responseNonce = s(C.COL_RESPONSE_NONCE),
            hookDetail = s(C.COL_HOOK_DETAIL), reportMock = b(C.COL_REPORT_MOCK),
            lastCalls = n(C.COL_LAST_CALLS).toLong(), currentCalls = n(C.COL_CURRENT_CALLS).toLong(),
            listenerCalls = n(C.COL_LISTENER_CALLS).toLong(), pushes = n(C.COL_PUSHES).toLong(),
            leaseRemainingMs = n(C.COL_LEASE_REMAINING_MS).toLong(), leaseExpirations = n(C.COL_LEASE_EXPIRATIONS).toLong(),
            rewrittenCallbacks = n(C.COL_REWRITTEN_CALLBACKS).toLong(), unmatchedCallbacks = n(C.COL_UNMATCHED_CALLBACKS).toLong(),
        )
        status.takeIf { nonce.isNotBlank() && it.responseNonce == nonce && it.backend == backend &&
            it.processId > 1 && it.isFresh(now) && it.moduleVersion.isNotBlank() &&
            it.lat.isFinite() && it.lng.isFinite() && it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }
    }.getOrNull()
}
