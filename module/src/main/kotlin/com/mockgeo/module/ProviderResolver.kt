package com.mockgeo.module

/**
 * 从系统方法的实参里解析"这次请求要的是哪个 provider"。
 *
 * 为什么需要它：LMS 的方法签名在 30+ 变成 `requestLocationUpdates(ProviderRequest, ...)`，
 * provider 不再是 String 实参而是包在 `LocationRequest` / `ProviderRequest` 里；
 * 0.3.1 之前一律回落 "gps"，导致 network/fused 订阅被标成 gps（旧基线已观测到）。
 *
 * 只做只读反射，不依赖 android.jar 常量，便于 JVM 单测。
 */
internal object ProviderResolver {

    const val GPS = "gps"
    const val NETWORK = "network"
    const val PASSIVE = "passive"
    const val FUSED = "fused"

    val known: Set<String> = setOf(GPS, NETWORK, PASSIVE, FUSED)

    /**
     * 按优先级解析：String 实参 → LocationRequest → ProviderRequest。
     * 解析不到返回 null（交给 [fallbackFor]）。
     */
    fun fromArgs(args: Iterable<Any?>): String? {
        for (arg in args) {
            when {
                arg is String && arg in known -> return arg
                arg != null && isLocationRequest(arg) -> providerOf(arg)?.let { return it }
                arg != null && isProviderRequest(arg) -> providerOf(arg)?.let { return it }
            }
        }
        return null
    }

    /** 解析失败时的保守回落：方法名里带 provider 名就用它，否则 gps。 */
    fun fallbackFor(methodName: String, args: Iterable<Any?>): String =
        fromArgs(args)
            ?: known.firstOrNull { methodName.contains(it, ignoreCase = true) }
            ?: GPS

    fun normalize(provider: String?): String? =
        provider?.trim()?.lowercase()?.takeIf { it in known }

    private fun isLocationRequest(o: Any) = o.javaClass.name == "android.location.LocationRequest"

    private fun isProviderRequest(o: Any) =
        o.javaClass.name.endsWith("ProviderRequest") || o.javaClass.name.contains("ProviderRequest$")

    private fun providerOf(o: Any): String? = runCatching {
        val getter = o.javaClass.methods.firstOrNull {
            it.name == "getProvider" && it.parameterCount == 0
        } ?: return@runCatching null
        normalize(getter.invoke(o) as? String)
    }.getOrNull()
}
