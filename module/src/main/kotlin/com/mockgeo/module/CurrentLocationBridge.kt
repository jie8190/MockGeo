package com.mockgeo.module

import android.location.Location
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** Bridges API 30+ one-shot callbacks after the framework has checked the request. */
internal class CurrentLocationBridge private constructor(
    private val original: Any,
    private val callbackType: Class<*>,
    private val provider: String,
    private val ownerUid: Int,
) {
    private val delivered = AtomicBoolean(false)
    val callback: Any = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, args ->
        if (method.name == "onLocation" && method.parameterTypes.contentEquals(arrayOf(Location::class.java))) {
            if (!delivered.compareAndSet(false, true)) return@newProxyInstance null
            val current = LocationDelivery.config(ownerUid)
            val output = if (current != null) FakeLocationFactory.create(provider, current) else args?.getOrNull(0)
            invokeOriginal { method.invoke(original, output) }.also {
                if (current != null) HookDiagnostics.pushes.incrementAndGet()
            }
        } else {
            invokeOriginal { method.invoke(original, *(args ?: emptyArray())) }
        }
    }

    /**
     * Invoked only after the original LMS registration returned successfully.
     *
     * The framework's client transport handles caller CancellationSignal state.
     * After synthetic delivery cancel the real server request and retain its returned signal.
     */
    fun deliverIfEnabled(fallbackCancellation: Any?) {
        val current = LocationDelivery.config(ownerUid) ?: return
        if (delivered.get()) return
        try {
            callbackType.getMethod("onLocation", Location::class.java)
                .invoke(callback, FakeLocationFactory.create(provider, current))
            if (delivered.get()) {
                cancelSignal(fallbackCancellation)
            }
        } catch (error: Throwable) {
            ModuleLog.w("current-location delivery failed", error)
        }
    }

    private fun cancelSignal(cancellation: Any?) {
        if (cancellation == null) return
        runCatching {
            val type = Class.forName("android.os.ICancellationSignal")
            if (type.isInstance(cancellation)) type.getMethod("cancel").invoke(cancellation)
        }.onFailure { ModuleLog.w("cancel fallback signal failed", it) }
    }

    private fun invokeOriginal(action: () -> Any?): Any? = try { action() }
    catch (error: InvocationTargetException) { throw error.targetException }

    companion object {
        fun wrap(
            candidate: Any?,
            provider: String,
            ownerUid: Int = android.os.Binder.getCallingUid(),
        ): CurrentLocationBridge? {
            if (candidate == null) return null
            val type = runCatching { Class.forName("android.location.ILocationCallback") }.getOrNull() ?: return null
            if (!type.isInstance(candidate)) return null
            return CurrentLocationBridge(candidate, type, provider, ownerUid)
        }
    }
}
