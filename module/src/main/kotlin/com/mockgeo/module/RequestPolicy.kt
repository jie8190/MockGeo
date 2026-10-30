package com.mockgeo.module

/** Bounds on synthetic delivery, read from the actual request rather than API names. */
internal data class RequestPolicy(
    val maxUpdates: Int = Int.MAX_VALUE,
    val durationMs: Long = Long.MAX_VALUE,
    val intervalMs: Long = 0,
    val minDistanceM: Float = 0f,
) {
    companion object {
        fun from(args: List<Any?>, legacySingle: Boolean = false): RequestPolicy {
            val request = args.firstOrNull { it?.javaClass?.name == "android.location.LocationRequest" }
            fun number(vararg names: String): Number? = request?.let { target -> names.firstNotNullOfOrNull { name ->
                runCatching { target.javaClass.getMethod(name).invoke(target) as? Number }.getOrNull()
            } }
            return RequestPolicy(
                if (legacySingle) 1 else (number("getMaxUpdates", "getNumUpdates")?.toInt() ?: Int.MAX_VALUE).coerceAtLeast(1),
                (number("getDurationMillis")?.toLong() ?: Long.MAX_VALUE).coerceAtLeast(0),
                (number("getMinUpdateIntervalMillis", "getFastestInterval", "getIntervalMillis")?.toLong() ?: 0).coerceAtLeast(0),
                (number("getMinUpdateDistanceMeters", "getSmallestDisplacement")?.toFloat() ?: 0f).coerceAtLeast(0f),
            )
        }
    }
}

internal class DeliveryBudget(val policy: RequestPolicy, private val createdAt: Long) {
    private var count = 0
    private var lastAt: Long? = null
    fun exhausted(now: Long) = count >= policy.maxUpdates || now - createdAt >= policy.durationMs
    fun ready(now: Long, distanceM: Float?): Boolean = !exhausted(now) &&
        (lastAt == null || now - lastAt!! >= policy.intervalMs) &&
        (distanceM == null || distanceM >= policy.minDistanceM)
    fun delivered(now: Long) { count++; lastAt = now }
}
