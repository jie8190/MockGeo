package com.mockgeo.core.engine

import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Pre-compiled static bridge/backup/copy triplets indexed by ART shorty signature.
 *
 * In ART's Quick & Nterp calling conventions, all object references (`L...;` and `[...`)
 * share shorty `'L'`, while primitives use `'Z'`, `'I'`, `'J'`, `'F'`, `'D'`, `'B'`, `'S'`, `'C'`.
 * Non-static methods pass `this` as the first `'L'` parameter in register `w1`.
 */
@Suppress("UNUSED_PARAMETER", "FunctionName")
object ArtBridgePool {

    @Volatile
    var antiInlineSink: Long = 0L

    data class SlotMethods(
        val key: String,
        val hookMethod: Method,
        val bakMethod: Method,
        val copyMethod: Method,
    )

    private val allocatedByKey = ConcurrentHashMap<String, SlotMethods>()
    private val usedSlotKeys = ConcurrentHashMap.newKeySet<String>()

    fun typeShorty(type: Class<*>): Char = when (type) {
        Void.TYPE -> 'V'
        Boolean::class.javaPrimitiveType -> 'Z'
        Int::class.javaPrimitiveType -> 'I'
        Long::class.javaPrimitiveType -> 'J'
        Float::class.javaPrimitiveType -> 'F'
        Double::class.javaPrimitiveType -> 'D'
        Byte::class.javaPrimitiveType -> 'B'
        Short::class.javaPrimitiveType -> 'S'
        Char::class.javaPrimitiveType -> 'C'
        else -> 'L'
    }

    fun methodShorty(method: Method): String {
        val ret = typeShorty(method.returnType)
        val sb = StringBuilder()
        sb.append(ret).append('_')
        if (!Modifier.isStatic(method.modifiers)) {
            sb.append('L')
        }
        for (p in method.parameterTypes) {
            sb.append(typeShorty(p))
        }
        return sb.toString()
    }

    @Synchronized
    fun acquireSlot(target: Executable): SlotMethods? {
        if (target !is Method) return null
        val targetId = "${target.declaringClass.name}#${target.name}(${target.parameterTypes.joinToString(",") { it.name }})"
        allocatedByKey[targetId]?.let { return it }

        val shorty = methodShorty(target)
        val cls = ArtBridgePool::class.java
        for (idx in 0 until 8) {
            val slotKey = "${shorty}_$idx"
            if (slotKey in usedSlotKeys) continue
            val hookName = "hook_$slotKey"
            val bakName = "bak_$slotKey"
            val copyName = "copy_$slotKey"
            val hookM = cls.declaredMethods.firstOrNull { it.name == hookName } ?: break
            val bakM = cls.declaredMethods.firstOrNull { it.name == bakName } ?: break
            val copyM = cls.declaredMethods.firstOrNull { it.name == copyName } ?: break
            usedSlotKeys += slotKey
            val slot = SlotMethods(slotKey, hookM, bakM, copyM)
            allocatedByKey[targetId] = slot
            return slot
        }
        return null
    }

    @JvmStatic
    private fun guardInline(tag: Int): Long {
        var acc = System.nanoTime() xor tag.toLong()
        for (i in 0 until 16) {
            acc = (acc * 31L) xor i.toLong()
        }
        antiInlineSink = acc
        return acc
    }

    @JvmStatic
    private fun invokeSlot(slotKey: String, vararg rawArgs: Any?): Any? {
        return RootXposedBridge.dispatchFromBridge(slotKey, rawArgs)
    }

    // ==================== V_LL (8 slots) ====================
    @JvmStatic fun hook_V_LL_0(a0: Any?, a1: Any?) { invokeSlot("V_LL_0", a0, a1) }
    @JvmStatic fun bak_V_LL_0(a0: Any?, a1: Any?) { if (guardInline(100) != Long.MIN_VALUE) copy_V_LL_0(a0, a1) }
    @JvmStatic fun copy_V_LL_0(a0: Any?, a1: Any?) { guardInline(1000) }

    @JvmStatic fun hook_V_LL_1(a0: Any?, a1: Any?) { invokeSlot("V_LL_1", a0, a1) }
    @JvmStatic fun bak_V_LL_1(a0: Any?, a1: Any?) { if (guardInline(101) != Long.MIN_VALUE) copy_V_LL_1(a0, a1) }
    @JvmStatic fun copy_V_LL_1(a0: Any?, a1: Any?) { guardInline(1001) }

    @JvmStatic fun hook_V_LL_2(a0: Any?, a1: Any?) { invokeSlot("V_LL_2", a0, a1) }
    @JvmStatic fun bak_V_LL_2(a0: Any?, a1: Any?) { if (guardInline(102) != Long.MIN_VALUE) copy_V_LL_2(a0, a1) }
    @JvmStatic fun copy_V_LL_2(a0: Any?, a1: Any?) { guardInline(1002) }

    @JvmStatic fun hook_V_LL_3(a0: Any?, a1: Any?) { invokeSlot("V_LL_3", a0, a1) }
    @JvmStatic fun bak_V_LL_3(a0: Any?, a1: Any?) { if (guardInline(103) != Long.MIN_VALUE) copy_V_LL_3(a0, a1) }
    @JvmStatic fun copy_V_LL_3(a0: Any?, a1: Any?) { guardInline(1003) }

    @JvmStatic fun hook_V_LL_4(a0: Any?, a1: Any?) { invokeSlot("V_LL_4", a0, a1) }
    @JvmStatic fun bak_V_LL_4(a0: Any?, a1: Any?) { if (guardInline(104) != Long.MIN_VALUE) copy_V_LL_4(a0, a1) }
    @JvmStatic fun copy_V_LL_4(a0: Any?, a1: Any?) { guardInline(1004) }

    @JvmStatic fun hook_V_LL_5(a0: Any?, a1: Any?) { invokeSlot("V_LL_5", a0, a1) }
    @JvmStatic fun bak_V_LL_5(a0: Any?, a1: Any?) { if (guardInline(105) != Long.MIN_VALUE) copy_V_LL_5(a0, a1) }
    @JvmStatic fun copy_V_LL_5(a0: Any?, a1: Any?) { guardInline(1005) }

    // ==================== V_LLL (8 slots) ====================
    @JvmStatic fun hook_V_LLL_0(a0: Any?, a1: Any?, a2: Any?) { invokeSlot("V_LLL_0", a0, a1, a2) }
    @JvmStatic fun bak_V_LLL_0(a0: Any?, a1: Any?, a2: Any?) { if (guardInline(200) != Long.MIN_VALUE) copy_V_LLL_0(a0, a1, a2) }
    @JvmStatic fun copy_V_LLL_0(a0: Any?, a1: Any?, a2: Any?) { guardInline(2000) }

    @JvmStatic fun hook_V_LLL_1(a0: Any?, a1: Any?, a2: Any?) { invokeSlot("V_LLL_1", a0, a1, a2) }
    @JvmStatic fun bak_V_LLL_1(a0: Any?, a1: Any?, a2: Any?) { if (guardInline(201) != Long.MIN_VALUE) copy_V_LLL_1(a0, a1, a2) }
    @JvmStatic fun copy_V_LLL_1(a0: Any?, a1: Any?, a2: Any?) { guardInline(2001) }

    @JvmStatic fun hook_V_LLL_2(a0: Any?, a1: Any?, a2: Any?) { invokeSlot("V_LLL_2", a0, a1, a2) }
    @JvmStatic fun bak_V_LLL_2(a0: Any?, a1: Any?, a2: Any?) { if (guardInline(202) != Long.MIN_VALUE) copy_V_LLL_2(a0, a1, a2) }
    @JvmStatic fun copy_V_LLL_2(a0: Any?, a1: Any?, a2: Any?) { guardInline(2002) }

    @JvmStatic fun hook_V_LLL_3(a0: Any?, a1: Any?, a2: Any?) { invokeSlot("V_LLL_3", a0, a1, a2) }
    @JvmStatic fun bak_V_LLL_3(a0: Any?, a1: Any?, a2: Any?) { if (guardInline(203) != Long.MIN_VALUE) copy_V_LLL_3(a0, a1, a2) }
    @JvmStatic fun copy_V_LLL_3(a0: Any?, a1: Any?, a2: Any?) { guardInline(2003) }

    @JvmStatic fun hook_V_LLL_4(a0: Any?, a1: Any?, a2: Any?) { invokeSlot("V_LLL_4", a0, a1, a2) }
    @JvmStatic fun bak_V_LLL_4(a0: Any?, a1: Any?, a2: Any?) { if (guardInline(204) != Long.MIN_VALUE) copy_V_LLL_4(a0, a1, a2) }
    @JvmStatic fun copy_V_LLL_4(a0: Any?, a1: Any?, a2: Any?) { guardInline(2004) }

    @JvmStatic fun hook_V_LLL_5(a0: Any?, a1: Any?, a2: Any?) { invokeSlot("V_LLL_5", a0, a1, a2) }
    @JvmStatic fun bak_V_LLL_5(a0: Any?, a1: Any?, a2: Any?) { if (guardInline(205) != Long.MIN_VALUE) copy_V_LLL_5(a0, a1, a2) }
    @JvmStatic fun copy_V_LLL_5(a0: Any?, a1: Any?, a2: Any?) { guardInline(2005) }

    // ==================== V_LLLL & Z_LLLL ====================
    @JvmStatic fun hook_V_LLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?) { invokeSlot("V_LLLL_0", a0, a1, a2, a3) }
    @JvmStatic fun bak_V_LLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?) { if (guardInline(300) != Long.MIN_VALUE) copy_V_LLLL_0(a0, a1, a2, a3) }
    @JvmStatic fun copy_V_LLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?) { guardInline(3000) }

    @JvmStatic fun hook_V_LLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?) { invokeSlot("V_LLLL_1", a0, a1, a2, a3) }
    @JvmStatic fun bak_V_LLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?) { if (guardInline(301) != Long.MIN_VALUE) copy_V_LLLL_1(a0, a1, a2, a3) }
    @JvmStatic fun copy_V_LLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?) { guardInline(3001) }

    @JvmStatic fun hook_Z_LLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Boolean = invokeSlot("Z_LLLL_0", a0, a1, a2, a3) as? Boolean ?: false
    @JvmStatic fun bak_Z_LLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Boolean = if (guardInline(310) != Long.MIN_VALUE) copy_Z_LLLL_0(a0, a1, a2, a3) else false
    @JvmStatic fun copy_Z_LLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Boolean { guardInline(3100); return false }

    @JvmStatic fun hook_Z_LLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Boolean = invokeSlot("Z_LLLL_1", a0, a1, a2, a3) as? Boolean ?: false
    @JvmStatic fun bak_Z_LLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Boolean = if (guardInline(311) != Long.MIN_VALUE) copy_Z_LLLL_1(a0, a1, a2, a3) else false
    @JvmStatic fun copy_Z_LLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Boolean { guardInline(3101); return false }

    // ==================== V_LLLLL, V_LLLLLL, V_LLLLLLL, L_LLLLLLL ====================
    @JvmStatic fun hook_V_LLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?) { invokeSlot("V_LLLLL_0", a0, a1, a2, a3, a4) }
    @JvmStatic fun bak_V_LLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?) { if (guardInline(400) != Long.MIN_VALUE) copy_V_LLLLL_0(a0, a1, a2, a3, a4) }
    @JvmStatic fun copy_V_LLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?) { guardInline(4000) }

    @JvmStatic fun hook_V_LLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?) { invokeSlot("V_LLLLL_1", a0, a1, a2, a3, a4) }
    @JvmStatic fun bak_V_LLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?) { if (guardInline(401) != Long.MIN_VALUE) copy_V_LLLLL_1(a0, a1, a2, a3, a4) }
    @JvmStatic fun copy_V_LLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?) { guardInline(4001) }

    @JvmStatic fun hook_V_LLLLL_2(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?) { invokeSlot("V_LLLLL_2", a0, a1, a2, a3, a4) }
    @JvmStatic fun bak_V_LLLLL_2(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?) { if (guardInline(402) != Long.MIN_VALUE) copy_V_LLLLL_2(a0, a1, a2, a3, a4) }
    @JvmStatic fun copy_V_LLLLL_2(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?) { guardInline(4002) }

    @JvmStatic fun hook_V_LLLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?) { invokeSlot("V_LLLLLL_0", a0, a1, a2, a3, a4, a5) }
    @JvmStatic fun bak_V_LLLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?) { if (guardInline(410) != Long.MIN_VALUE) copy_V_LLLLLL_0(a0, a1, a2, a3, a4, a5) }
    @JvmStatic fun copy_V_LLLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?) { guardInline(4100) }

    @JvmStatic fun hook_V_LLLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?) { invokeSlot("V_LLLLLL_1", a0, a1, a2, a3, a4, a5) }
    @JvmStatic fun bak_V_LLLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?) { if (guardInline(411) != Long.MIN_VALUE) copy_V_LLLLLL_1(a0, a1, a2, a3, a4, a5) }
    @JvmStatic fun copy_V_LLLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?) { guardInline(4101) }

    @JvmStatic fun hook_V_LLLLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?, a6: Any?) { invokeSlot("V_LLLLLLL_0", a0, a1, a2, a3, a4, a5, a6) }
    @JvmStatic fun bak_V_LLLLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?, a6: Any?) { if (guardInline(420) != Long.MIN_VALUE) copy_V_LLLLLLL_0(a0, a1, a2, a3, a4, a5, a6) }
    @JvmStatic fun copy_V_LLLLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?, a6: Any?) { guardInline(4200) }

    @JvmStatic fun hook_V_LLLLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?, a6: Any?) { invokeSlot("V_LLLLLLL_1", a0, a1, a2, a3, a4, a5, a6) }
    @JvmStatic fun bak_V_LLLLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?, a6: Any?) { if (guardInline(421) != Long.MIN_VALUE) copy_V_LLLLLLL_1(a0, a1, a2, a3, a4, a5, a6) }
    @JvmStatic fun copy_V_LLLLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?, a6: Any?) { guardInline(4201) }

    @JvmStatic fun hook_L_LLLLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?, a6: Any?): Any? = invokeSlot("L_LLLLLLL_0", a0, a1, a2, a3, a4, a5, a6)
    @JvmStatic fun bak_L_LLLLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?, a6: Any?): Any? = if (guardInline(430) != Long.MIN_VALUE) copy_L_LLLLLLL_0(a0, a1, a2, a3, a4, a5, a6) else null
    @JvmStatic fun copy_L_LLLLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?, a5: Any?, a6: Any?): Any? { guardInline(4300); return null }

    // ==================== V_LLLIL (LocationProviderManager.registerLocationRequest) ====================
    @JvmStatic fun hook_V_LLLIL_0(a0: Any?, a1: Any?, a2: Any?, a3: Int, a4: Any?) { invokeSlot("V_LLLIL_0", a0, a1, a2, a3, a4) }
    @JvmStatic fun bak_V_LLLIL_0(a0: Any?, a1: Any?, a2: Any?, a3: Int, a4: Any?) { if (guardInline(500) != Long.MIN_VALUE) copy_V_LLLIL_0(a0, a1, a2, a3, a4) }
    @JvmStatic fun copy_V_LLLIL_0(a0: Any?, a1: Any?, a2: Any?, a3: Int, a4: Any?) { guardInline(5000) }

    @JvmStatic fun hook_V_LLLIL_1(a0: Any?, a1: Any?, a2: Any?, a3: Int, a4: Any?) { invokeSlot("V_LLLIL_1", a0, a1, a2, a3, a4) }
    @JvmStatic fun bak_V_LLLIL_1(a0: Any?, a1: Any?, a2: Any?, a3: Int, a4: Any?) { if (guardInline(501) != Long.MIN_VALUE) copy_V_LLLIL_1(a0, a1, a2, a3, a4) }
    @JvmStatic fun copy_V_LLLIL_1(a0: Any?, a1: Any?, a2: Any?, a3: Int, a4: Any?) { guardInline(5001) }

    // ==================== L_L, L_LL, L_LLL, L_LLLL, L_LLLLL ====================
    @JvmStatic fun hook_L_L_0(a0: Any?): Any? = invokeSlot("L_L_0", a0)
    @JvmStatic fun bak_L_L_0(a0: Any?): Any? = if (guardInline(600) != Long.MIN_VALUE) copy_L_L_0(a0) else null
    @JvmStatic fun copy_L_L_0(a0: Any?): Any? { guardInline(6000); return null }

    @JvmStatic fun hook_L_L_1(a0: Any?): Any? = invokeSlot("L_L_1", a0)
    @JvmStatic fun bak_L_L_1(a0: Any?): Any? = if (guardInline(601) != Long.MIN_VALUE) copy_L_L_1(a0) else null
    @JvmStatic fun copy_L_L_1(a0: Any?): Any? { guardInline(6001); return null }

    @JvmStatic fun hook_L_LL_0(a0: Any?, a1: Any?): Any? = invokeSlot("L_LL_0", a0, a1)
    @JvmStatic fun bak_L_LL_0(a0: Any?, a1: Any?): Any? = if (guardInline(610) != Long.MIN_VALUE) copy_L_LL_0(a0, a1) else null
    @JvmStatic fun copy_L_LL_0(a0: Any?, a1: Any?): Any? { guardInline(6100); return null }

    @JvmStatic fun hook_L_LL_1(a0: Any?, a1: Any?): Any? = invokeSlot("L_LL_1", a0, a1)
    @JvmStatic fun bak_L_LL_1(a0: Any?, a1: Any?): Any? = if (guardInline(611) != Long.MIN_VALUE) copy_L_LL_1(a0, a1) else null
    @JvmStatic fun copy_L_LL_1(a0: Any?, a1: Any?): Any? { guardInline(6101); return null }

    @JvmStatic fun hook_L_LLL_0(a0: Any?, a1: Any?, a2: Any?): Any? = invokeSlot("L_LLL_0", a0, a1, a2)
    @JvmStatic fun bak_L_LLL_0(a0: Any?, a1: Any?, a2: Any?): Any? = if (guardInline(620) != Long.MIN_VALUE) copy_L_LLL_0(a0, a1, a2) else null
    @JvmStatic fun copy_L_LLL_0(a0: Any?, a1: Any?, a2: Any?): Any? { guardInline(6200); return null }

    @JvmStatic fun hook_L_LLL_1(a0: Any?, a1: Any?, a2: Any?): Any? = invokeSlot("L_LLL_1", a0, a1, a2)
    @JvmStatic fun bak_L_LLL_1(a0: Any?, a1: Any?, a2: Any?): Any? = if (guardInline(621) != Long.MIN_VALUE) copy_L_LLL_1(a0, a1, a2) else null
    @JvmStatic fun copy_L_LLL_1(a0: Any?, a1: Any?, a2: Any?): Any? { guardInline(6201); return null }

    @JvmStatic fun hook_L_LLL_2(a0: Any?, a1: Any?, a2: Any?): Any? = invokeSlot("L_LLL_2", a0, a1, a2)
    @JvmStatic fun bak_L_LLL_2(a0: Any?, a1: Any?, a2: Any?): Any? = if (guardInline(622) != Long.MIN_VALUE) copy_L_LLL_2(a0, a1, a2) else null
    @JvmStatic fun copy_L_LLL_2(a0: Any?, a1: Any?, a2: Any?): Any? { guardInline(6202); return null }

    @JvmStatic fun hook_L_LLL_3(a0: Any?, a1: Any?, a2: Any?): Any? = invokeSlot("L_LLL_3", a0, a1, a2)
    @JvmStatic fun bak_L_LLL_3(a0: Any?, a1: Any?, a2: Any?): Any? = if (guardInline(623) != Long.MIN_VALUE) copy_L_LLL_3(a0, a1, a2) else null
    @JvmStatic fun copy_L_LLL_3(a0: Any?, a1: Any?, a2: Any?): Any? { guardInline(6203); return null }

    @JvmStatic fun hook_L_LLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Any? = invokeSlot("L_LLLL_0", a0, a1, a2, a3)
    @JvmStatic fun bak_L_LLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Any? = if (guardInline(630) != Long.MIN_VALUE) copy_L_LLLL_0(a0, a1, a2, a3) else null
    @JvmStatic fun copy_L_LLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Any? { guardInline(6300); return null }

    @JvmStatic fun hook_L_LLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Any? = invokeSlot("L_LLLL_1", a0, a1, a2, a3)
    @JvmStatic fun bak_L_LLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Any? = if (guardInline(631) != Long.MIN_VALUE) copy_L_LLLL_1(a0, a1, a2, a3) else null
    @JvmStatic fun copy_L_LLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?): Any? { guardInline(6301); return null }

    @JvmStatic fun hook_L_LLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?): Any? = invokeSlot("L_LLLLL_0", a0, a1, a2, a3, a4)
    @JvmStatic fun bak_L_LLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?): Any? = if (guardInline(640) != Long.MIN_VALUE) copy_L_LLLLL_0(a0, a1, a2, a3, a4) else null
    @JvmStatic fun copy_L_LLLLL_0(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?): Any? { guardInline(6400); return null }

    @JvmStatic fun hook_L_LLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?): Any? = invokeSlot("L_LLLLL_1", a0, a1, a2, a3, a4)
    @JvmStatic fun bak_L_LLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?): Any? = if (guardInline(641) != Long.MIN_VALUE) copy_L_LLLLL_1(a0, a1, a2, a3, a4) else null
    @JvmStatic fun copy_L_LLLLL_1(a0: Any?, a1: Any?, a2: Any?, a3: Any?, a4: Any?): Any? { guardInline(6401); return null }

    // ==================== Z_L, Z_LL, Z_LI, Z_LLI ====================
    @JvmStatic fun hook_Z_L_0(a0: Any?): Boolean = invokeSlot("Z_L_0", a0) as? Boolean ?: false
    @JvmStatic fun bak_Z_L_0(a0: Any?): Boolean = if (guardInline(700) != Long.MIN_VALUE) copy_Z_L_0(a0) else false
    @JvmStatic fun copy_Z_L_0(a0: Any?): Boolean { guardInline(7000); return false }

    @JvmStatic fun hook_Z_LL_0(a0: Any?, a1: Any?): Boolean = invokeSlot("Z_LL_0", a0, a1) as? Boolean ?: false
    @JvmStatic fun bak_Z_LL_0(a0: Any?, a1: Any?): Boolean = if (guardInline(710) != Long.MIN_VALUE) copy_Z_LL_0(a0, a1) else false
    @JvmStatic fun copy_Z_LL_0(a0: Any?, a1: Any?): Boolean { guardInline(7100); return false }

    @JvmStatic fun hook_Z_LL_1(a0: Any?, a1: Any?): Boolean = invokeSlot("Z_LL_1", a0, a1) as? Boolean ?: false
    @JvmStatic fun bak_Z_LL_1(a0: Any?, a1: Any?): Boolean = if (guardInline(711) != Long.MIN_VALUE) copy_Z_LL_1(a0, a1) else false
    @JvmStatic fun copy_Z_LL_1(a0: Any?, a1: Any?): Boolean { guardInline(7101); return false }

    @JvmStatic fun hook_Z_LI_0(a0: Any?, a1: Int): Boolean = invokeSlot("Z_LI_0", a0, a1) as? Boolean ?: false
    @JvmStatic fun bak_Z_LI_0(a0: Any?, a1: Int): Boolean = if (guardInline(720) != Long.MIN_VALUE) copy_Z_LI_0(a0, a1) else false
    @JvmStatic fun copy_Z_LI_0(a0: Any?, a1: Int): Boolean { guardInline(7200); return false }

    @JvmStatic fun hook_Z_LLI_0(a0: Any?, a1: Any?, a2: Int): Boolean = invokeSlot("Z_LLI_0", a0, a1, a2) as? Boolean ?: false
    @JvmStatic fun bak_Z_LLI_0(a0: Any?, a1: Any?, a2: Int): Boolean = if (guardInline(730) != Long.MIN_VALUE) copy_Z_LLI_0(a0, a1, a2) else false
    @JvmStatic fun copy_Z_LLI_0(a0: Any?, a1: Any?, a2: Int): Boolean { guardInline(7300); return false }

    @JvmStatic fun hook_Z_LLI_1(a0: Any?, a1: Any?, a2: Int): Boolean = invokeSlot("Z_LLI_1", a0, a1, a2) as? Boolean ?: false
    @JvmStatic fun bak_Z_LLI_1(a0: Any?, a1: Any?, a2: Int): Boolean = if (guardInline(731) != Long.MIN_VALUE) copy_Z_LLI_1(a0, a1, a2) else false
    @JvmStatic fun copy_Z_LLI_1(a0: Any?, a1: Any?, a2: Int): Boolean { guardInline(7301); return false }

    // ==================== I_LIIL, I_LIILLZ (AppOpsService.checkOperation) ====================
    @JvmStatic fun hook_I_LIIL_0(a0: Any?, a1: Int, a2: Int, a3: Any?): Int = invokeSlot("I_LIIL_0", a0, a1, a2, a3) as? Int ?: 0
    @JvmStatic fun bak_I_LIIL_0(a0: Any?, a1: Int, a2: Int, a3: Any?): Int = if (guardInline(800) != Long.MIN_VALUE) copy_I_LIIL_0(a0, a1, a2, a3) else 0
    @JvmStatic fun copy_I_LIIL_0(a0: Any?, a1: Int, a2: Int, a3: Any?): Int { guardInline(8000); return 0 }

    @JvmStatic fun hook_I_LIILLZ_0(a0: Any?, a1: Int, a2: Int, a3: Any?, a4: Any?, a5: Boolean): Int =
        invokeSlot("I_LIILLZ_0", a0, a1, a2, a3, a4, a5) as? Int ?: 0
    @JvmStatic fun bak_I_LIILLZ_0(a0: Any?, a1: Int, a2: Int, a3: Any?, a4: Any?, a5: Boolean): Int =
        if (guardInline(810) != Long.MIN_VALUE) copy_I_LIILLZ_0(a0, a1, a2, a3, a4, a5) else 0
    @JvmStatic fun copy_I_LIILLZ_0(a0: Any?, a1: Int, a2: Int, a3: Any?, a4: Any?, a5: Boolean): Int { guardInline(8100); return 0 }

    // ==================== PendingIntent.send overloads ====================
    @JvmStatic fun hook_V_LLILL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?) { invokeSlot("V_LLILL_0", a0, a1, a2, a3, a4) }
    @JvmStatic fun bak_V_LLILL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?) { if (guardInline(900) != Long.MIN_VALUE) copy_V_LLILL_0(a0, a1, a2, a3, a4) }
    @JvmStatic fun copy_V_LLILL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?) { guardInline(9000) }

    @JvmStatic fun hook_V_LLILLL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?, a5: Any?) { invokeSlot("V_LLILLL_0", a0, a1, a2, a3, a4, a5) }
    @JvmStatic fun bak_V_LLILLL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?, a5: Any?) { if (guardInline(910) != Long.MIN_VALUE) copy_V_LLILLL_0(a0, a1, a2, a3, a4, a5) }
    @JvmStatic fun copy_V_LLILLL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?, a5: Any?) { guardInline(9100) }

    @JvmStatic fun hook_V_LLILLLL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?, a5: Any?, a6: Any?) { invokeSlot("V_LLILLLL_0", a0, a1, a2, a3, a4, a5, a6) }
    @JvmStatic fun bak_V_LLILLLL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?, a5: Any?, a6: Any?) { if (guardInline(920) != Long.MIN_VALUE) copy_V_LLILLLL_0(a0, a1, a2, a3, a4, a5, a6) }
    @JvmStatic fun copy_V_LLILLLL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?, a5: Any?, a6: Any?) { guardInline(9200) }

    @JvmStatic fun hook_V_LLILLLLL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?, a5: Any?, a6: Any?, a7: Any?) { invokeSlot("V_LLILLLLL_0", a0, a1, a2, a3, a4, a5, a6, a7) }
    @JvmStatic fun bak_V_LLILLLLL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?, a5: Any?, a6: Any?, a7: Any?) { if (guardInline(930) != Long.MIN_VALUE) copy_V_LLILLLLL_0(a0, a1, a2, a3, a4, a5, a6, a7) }
    @JvmStatic fun copy_V_LLILLLLL_0(a0: Any?, a1: Any?, a2: Int, a3: Any?, a4: Any?, a5: Any?, a6: Any?, a7: Any?) { guardInline(9300) }
}
