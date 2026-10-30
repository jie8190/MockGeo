package com.mockgeo.module

import android.app.PendingIntent
import android.location.Location
import android.os.Binder
import com.mockgeo.core.model.ControlPlane
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Recipient-bound output hooks. Provider caches and global Parcel methods remain untouched. */
object HookInstaller {
    private val lastNames = setOf("getLastLocation", "getLastLocationWithPackage")
    private val registerNames = setOf("requestLocationUpdates", "registerLocationListener", "requestSingleUpdate",
        "getCurrentLocation", "requestLocationUpdatesListener", "registerLocationPendingIntent")
    private val releaseNames = setOf("removeUpdates", "unregisterLocationListener", "unregisterLocationPendingIntent")
    private val gnssAdd = setOf("registerGnssStatusCallback", "addGnssStatusListener")
    private val gnssRemove = setOf("unregisterGnssStatusCallback", "removeGnssStatusListener")
    private val nmeaAdd = setOf("registerGnssNmeaCallback", "addNmeaListener")
    private val nmeaRemove = setOf("unregisterGnssNmeaCallback", "removeNmeaListener")
    private val enabledNames = setOf("isProviderEnabledForUser", "isProviderEnabled", "isLocationEnabled", "isLocationEnabledForUser")
    private val names = lastNames + registerNames + releaseNames + gnssAdd + gnssRemove + nmeaAdd + nmeaRemove + enabledNames + "sendExtraCommand"

    @Volatile var installedCount = 0
        private set

    fun install(api: XposedInterface, loader: ClassLoader): Int {
        installedCount = 0
        val cls = listOf("com.android.server.location.LocationManagerService", "com.android.server.LocationManagerService")
            .firstNotNullOfOrNull { name -> runCatching { Class.forName(name, false, loader) }.getOrNull() }
        if (cls != null) {
            var current: Class<*>? = cls
            while (current != null && current != Any::class.java) {
                current.declaredMethods.filter { it.name in names && !Modifier.isAbstract(it.modifiers) }.forEach { method ->
                    hook(api, method) { call -> dispatch(method.name, call) }
                }
                current = current.superclass
            }
        } else ModuleLog.w("LocationManagerService not found")
        installPendingTransports(api, loader)
        installPendingPayloads(api)
        installListenerDispatch(api, loader)
        installedCount += EvidenceHooks.install(api, loader)
        ModuleConfig.hooksInstalled = installedCount
        ModuleLog.d("install finished: $installedCount recipient hooks")
        return installedCount
    }

    private class Call(val chain: XposedInterface.Chain) {
        var proceeded = false
        var originalResult: Any? = null
        var originalFailure: Throwable? = null
        val args get() = chain.args
        val receiver get() = chain.thisObject
        fun proceed(args: Array<Any?>? = null): Any? {
            check(!proceeded)
            proceeded = true
            return try { (if (args == null) chain.proceed() else chain.proceed(args)).also { originalResult = it } }
            catch (error: Throwable) { originalFailure = error; throw error }
        }
    }

    private fun hook(api: XposedInterface, method: Method, dispatch: (Call) -> Any?) {
        runCatching {
            api.hook(method).intercept(XposedInterface.Hooker { chain ->
                val call = Call(chain)
                val value = try { dispatch(call) } catch (error: Throwable) {
                    call.originalFailure?.let { throw it } // never hide framework permission errors
                    ModuleLog.w("hook ${method.name} fallback", error)
                    if (call.proceeded) call.originalResult else call.proceed()
                }
                if (method.returnType == Void.TYPE) null else value
            })
            installedCount++
        }.onFailure { ModuleLog.w("hook ${method.name} install failed", it) }
    }

    private fun dispatch(name: String, call: Call): Any? {
        val args = call.args
        val uid = Binder.getCallingUid()
        if (name == "sendExtraCommand") {
            return if (args.firstOrNull() == ControlPlane.PROVIDER)
                CommandDispatcher.intercept(args.getOrNull(1) as? String, args.getOrNull(2) as? android.os.Bundle)
            else call.proceed()
        }
        if (name in lastNames) {
            val original = call.proceed()
            val cfg = LocationDelivery.config(uid) ?: return original
            HookDiagnostics.lastCalls.incrementAndGet()
            return FakeLocationFactory.create(ProviderResolver.fallbackFor(name, args), cfg)
        }
        if (name == "getCurrentLocation") {
            val provider = ProviderResolver.fallbackFor(name, args)
            val bridges = args.map { CurrentLocationBridge.wrap(it, provider, uid) }
            val result = call.proceed(args.mapIndexed { index, arg -> bridges[index]?.callback ?: arg }.toTypedArray())
            if (LocationDelivery.config(uid) != null) HookDiagnostics.currentCalls.incrementAndGet()
            bridges.filterNotNull().forEach { it.deliverIfEnabled(result) }
            return result
        }
        if (name in registerNames || name in releaseNames) {
            val provider = ProviderResolver.fallbackFor(name, args)
            val policy = RequestPolicy.from(args, name == "requestSingleUpdate")
            val releasing = name in releaseNames
            val forwarded = if (releasing) args else args.map {
                LocationDelivery.listener(it, provider, uid, policy.maxUpdates) ?: it
            }
            val result = call.proceed(forwarded.toTypedArray())
            if (releasing) args.forEach {
                ListenerPusher.unregister(it)
                LocationDelivery.unregister(it)
                if (it is PendingIntent) PendingIntentPusher.unregister(it)
            } else if (result != false) {
                val receiver = call.receiver
                if (LocationDelivery.config(uid) != null) HookDiagnostics.listenerCalls.incrementAndGet()
                forwarded.forEach { arg ->
                    val complete = { releaseCompleted(receiver, arg) }
                    if (arg is PendingIntent) PendingIntentPusher.register(arg, provider, policy, complete)
                    else {
                        LocationDelivery.registerValidated(arg, provider, uid, policy.maxUpdates)
                        ListenerPusher.register(arg, provider, policy, complete, uid)
                    }
                }
            }
            return result
        }
        if (name in gnssAdd || name in nmeaAdd) {
            val nmea = name in nmeaAdd
            val candidates = args.map { GnssPusher.prepare(it, nmea, uid) }
            val result = call.proceed(args.mapIndexed { index, arg -> candidates[index]?.proxy ?: arg }.toTypedArray())
            if (result != false) candidates.filterNotNull().forEach(GnssPusher::register)
            return result
        }
        if (name in gnssRemove || name in nmeaRemove) {
            val result = call.proceed()
            args.forEach { GnssPusher.unregister(it, name in nmeaRemove) }
            return result
        }
        val original = call.proceed()
        if (name in enabledNames && original is Boolean && LocationDelivery.config(uid) != null) {
            return if (name.startsWith("isLocationEnabled")) true
            else if (args.firstOrNull() in setOf("gps", "network", "fused")) true else original
        }
        return original
    }

    /** Exact Android delivery class verified on-device; no blanket provider/report replacement. */
    private fun installPendingTransports(api: XposedInterface, loader: ClassLoader) {
        val candidates = listOf(
            "com.android.server.location.provider.LocationProviderManager\$LocationPendingIntentTransport",
            "com.android.server.location.LocationProviderManager\$LocationPendingIntentTransport",
            "com.android.server.LocationManagerService\$Receiver",
            "com.android.server.location.LocationManagerService\$Receiver")
        var count = 0
        candidates.forEach { name ->
            val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: return@forEach
            val pending = cls.declaredFields.firstOrNull { it.type == PendingIntent::class.java } ?: return@forEach
            pending.isAccessible = true
            cls.declaredMethods.filter { it.name in setOf("deliverOnLocationChanged", "callLocationChangedLocked") }.forEach { m ->
                val before = installedCount
                hook(api, m) { call ->
                    val intent = pending.get(call.receiver) as? PendingIntent
                    val cfg = intent?.let { LocationDelivery.config(it.creatorUid) }
                    if (cfg == null) call.proceed()
                    else call.proceed(call.args.map { LocationDelivery.rewrite(it, null, cfg) }.toTypedArray())
                }
                count += installedCount - before
            }
        }
        ModuleLog.d("PendingIntent delivery hooks: $count")
    }

    private fun releaseCompleted(receiver: Any?, arg: Any?) {
        if (receiver == null || arg == null) return
        val name = if (arg is PendingIntent) "unregisterLocationPendingIntent" else "unregisterLocationListener"
        runCatching { receiver.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 && it.parameterTypes[0].isInstance(arg)
        }?.invoke(receiver, arg) }.onFailure { ModuleLog.w("finite subscription cleanup failed", it) }
    }

    /** OEM power optimization reconstructs ILocationListener from its Binder, discarding wrappers. */
    private fun installListenerDispatch(api: XposedInterface, loader: ClassLoader) {
        val listener = Class.forName("android.location.ILocationListener", false, loader)
        val proxy = Class.forName("android.location.ILocationListener\$Stub\$Proxy", false, loader)
        proxy.declaredMethods.filter { it.name == "onLocationChanged" }.forEach { method ->
            hook(api, method) { call ->
                LocationDelivery.outgoing(call.receiver, call.args.toTypedArray()) { call.proceed(it) }
            }
        }
        for (name in listOf("com.android.server.location.provider.LocationProviderManager", "com.android.server.location.LocationProviderManager")) {
            val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            val getName = cls.getMethod("getName")
            cls.declaredMethods.filter { it.name == "registerLocationRequest" && it.parameterTypes.contains(listener) }.forEach { method ->
                hook(api, method) { call ->
                    val identity = call.args.firstOrNull { it?.javaClass?.name?.endsWith(".CallerIdentity") == true }
                    val uid = identity?.javaClass?.getMethod("getUid")?.invoke(identity) as? Int
                    val provider = getName.invoke(call.receiver) as? String
                    val candidate = call.args.firstOrNull { listener.isInstance(it) }
                    val change = if (uid != null && provider != null)
                        LocationDelivery.registerValidated(candidate, provider, uid, RequestPolicy.from(call.args).maxUpdates) else null
                    try { call.proceed() } catch (error: Throwable) { LocationDelivery.rollback(change); throw error }
                }
            }
            // Ensure precompiled callers dispatch through the replaced Binder proxy method.
            runCatching { Class.forName(name + "\$LocationListenerTransport", false, loader).declaredMethods
                .filter { it.name == "deliverOnLocationChanged" }.forEach { api.deoptimize(it) } }
        }
        // The provider registration call can itself be inlined into LMS on an AOT ROM.
        for (name in listOf("com.android.server.location.LocationManagerService", "com.android.server.LocationManagerService")) {
            runCatching { Class.forName(name, false, loader).declaredMethods
                .filter { it.name in registerNames }.forEach { api.deoptimize(it) } }
        }
    }

    /** Also cover cached/optimized ROM deliveries that bypass the Java transport hook. */
    private fun installPendingPayloads(api: XposedInterface) {
        PendingIntent::class.java.declaredMethods.filter { method ->
            method.name in setOf("send", "sendAndReturnResult") &&
                method.parameterTypes.contains(android.content.Intent::class.java)
        }.forEach { method ->
            hook(api, method) { call ->
                val pending = call.receiver as? PendingIntent
                val cfg = pending?.let { LocationDelivery.config(it.creatorUid) }
                if (cfg == null) call.proceed() else call.proceed(call.args.map { arg ->
                    if (arg !is android.content.Intent) arg else {
                        val extras = arg.extras
                        var changed: android.content.Intent? = null
                        for (key in listOf("location", "locations")) {
                            @Suppress("DEPRECATION") val value = extras?.get(key)
                            if (value is Location) {
                                if (changed == null) changed = android.content.Intent(arg)
                                changed.putExtra(key, FakeLocationFactory.fromReal(value, cfg))
                            } else if (value is Array<*> && value.all { it is Location }) {
                                if (changed == null) changed = android.content.Intent(arg)
                                changed.putExtra(key, value.map { FakeLocationFactory.fromReal(it as Location, cfg) }.toTypedArray())
                            }
                        }
                        changed ?: arg
                    }
                }.toTypedArray())
            }
        }
    }
}
