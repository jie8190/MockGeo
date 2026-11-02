package com.mockgeo.module

import android.app.AppOpsManager
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** All hooks preserve framework permission checks and declared return types. */
internal object EvidenceHooks {
    fun install(api: XposedInterface, loader: ClassLoader): Int {
        var count = 0
        val appOps = find(loader, "com.android.server.appop.AppOpsService")
            ?: find(loader, "com.android.server.AppOpsService")
        // noteOperation is an operation, not an integer query on modern Android.
        // Leave its SyncNotedAppOp and permission/accounting behavior intact.
        appOps?.declaredMethods?.filter {
            it.name == "checkOperation" && it.returnType == Int::class.javaPrimitiveType &&
                it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
        }?.forEach { method ->
            count += hook(api, method) { args, result ->
                val cfg = ModuleConfig.snapshot()
                if (IdentityGuard.shouldHideMock(cfg) && args.firstOrNull() == mockOp &&
                    ProcessHookPolicy.isApplicationUid(android.os.Binder.getCallingUid()) && !Exemptions.isCallerExempt())
                    AppOpsManager.MODE_IGNORED else result
            }
        }
        // Wi-Fi is modular on newer Android. A missing service loader is reported;
        // scoped WifiManager hooks below provide the public API path.
        val wifi = find(loader, "com.android.server.wifi.WifiServiceImpl")
        if (wifi == null) ModuleLog.d("system Wi-Fi class unavailable; scoped API required")
        wifi?.declaredMethods?.filter { it.name in setOf("getScanResults", "getConnectionInfo") }?.forEach { m ->
            count += hook(api, m) { _, original ->
                val cfg = ModuleConfig.snapshot()
                if (!IdentityGuard.shouldHideMock(cfg) ||
                    !ProcessHookPolicy.isApplicationUid(android.os.Binder.getCallingUid()) || Exemptions.isCallerExempt()) original
                else when (m.name) {
                    "getScanResults" -> ResultAdapter.list(m.returnType, original, fakeScanResults(cfg.lat, cfg.lng))
                    else -> fakeWifiInfo(cfg.lat, cfg.lng) ?: original
                }
            }
        }
        ModuleLog.d("evidence system hooks installed: $count")
        return count
    }

    fun installClient(api: XposedInterface, loader: ClassLoader): Int {
        var count = 0
        for (name in listOf("android.net.wifi.WifiManager", "android.telephony.TelephonyManager")) {
            val cls = find(loader, name) ?: continue
            cls.declaredMethods.filter {
                it.name in setOf("getScanResults", "getConnectionInfo", "getAllCellInfo", "getCellLocation", "getBlurryCellInfos", "getBlurryCellLocation")
            }.forEach { m ->
                count += hook(api, m) { _, original ->
                    val cfg = AppScopeConfig.snapshot(AppScopeHooks.appContext())
                    if (!AppScopeHooks.isActive(cfg) || !cfg.hideMock) original
                    else when (m.name) {
                        "getScanResults" -> ResultAdapter.list(m.returnType, original, fakeScanResults(cfg.lat, cfg.lng))
                        "getConnectionInfo" -> fakeWifiInfo(cfg.lat, cfg.lng) ?: original
                        "getAllCellInfo", "getBlurryCellInfos" -> ResultAdapter.list(m.returnType, original, fakeCellInfo(cfg.lat, cfg.lng))
                        "getCellLocation", "getBlurryCellLocation" -> fakeCellLocation(cfg.lat, cfg.lng) ?: original
                        else -> original
                    }
                }
            }
        }
        ModuleLog.d("evidence client hooks installed: $count")
        return count
    }

    private fun hook(api: XposedInterface, m: Method, replacement: (List<Any?>, Any?) -> Any?): Int = runCatching {
        api.hook(m).intercept(XposedInterface.Hooker { chain ->
            val original = chain.proceed() // exactly once; permission exceptions propagate unchanged
            runCatching {
                val changed = replacement(chain.args, original)
                ResultAdapter.checked(m.returnType, changed, original)
            }.getOrElse { ModuleLog.w("evidence ${m.name} fallback", it); original }
        })
        1
    }.getOrElse { ModuleLog.w("evidence ${m.name} install failed", it); 0 }

    private val mockOp: Int? by lazy {
        runCatching { AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
            .invoke(null, "android:mock_location") as Int }.getOrNull()
    }

    internal fun fakeScanResults(lat: Double, lng: Double): List<Any>? = runCatching {
        val cls = Class.forName("android.net.wifi.ScanResult")
        val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
        FakeEvidence.ssids().mapIndexed { index, ssid ->
            ctor.newInstance().also {
                cls.getField("SSID").set(it, ssid)
                cls.getField("BSSID").set(it, FakeEvidence.bssid(lat, lng, index))
                cls.getField("level").setInt(it, -55 - index * 4)
                cls.getField("frequency").setInt(it, if (index % 2 == 0) 2437 else 5180)
                cls.getField("capabilities").set(it, "[WPA2-PSK-CCMP][ESS]")
                cls.getField("timestamp").setLong(it, android.os.SystemClock.elapsedRealtime() * 1000)
            }
        }
    }.getOrNull()

    internal fun fakeWifiInfo(lat: Double, lng: Double): Any? = runCatching {
        val cls = Class.forName("android.net.wifi.WifiInfo")
        val info = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val ssidCls = Class.forName("android.net.wifi.WifiSsid")
        val ssid = ssidCls.getMethod("createFromAsciiEncoded", String::class.java).invoke(null, FakeEvidence.ssids().first())
        cls.getMethod("setSSID", ssidCls).invoke(info, ssid)
        cls.getMethod("setBSSID", String::class.java).invoke(info, FakeEvidence.bssid(lat, lng, 0))
        cls.getMethod("setRssi", Int::class.javaPrimitiveType).invoke(info, -58)
        cls.getMethod("setLinkSpeed", Int::class.javaPrimitiveType).invoke(info, 72)
        cls.getMethod("setFrequency", Int::class.javaPrimitiveType).invoke(info, 2437)
        info
    }.getOrNull()

    internal fun fakeCellInfo(lat: Double, lng: Double): List<Any>? = runCatching {
        val id = FakeEvidence.cellIdentity(lat, lng)
        val cls = Class.forName("android.telephony.CellIdentityLte")
        val intType = Int::class.javaPrimitiveType
        val identity = cls.getDeclaredConstructor(intType, intType, intType, intType, intType)
            .newInstance(id.mcc, id.mnc, id.ci, id.pci, id.tac)
        val infoCls = Class.forName("android.telephony.CellInfoLte")
        val info = infoCls.getDeclaredConstructor().newInstance()
        infoCls.getMethod("setCellIdentity", cls).invoke(info, identity)
        infoCls.getMethod("setRegistered", Boolean::class.javaPrimitiveType).invoke(info, true)
        infoCls.getMethod("setTimeStamp", Long::class.javaPrimitiveType).invoke(info, android.os.SystemClock.elapsedRealtimeNanos())
        val strengthCls = Class.forName("android.telephony.CellSignalStrengthLte")
        // Some OEM numeric constructors call unavailable vendor level calculators.
        // Fill the fixture's primitive fields without invoking those services.
        val strength = strengthCls.getDeclaredConstructor().newInstance()
        for ((field, value) in mapOf("mRssi" to -55, "mRsrp" to -90, "mRsrq" to -12,
            "mRssnr" to 18, "mCqi" to 10, "mTimingAdvance" to 1, "mLevel" to 3)) {
            strengthCls.getDeclaredField(field).apply { isAccessible = true }.setInt(strength, value)
        }
        infoCls.getMethod("setCellSignalStrength", strengthCls).invoke(info, strength)
        listOf(info)
    }.getOrNull()

    internal fun fakeCellLocation(lat: Double, lng: Double): Any? = runCatching {
        val id = FakeEvidence.cellIdentity(lat, lng)
        val cls = Class.forName("android.telephony.gsm.GsmCellLocation")
        cls.getDeclaredConstructor().newInstance().also {
            cls.getMethod("setLacAndCid", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(it, id.tac, id.ci)
        }
    }.getOrNull()

    private fun find(loader: ClassLoader, name: String): Class<*>? =
        runCatching { Class.forName(name, false, loader) }.getOrNull()
}

/** Supports both AOSP lists and vendor/mainline ParceledListSlice wrappers. */
internal object ResultAdapter {
    fun checked(type: Class<*>, value: Any?, fallback: Any?): Any? = when {
        value == null -> fallback
        type == Int::class.javaPrimitiveType && value is Int -> value
        type.isInstance(value) -> value
        else -> fallback
    }
    fun list(type: Class<*>, original: Any?, entries: List<Any>?): Any? {
        if (entries == null) return original
        if (type.isInstance(entries)) return entries
        return runCatching {
            val ctor = type.getDeclaredConstructor(List::class.java).apply { isAccessible = true }
            checked(type, ctor.newInstance(entries), original)
        }.getOrDefault(original)
    }
}
