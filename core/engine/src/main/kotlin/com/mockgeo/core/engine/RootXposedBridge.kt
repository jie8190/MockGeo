package com.mockgeo.core.engine

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.io.FileNotFoundException
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Root-mode implementation of `io.github.libxposed.api.XposedInterface` (API 102)
 * backed by `NativeArtBridge` (`libmockgeo_art.so`) + `ArtBridgePool`.
 *
 * Allows `HookInstaller`, `EvidenceHooks`, `AppScopeHooks`, and `SensorHooks` in `:module`
 * to run in `system_server` without LSPosed.
 */
class RootXposedBridge(
    private val nativeInstallEnabled: Boolean = true,
) : XposedInterface {

    data class HookRecord(
        val executable: Executable,
        val slotKey: String?,
        val bakMethod: Method?,
        val isStatic: Boolean,
        val hookers: CopyOnWriteArrayList<XposedInterface.Hooker> = CopyOnWriteArrayList(),
    )

    private val recordsByExecutable = ConcurrentHashMap<Executable, HookRecord>()

    fun hookedMethodsCount(): Int = recordsByExecutable.size

    fun isMethodHooked(executable: Executable): Boolean = recordsByExecutable.containsKey(executable)

    override fun getFrameworkName(): String = "MockGeo-RootART"

    override fun getFrameworkVersion(): String = "1.0.0"

    override fun getFrameworkVersionCode(): Long = 102L

    override fun getFrameworkProperties(): Long = XposedInterface.PROP_CAP_SYSTEM

    override fun hook(origin: Executable): XposedInterface.HookBuilder {
        return Builder(origin)
    }

    override fun hookClassInitializer(origin: Class<*>): XposedInterface.HookBuilder {
        val clinit = origin.declaredConstructors.firstOrNull()
            ?: throw IllegalArgumentException("No initializer found for ${origin.name}")
        return Builder(clinit)
    }

    override fun deoptimize(method: Executable): Boolean {
        return if (nativeInstallEnabled) {
            NativeArtBridge.deoptimizeMethod(method)
        } else {
            true
        }
    }

    override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> {
        throw UnsupportedOperationException("getInvoker(Method) not required by MockGeo hooks")
    }

    override fun <T : Any?> getInvoker(constructor: Constructor<T>): XposedInterface.CtorInvoker<T> {
        throw UnsupportedOperationException("getInvoker(Constructor) not required by MockGeo hooks")
    }

    override fun log(priority: Int, tag: String?, msg: String) {
        runCatching { Log.println(priority, tag ?: TAG, msg) }
    }

    override fun log(priority: Int, tag: String?, msg: String, tr: Throwable?) {
        runCatching { Log.println(priority, tag ?: TAG, "$msg\n${Log.getStackTraceString(tr)}") }
    }

    override fun getModuleApplicationInfo(): ApplicationInfo = ApplicationInfo()

    override fun getRemotePreferences(group: String): SharedPreferences {
        throw UnsupportedOperationException("RemotePreferences not used; MockGeo uses ControlPlane")
    }

    override fun listRemoteFiles(): Array<String> = emptyArray()

    override fun openRemoteFile(name: String): ParcelFileDescriptor {
        throw FileNotFoundException(name)
    }

    /**
     * Direct invocation helper for JVM unit tests and proxy fallback.
     */
    fun invokeHooked(
        executable: Executable,
        thisObject: Any?,
        args: Array<Any?>,
        fallbackProceed: (Any?, Array<Any?>) -> Any?,
    ): Any? {
        val record = recordsByExecutable[executable]
            ?: return fallbackProceed(thisObject, args)
        val chain = RootChain(
            executable = executable,
            hookers = record.hookers.toList(),
            index = 0,
            thisObject = thisObject,
            currentArgs = args,
            originInvoke = fallbackProceed,
        )
        return chain.proceedInternal(thisObject, args)
    }

    private inner class Builder(private val origin: Executable) : XposedInterface.HookBuilder {
        private var id: String? = null

        override fun setPriority(priority: Int): XposedInterface.HookBuilder = this

        override fun setExceptionMode(mode: XposedInterface.ExceptionMode): XposedInterface.HookBuilder = this

        override fun setId(id: String?): XposedInterface.HookBuilder {
            this.id = id
            return this
        }

        override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
            val isStatic = Modifier.isStatic(origin.modifiers)
            val existing = recordsByExecutable[origin]
            if (existing != null) {
                existing.hookers.add(hooker)
                return Handle(origin, id ?: existing.slotKey ?: origin.name, existing, hooker)
            }

            var slotKey: String? = null
            var bakMethod: Method? = null
            if (nativeInstallEnabled && origin is Method) {
                val slot = ArtBridgePool.acquireSlot(origin)
                if (slot != null) {
                    val hooked = NativeArtBridge.hookMethod(
                        target = origin,
                        hook = slot.hookMethod,
                        backup = slot.bakMethod,
                        copy = slot.copyMethod,
                    )
                    if (hooked) {
                        slotKey = slot.key
                        bakMethod = slot.bakMethod
                    }
                }
            }

            val record = HookRecord(
                executable = origin,
                slotKey = slotKey,
                bakMethod = bakMethod,
                isStatic = isStatic,
            )
            record.hookers.add(hooker)
            recordsByExecutable[origin] = record
            if (slotKey != null) {
                slotsToRecords[slotKey] = record
            }
            return Handle(origin, id ?: slotKey ?: origin.name, record, hooker)
        }
    }

    private class Handle(
        private val origin: Executable,
        private val handleId: String,
        private val record: HookRecord,
        @Volatile private var currentHooker: XposedInterface.Hooker,
    ) : XposedInterface.HookHandle {
        override fun getExecutable(): Executable = origin

        override fun getId(): String = handleId

        override fun unhook() {
            record.hookers.remove(currentHooker)
        }

        override fun replaceHook(replacement: XposedInterface.Hooker): XposedInterface.HookHandle {
            val idx = record.hookers.indexOf(currentHooker)
            if (idx >= 0) {
                record.hookers[idx] = replacement
            } else {
                record.hookers.add(replacement)
            }
            currentHooker = replacement
            return this
        }
    }

    private class RootChain(
        private val executable: Executable,
        private val hookers: List<XposedInterface.Hooker>,
        private val index: Int,
        private val thisObject: Any?,
        private val currentArgs: Array<Any?>,
        private val originInvoke: (Any?, Array<Any?>) -> Any?,
    ) : XposedInterface.Chain {

        override fun getExecutable(): Executable = executable

        override fun getThisObject(): Any? = thisObject

        override fun getArgs(): List<Any?> = Collections.unmodifiableList(currentArgs.asList())

        override fun getArg(index: Int): Any? = currentArgs[index]

        override fun proceed(): Any? = proceedInternal(thisObject, currentArgs)

        override fun proceed(args: Array<out Any?>): Any? =
            proceedInternal(thisObject, Array(args.size) { args[it] })

        override fun proceedWith(thisObject: Any): Any? = proceedInternal(thisObject, currentArgs)

        override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? =
            proceedInternal(thisObject, Array(args.size) { args[it] })

        fun proceedInternal(targetThis: Any?, args: Array<Any?>): Any? {
            if (index < hookers.size) {
                val nextChain = RootChain(
                    executable = executable,
                    hookers = hookers,
                    index = index + 1,
                    thisObject = targetThis,
                    currentArgs = args,
                    originInvoke = originInvoke,
                )
                return hookers[index].intercept(nextChain)
            }
            return originInvoke(targetThis, args)
        }
    }

    companion object {
        private const val TAG = "RootXposedBridge"
        private val slotsToRecords = ConcurrentHashMap<String, HookRecord>()

        @JvmStatic
        fun dispatchFromBridge(slotKey: String, rawArgs: Array<out Any?>): Any? {
            val record = slotsToRecords[slotKey] ?: return null
            val receiver: Any?
            val callArgs: Array<Any?>
            if (record.isStatic) {
                receiver = null
                callArgs = Array(rawArgs.size) { rawArgs[it] }
            } else {
                receiver = rawArgs.firstOrNull()
                callArgs = if (rawArgs.size <= 1) {
                    emptyArray()
                } else {
                    Array(rawArgs.size - 1) { rawArgs[it + 1] }
                }
            }

            val originInvoke: (Any?, Array<Any?>) -> Any? = { targetThis, targetArgs ->
                val bak = record.bakMethod
                    ?: throw IllegalStateException("Missing backup method for slot $slotKey")
                val fullArgs = if (record.isStatic) {
                    targetArgs
                } else {
                    Array(targetArgs.size + 1) { i -> if (i == 0) targetThis else targetArgs[i - 1] }
                }
                try {
                    bak.invoke(null, *fullArgs)
                } catch (ite: InvocationTargetException) {
                    throw ite.targetException ?: ite
                }
            }

            val chain = RootChain(
                executable = record.executable,
                hookers = record.hookers.toList(),
                index = 0,
                thisObject = receiver,
                currentArgs = callArgs,
                originInvoke = originInvoke,
            )
            return chain.proceed()
        }
    }
}
