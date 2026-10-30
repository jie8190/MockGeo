package com.mockgeo.core.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootXposedBridgeTest {

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
