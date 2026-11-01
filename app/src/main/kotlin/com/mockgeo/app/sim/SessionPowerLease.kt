package com.mockgeo.app.sim

/** A bounded CPU lease: no renewal after stop/error; process death releases the OS lock. */
internal class SessionPowerLease(
    private val acquire: (Long) -> Unit,
    private val release: () -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var held = false
    private var renewedAt = 0L

    fun refresh(active: Boolean) {
        if (active) {
            val now = nowMs()
            if (held && now - renewedAt in 0 until RENEW_MS) return
            acquire(TIMEOUT_MS)
            held = true
            renewedAt = now
        } else close()
    }

    fun close() {
        if (held) {
            held = false
            release()
        }
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
        const val RENEW_MS = 20_000L
    }
}
