package com.mockgeo.app.checks

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mockgeo.core.model.CheckResult

/**
 * 启动自检（docs/08 §9）：启动时跑一次 + 每次点"开始"前再跑一次。
 * 失败项给出可操作引导（actionKey → UI 跳转对应设置页）。
 */
object PermissionChecker {

    fun run(context: Context, engineRequiresMockAppOp: Boolean): List<CheckResult> {
        val out = mutableListOf<CheckResult>()

        // 1. 定位权限
        val fine = has(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = has(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        out += CheckResult(
            name = "定位权限",
            ok = fine || coarse,
            message = if (fine || coarse) null else "需要至少粗略定位权限",
            actionKey = "PERMISSION",
        )

        // 2. 后台定位（29+）
        if (Build.VERSION.SDK_INT >= 29) {
            val bg = has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            out += CheckResult(
                name = "后台定位",
                ok = bg,
                message = if (bg) null else "选择\"始终允许\"，否则后台期间模拟可能中断",
                actionKey = "APP_DETAILS",
            )
        }

        // 3. 通知权限（33+）
        if (Build.VERSION.SDK_INT >= 33) {
            val notif = has(context, Manifest.permission.POST_NOTIFICATIONS)
            out += CheckResult(
                name = "通知权限",
                ok = notif,
                message = if (notif) null else "需要通知权限以常驻显示模拟状态",
                actionKey = "PERMISSION",
            )
        }

        // 4. 悬浮窗（可选能力，失败只降级不阻塞）
        val overlay = Settings.canDrawOverlays(context)
        out += CheckResult(
            name = "悬浮窗",
            ok = overlay,
            message = if (overlay) null else "未授予悬浮窗权限，摇杆/控制条不可用（不影响核心模拟）",
            actionKey = "OVERLAY",
        )

        // 5. mock AppOps（仅 provider 引擎需要）
        if (engineRequiresMockAppOp) {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val allowed = ops != null && context.packageName != null && run {
                val uid = context.applicationInfo.uid
                if (Build.VERSION.SDK_INT >= 29) {
                    ops.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        uid,
                        context.packageName,
                    ) == AppOpsManager.MODE_ALLOWED
                } else {
                    // 字符串版 checkOpNoThrow 自 API 19 起公开
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        uid,
                        context.packageName,
                    ) == AppOpsManager.MODE_ALLOWED
                }
            }
            out += CheckResult(
                name = "模拟位置 AppOps",
                ok = allowed,
                message = if (allowed) null else "开发者选项 → 选择模拟位置信息应用 → 本应用",
                actionKey = "DEV_OPTIONS",
            )
        }

        // Module installation and version are evaluated together with the live engine handshake.

        return out
    }

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

}
