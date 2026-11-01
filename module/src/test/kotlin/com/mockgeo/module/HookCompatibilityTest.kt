package com.mockgeo.module

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HookCompatibilityTest {
    @Test fun `ancillary or partial installation never reports a ready location engine`() {
        assertThat(HookReadiness.missing(setOf("send", "checkOperation", "getScanResults"))).hasSize(6)
        assertThat(HookReadiness.missing(setOf("sendExtraCommand", "getLastLocation", "getCurrentLocation",
            "registerLocationListener", "unregisterLocationListener"))).containsExactly("delivery")
    }

    @Test fun `transport delivery and legacy registration names satisfy required coverage`() {
        assertThat(HookReadiness.missing(setOf("sendExtraCommand", "getLastLocationWithPackage", "getCurrentLocation",
            "requestLocationUpdates", "removeUpdates", "listenerTransport"))).isEmpty()
    }

    @Test fun `older Android does not require a current-location API that does not exist`() {
        val installed = setOf("sendExtraCommand", "getLastLocation", "requestLocationUpdates", "removeUpdates", "onLocationChanged")
        assertThat(HookReadiness.missing(installed, 29)).isEmpty()
        assertThat(HookReadiness.missing(installed, 37)).containsExactly("current")
    }

    private open class Identity(@Suppress("unused") private val mUid: Int)
    private class VendorIdentity : Identity(10345)
    private class GetterIdentity { @Suppress("unused") fun getUid() = 10456 }
    private class FallbackIdentity {
        @Suppress("unused") private val uid = 10567
        @Suppress("unused") fun getUid(): Int = error("vendor getter unavailable")
    }
    @Test fun `identity reads inherited private fields as well as getters`() {
        assertThat(FrameworkAccess.value(VendorIdentity(), "getUid", "uid", "mUid")).isEqualTo(10345)
        assertThat(FrameworkAccess.value(GetterIdentity(), "getUid", "uid", "mUid")).isEqualTo(10456)
        assertThat(FrameworkAccess.value(FallbackIdentity(), "getUid", "uid", "mUid")).isEqualTo(10567)
        assertThat(FrameworkAccess.value(Any(), "getUid", "uid", "mUid")).isNull()
    }

    private interface Listener
    private open class Transport(@Suppress("unused") private val listener: Listener)
    private class VendorTransport(listener: Listener) : Transport(listener)
    @Test fun `transport finds recipient field inherited from framework base class`() {
        val target = object : Listener {}
        val transport = VendorTransport(target)
        assertThat(FrameworkAccess.field(transport.javaClass, Listener::class.java)!!.get(transport)).isSameInstanceAs(target)
    }

    private open class Service { open fun getLastLocation() = 1; fun sendExtraCommand() = 2 }
    private class VendorService : Service() { override fun getLastLocation() = 3 }
    @Test fun `discovery includes vendor overrides and inherited entry points`() {
        val methods = FrameworkAccess.methods(VendorService::class.java)
        assertThat(methods.filter { it.name == "getLastLocation" }.map { it.declaringClass })
            .containsExactly(VendorService::class.java, Service::class.java)
        assertThat(methods.any { it.name == "sendExtraCommand" }).isTrue()
    }
}
