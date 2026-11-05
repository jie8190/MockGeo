package com.mockgeo.app.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LocalLocationAgeTest {
    @Test fun `missing and future monotonic timestamps are unknown rather than fresh`() {
        assertThat(locationAgeMs(0, 2000)).isNull()
        assertThat(locationAgeMs(3000, 2000)).isNull()
        assertThat(locationAgeMs(1_000_000_000, 3_000_000_000)).isEqualTo(2000)
    }
}
