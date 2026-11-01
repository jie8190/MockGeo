package com.mockgeo.module

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Bounded reflection over the known framework object, including OEM subclasses. */
internal object FrameworkAccess {
    fun hierarchy(type: Class<*>): Sequence<Class<*>> =
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }

    fun methods(type: Class<*>): List<Method> = hierarchy(type).flatMap { it.declaredMethods.asSequence() }
        .filter { !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) }.toList()

    fun field(type: Class<*>, fieldType: Class<*>): Field? = hierarchy(type)
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { !Modifier.isStatic(it.modifiers) && fieldType.isAssignableFrom(it.type) }
        ?.apply { isAccessible = true }

    fun value(instance: Any, getter: String, vararg fields: String): Any? {
        for (type in hierarchy(instance.javaClass)) {
            runCatching { type.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(instance) }
                .getOrNull()?.let { return it }
            for (name in fields) {
                runCatching { type.getDeclaredField(name).apply { isAccessible = true }.get(instance) }
                    .getOrNull()?.let { return it }
            }
        }
        return null
    }

    fun localLocationService(): Any? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("checkService", String::class.java).invoke(null, "location")
        // BinderProxy is never a hook target. Do not load a duplicate services.jar.
        binder?.takeIf { it is android.os.Binder &&
            Class.forName("android.location.ILocationManager").isInstance(it) }
    }.getOrNull()
}
