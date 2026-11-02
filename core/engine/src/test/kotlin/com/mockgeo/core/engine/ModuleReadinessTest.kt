package com.mockgeo.core.engine

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ModuleReadinessTest {
    @Test fun `live supported module works when package manager hides installation`() {
        val checks = ModuleReadiness.checks(null, true, "0.3.14")
        assertThat(checks.all { it.ok }).isTrue()
        assertThat(checks.joinToString { it.message.orEmpty() }).doesNotContain("重启")
    }

    @Test fun `package installation alone and stale receipts do not prove loaded module`() {
        assertThat(ModuleReadiness.checks("0.3.14", false, "0.3.14").last().ok).isFalse()
        assertThat(ModuleReadiness.checks(null, false, "0.3.14").none { it.ok }).isTrue()
    }

    @Test fun `known installed and loaded mismatch still requires restart`() {
        val check = ModuleReadiness.checks("0.3.15", true, "0.3.14").last()
        assertThat(check.ok).isFalse()
        assertThat(check.message).contains("手动重启")
    }

    @Test fun `unsupported or malformed live versions are rejected`() {
        for (version in listOf("0.3.0", "unknown", "")) {
            assertThat(ModuleReadiness.checks(null, true, version).last().ok).isFalse()
        }
    }
}
