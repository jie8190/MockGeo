package com.mockgeo.root

import java.lang.reflect.Executable
import java.lang.reflect.Method

/** Registered by the injected native worker in this VM, never via a test-process proxy. */
object NativeHooks {
    @JvmStatic external fun initialize(): Boolean
    @JvmStatic external fun hook(target: Executable, context: Any, callback: Method): Method?
    @JvmStatic external fun deoptimize(target: Executable): Boolean
}
