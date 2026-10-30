package com.mockgeo.module

import java.util.concurrent.atomic.AtomicLong

/** Aggregate only: never records calling packages, URLs, or application identities. */
internal object HookDiagnostics {
    val lastCalls = AtomicLong()
    val currentCalls = AtomicLong()
    val listenerCalls = AtomicLong()
    val pushes = AtomicLong()

    /** PendingIntent 订阅的合成投递次数（与 pushes 分开统计，便于诊断）。 */
    val pendingPushes = AtomicLong()

    fun reset() {
        lastCalls.set(0); currentCalls.set(0); listenerCalls.set(0); pushes.set(0); pendingPushes.set(0)
    }
}
