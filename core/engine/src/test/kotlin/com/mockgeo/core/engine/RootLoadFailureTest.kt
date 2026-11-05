package com.mockgeo.core.engine

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RootLoadFailureTest {
    @Test fun `initial interrupt is distinguished from secondary cleanup failure`() {
        val text = RootLoadFailure.describe("target restoration failed\nPTRACE_INTERRUPT before remote execution failed; errno=5 (I/O error)")
        assertThat(text).contains("尚未进入载荷加载")
        assertThat(text).contains("errno=5")
    }
    @Test fun `post execution restoration failure does not encourage another injection`() {
        assertThat(RootLoadFailure.describe("target restoration failed after register modification")).contains("不要继续反复注入")
    }
}
