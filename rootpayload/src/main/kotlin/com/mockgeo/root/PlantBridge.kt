package com.mockgeo.root

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The module API adapter; all actual patches/backup calls are owned by LSPlant. */
class PlantBridge : XposedInterface {
    private val records = ConcurrentHashMap<Executable, Record>()
    val installedMethods: Set<Executable> get() = records.keys.toSet()

    class Record(val origin: Method) {
        val hookers = CopyOnWriteArrayList<XposedInterface.Hooker>()
        val installed = CountDownLatch(1)
        @Volatile var backup: Method? = null
        @Volatile var failure: Throwable? = null

        @Suppress("unused") // Called from the generated native hook stub.
        fun callback(raw: Array<Any?>): Any? {
            check(installed.await(5, TimeUnit.SECONDS)) { "Root hook installation did not complete" }
            failure?.let { throw it }
            val static = Modifier.isStatic(origin.modifiers)
            val receiver = if (static) null else raw.firstOrNull()
            val args = if (static) raw else raw.drop(1).toTypedArray()
            val original = checkNotNull(backup)
            return Chain(origin, hookers.toList(), 0, receiver, args) { target, values ->
                try { original.invoke(target, *values) }
                catch (error: InvocationTargetException) { throw error.targetException }
            }.proceed()
        }
    }

    override fun hook(origin: Executable): XposedInterface.HookBuilder = Builder(origin)
    private inner class Builder(private val origin: Executable) : XposedInterface.HookBuilder {
        private var id: String? = null
        override fun setId(id: String?) = apply { this.id = id }
        override fun setPriority(priority: Int) = apply { require(priority == 50) { "Custom hook priority is unsupported" } }
        override fun setExceptionMode(mode: XposedInterface.ExceptionMode) = this
        override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle = synchronized(records) {
            require(origin is Method) { "Only method hooks are supported" }
            var record = records[origin]
            if (record == null) {
                record = Record(origin)
                record.hookers.add(hooker)
                try {
                    record.backup = checkNotNull(NativeHooks.hook(origin, record,
                        Record::class.java.getDeclaredMethod("callback", Array<Any?>::class.java))) {
                        "Native hook failed: $origin"
                    }.apply { isAccessible = true }
                    records[origin] = record
                } catch (error: Throwable) {
                    record.failure = error
                    throw error
                } finally { record.installed.countDown() }
            } else record.hookers.add(hooker)
            Handle(origin, id ?: origin.toGenericString(), record, hooker)
        }
    }
    private class Handle(private val origin: Executable, private val id: String,
                         private val record: Record, private var hooker: XposedInterface.Hooker) : XposedInterface.HookHandle {
        override fun getExecutable() = origin
        override fun getId() = id
        override fun unhook() { record.hookers.remove(hooker) }
        override fun replaceHook(replacement: XposedInterface.Hooker): XposedInterface.HookHandle = synchronized(record) {
            val index = record.hookers.indexOf(hooker)
            check(index >= 0) { "Hook has been removed" }
            record.hookers[index] = replacement
            hooker = replacement
            this
        }
    }
    private class Chain(private val origin: Executable, private val hookers: List<XposedInterface.Hooker>,
                        private val index: Int, private val receiver: Any?, private val arguments: Array<Any?>,
                        private val original: (Any?, Array<Any?>) -> Any?) : XposedInterface.Chain {
        override fun getExecutable() = origin
        override fun getThisObject() = receiver
        override fun getArgs(): List<Any?> = arguments.toList()
        override fun getArg(index: Int) = arguments[index]
        override fun proceed() = next(receiver, arguments)
        override fun proceed(args: Array<out Any?>) = next(receiver, args.map { it }.toTypedArray())
        override fun proceedWith(thisObject: Any) = next(thisObject, arguments)
        override fun proceedWith(thisObject: Any, args: Array<out Any?>) = next(thisObject, args.map { it }.toTypedArray())
        private fun next(target: Any?, values: Array<Any?>): Any? =
            if (index == hookers.size) original(target, values)
            else hookers[index].intercept(Chain(origin, hookers, index + 1, target, values, original))
    }
    override fun deoptimize(method: Executable) = NativeHooks.deoptimize(method)
    override fun hookClassInitializer(origin: Class<*>): XposedInterface.HookBuilder =
        throw UnsupportedOperationException("Class initializer hooks are unsupported")
    override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> = throw UnsupportedOperationException()
    override fun <T : Any?> getInvoker(constructor: Constructor<T>): XposedInterface.CtorInvoker<T> = throw UnsupportedOperationException()
    override fun getFrameworkName() = "MockGeo-Root-LSPlant"
    override fun getFrameworkVersion() = BuildConfig.VERSION_NAME
    override fun getFrameworkVersionCode() = 102L
    override fun getFrameworkProperties() = XposedInterface.PROP_CAP_SYSTEM
    override fun getModuleApplicationInfo() = ApplicationInfo()
    override fun log(priority: Int, tag: String?, msg: String) { Log.println(priority, tag ?: "MockGeoRoot", msg) }
    override fun log(priority: Int, tag: String?, msg: String, tr: Throwable?) { log(priority, tag, "$msg\n${Log.getStackTraceString(tr)}") }
    override fun getRemotePreferences(group: String): SharedPreferences = throw UnsupportedOperationException()
    override fun listRemoteFiles(): Array<String> = emptyArray()
    override fun openRemoteFile(name: String): ParcelFileDescriptor = throw FileNotFoundException(name)
}
