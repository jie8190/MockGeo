package com.mockgeo.core.engine

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleVersionTest {
    @Test fun `older or missing loaded modules are rejected`() {
        listOf(null, "", "0.3.0", "0.2.9", "not-a-version", "0.3.1-invalid").forEach {
            assertFalse(XposedEngine.moduleVersionSupported(it), "version=$it")
        }
    }
    @Test fun `fixed and newer modules are accepted numerically`() {
        listOf("0.3.1", "0.3.2", "0.10.0", "1.0.0").forEach {
            assertTrue(XposedEngine.moduleVersionSupported(it), "version=$it")
        }
    }
}
