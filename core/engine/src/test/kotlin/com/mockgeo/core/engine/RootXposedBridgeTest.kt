package com.mockgeo.core.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RootXposedBridgeTest {
    class NativeTargets {
        fun fails(value: String, second: String, third: String): Boolean = false
        fun duringInstall(value: String): Boolean = false
        fun shared(value: String): Boolean = false
        fun unsupported(value: Double): Double = value
    }

    @Test fun `failed native install does not report success and releases the slot for retry`() {
        var attempts = 0
        val bridge = RootXposedBridge(installNative = { _, _, _, _ -> attempts++; false })
        val method = NativeTargets::class.java.getDeclaredMethod("fails", String::class.java, String::class.java, String::class.java)
        val before = bridge.hookedMethodsCount()
        repeat(10) {
            assertThrows(IllegalStateException::class.java) { bridge.hook(method).intercept { true } }
        }
        assertEquals(10, attempts)
        assertFalse(bridge.isMethodHooked(method))
        assertEquals(before, bridge.hookedMethodsCount())
    }

    @Test fun `native entry can dispatch immediately while installer is still returning`() {
        val method = NativeTargets::class.java.getDeclaredMethod("duringInstall", String::class.java)
        var observed: Any? = null
        val bridge = RootXposedBridge(installNative = { target, _, _, _ ->
            val slot = ArtBridgePool.acquireSlot(target)!!
            observed = RootXposedBridge.dispatchFromBridge(slot.key, arrayOf(NativeTargets(), "ready"))
            true
        })
        val handle = bridge.hook(method).intercept { it.getArg(0) == "ready" }
        assertEquals(true, observed)
        assertTrue(bridge.isMethodHooked(method))
        handle.unhook()
    }

    @Test fun `two bridges hook one native method only once and share its dispatch chain`() {
        val method = NativeTargets::class.java.getDeclaredMethod("shared", String::class.java)
        var attempts = 0
        val install: (java.lang.reflect.Executable, java.lang.reflect.Method, java.lang.reflect.Method, java.lang.reflect.Method) -> Boolean =
            { _, _, _, _ -> attempts++; true }
        val first = RootXposedBridge(installNative = install)
        val second = RootXposedBridge(installNative = install)
        val calls = mutableListOf<String>()
        val h1 = first.hook(method).intercept { chain -> calls += "first"; !(chain.proceed() as Boolean) }
        val h2 = second.hook(method).intercept { calls += "second"; true }
        assertEquals(1, attempts)
        assertEquals(false, first.invokeHooked(method, NativeTargets(), arrayOf("value")) { _, _ -> false })
        assertEquals(listOf("first", "second"), calls)
        h1.unhook()
        h2.unhook()
    }

    @Test fun `unsupported signature and class initializer cannot become successful hooks`() {
        val bridge = RootXposedBridge(installNative = { _, _, _, _ -> error("Must not install") })
        val method = NativeTargets::class.java.getDeclaredMethod("unsupported", Double::class.javaPrimitiveType)
        assertThrows(UnsupportedOperationException::class.java) { bridge.hook(method).intercept { 0.0 } }
        assertFalse(bridge.isMethodHooked(method))
        assertThrows(UnsupportedOperationException::class.java) { bridge.hookClassInitializer(NativeTargets::class.java) }
    }

    class SampleService {
        fun registerLocationRequest(req: Any, identity: Any, permLevel: Int, listener: Any) {}
        fun onLocationChanged(locations: List<Any>, callback: Any?) {}
        fun checkOperation(op: Int, uid: Int, pkg: String): Int = 0
        fun getLastLocation(provider: String, req: Any, pkg: String, attr: String?): Any? = null
        fun sendExtraCommand(provider: String, cmd: String, extras: Any?) {}
        fun isProviderEnabledForUser(provider: String, userId: Int): Boolean = false
    }

    @Test
    fun `shorty computation and slot allocation cover core system_server hooks`() {
        val cls = SampleService::class.java
        val mReg = cls.getDeclaredMethod("registerLocationRequest", Any::class.java, Any::class.java, Int::class.javaPrimitiveType, Any::class.java)
        val mOut = cls.getDeclaredMethod("onLocationChanged", List::class.java, Any::class.java)
        val mAppOps = cls.getDeclaredMethod("checkOperation", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        val mLast = cls.getDeclaredMethod("getLastLocation", String::class.java, Any::class.java, String::class.java, String::class.java)
        val mExtra = cls.getDeclaredMethod("sendExtraCommand", String::class.java, String::class.java, Any::class.java)
        val mEnabled = cls.getDeclaredMethod("isProviderEnabledForUser", String::class.java, Int::class.javaPrimitiveType)

        assertEquals("V_LLLIL", ArtBridgePool.methodShorty(mReg))
        assertEquals("V_LLL", ArtBridgePool.methodShorty(mOut))
        assertEquals("I_LIIL", ArtBridgePool.methodShorty(mAppOps))
        assertEquals("L_LLLLL", ArtBridgePool.methodShorty(mLast))
        assertEquals("V_LLLL", ArtBridgePool.methodShorty(mExtra))
        assertEquals("Z_LLI", ArtBridgePool.methodShorty(mEnabled))

        assertNotNull(ArtBridgePool.acquireSlot(mReg))
        assertNotNull(ArtBridgePool.acquireSlot(mOut))
        assertNotNull(ArtBridgePool.acquireSlot(mAppOps))
        assertNotNull(ArtBridgePool.acquireSlot(mLast))
        assertNotNull(ArtBridgePool.acquireSlot(mExtra))
        assertNotNull(ArtBridgePool.acquireSlot(mEnabled))
    }

    @Test
    fun `RootXposedBridge intercepts and rewrites args and return values and propagates SecurityException`() {
        val bridge = RootXposedBridge(nativeInstallEnabled = false)
        val service = SampleService()
        val mEnabled = SampleService::class.java.getDeclaredMethod(
            "isProviderEnabledForUser",
            String::class.java,
            Int::class.javaPrimitiveType,
        )

        val handle = bridge.hook(mEnabled).intercept { chain ->
            val orig = chain.proceed() as Boolean
            if (chain.getArg(0) == "gps") true else orig
        }
        assertTrue(bridge.isMethodHooked(mEnabled))

        val resGps = bridge.invokeHooked(mEnabled, service, arrayOf("gps", 0)) { _, _ -> false }
        val resNet = bridge.invokeHooked(mEnabled, service, arrayOf("passive", 0)) { _, _ -> false }
        assertEquals(true, resGps)
        assertEquals(false, resNet)

        // Verify framework SecurityException propagates untouched
        assertThrows(SecurityException::class.java) {
            bridge.invokeHooked(mEnabled, service, arrayOf("gps", 0)) { _, _ ->
                throw SecurityException("uid lacks ACCESS_FINE_LOCATION")
            }
        }

        // Verify unhook restores original behavior
        handle.unhook()
        val afterUnhook = bridge.invokeHooked(mEnabled, service, arrayOf("gps", 0)) { _, _ -> false }
        assertEquals(false, afterUnhook)
    }
}
