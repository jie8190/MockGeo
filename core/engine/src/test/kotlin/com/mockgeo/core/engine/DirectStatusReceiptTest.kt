package com.mockgeo.core.engine

import com.google.common.truth.Truth.assertThat
import com.mockgeo.core.model.ControlPlane as C
import org.junit.jupiter.api.Test

class DirectStatusReceiptTest {
    private fun fields(): Map<String, Any?> = mapOf(
        C.COL_ENABLED to false, C.COL_REVISION to 5L, C.COL_LAT to 10.0, C.COL_LNG to 20.0,
        C.COL_HOOKS to true, C.COL_UPTIME_MS to 100L, C.COL_UPDATED_AT to 1_000L,
        C.COL_MODULE_VERSION to "0.3.15", C.COL_BACKEND to "xposed", C.COL_PROCESS_ID to 123,
        C.COL_INSTANCE_NONCE to "instance", C.COL_RESPONSE_NONCE to "request", C.COL_HOOK_DETAIL to "ready",
        C.COL_REPORT_MOCK to true, C.COL_LAST_CALLS to 0L, C.COL_CURRENT_CALLS to 0L,
        C.COL_LISTENER_CALLS to 0L, C.COL_PUSHES to 0L, C.COL_LEASE_REMAINING_MS to 0L,
        C.COL_LEASE_EXPIRATIONS to 0L, C.COL_REWRITTEN_CALLBACKS to 0L, C.COL_UNMATCHED_CALLBACKS to 0L,
    )

    @Test fun `complete reply reports real disabled state and loaded version`() {
        val receipt = DirectStatusReceipt.decode(fields(), "request", "xposed", 1_000)!!
        assertThat(receipt.enabled).isFalse()
        assertThat(receipt.moduleVersion).isEqualTo("0.3.15")
        assertThat(receipt.revision).isEqualTo(5)
    }

    @Test fun `incomplete stale wrong backend and replayed replies are rejected`() {
        for (key in fields().keys) {
            assertThat(DirectStatusReceipt.decode(fields() - key, "request", "xposed", 1_000)).isNull()
        }
        assertThat(DirectStatusReceipt.decode(fields(), "other", "xposed", 1_000)).isNull()
        assertThat(DirectStatusReceipt.decode(fields(), "request", "root", 1_000)).isNull()
        assertThat(DirectStatusReceipt.decode(fields(), "request", "xposed", 999)).isNull()
        assertThat(DirectStatusReceipt.decode(fields(), "request", "xposed", 20_000)).isNull()
        assertThat(DirectStatusReceipt.decode(fields() + (C.COL_HOOKS to "true"), "request", "xposed", 1_000)).isNull()
    }
}
