package com.mockgeo.module

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProviderResolverTest {

    @Test
    fun `string argument wins over others`() {
        assertEquals("network", ProviderResolver.fromArgs(listOf<Any?>(42, "network", null)))
    }

    @Test
    fun `unknown strings are ignored`() {
        assertNull(ProviderResolver.fromArgs(listOf<Any?>("gps2", "mockgeo")))
    }

    @Test
    fun `null args resolve to null`() {
        assertNull(ProviderResolver.fromArgs(listOf<Any?>(null, null)))
    }

    @Test
    fun `fallback reads provider from method name`() {
        assertEquals("network", ProviderResolver.fallbackFor("requestLocationUpdatesNetwork", emptyList<Any?>()))
    }

    @Test
    fun `fallback defaults to gps`() {
        assertEquals(ProviderResolver.GPS, ProviderResolver.fallbackFor("requestLocationUpdates", emptyList<Any?>()))
    }

    @Test
    fun `normalize trims and lowercases`() {
        assertEquals(ProviderResolver.FUSED, ProviderResolver.normalize(" FUSED "))
        assertNull(ProviderResolver.normalize("bogus"))
        assertNull(ProviderResolver.normalize(null))
    }
}
