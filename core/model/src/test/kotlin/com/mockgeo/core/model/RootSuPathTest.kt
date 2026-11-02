package com.mockgeo.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RootSuPathTest {
    @Test fun `default stays PATH su and custom executable is preserved`() {
        assertThat(RootSuPath.executable("")).isEqualTo("su")
        assertThat(RootSuPath.normalize(" su ")).isEmpty()
        assertThat(RootSuPath.executable(" /data/local/tmp/my su ")).isEqualTo("/data/local/tmp/my su")
    }
    @Test fun `relative paths directories and control characters are rejected`() {
        for (bad in listOf("bin/su", "su -c id", "/", "/data/local/tmp/", "/data/su\n", "/data/su\u0000", "/data/\tsu")) {
            assertThrows<IllegalArgumentException> { RootSuPath.normalize(bad) }
        }
    }
}
