package com.mockgeo.module

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 模块状态页（纯代码构建，无资源依赖，保持模块 APK 精简）。
 * 只做一件事：告诉用户模块装好了、下一步去哪。
 */
class StatusActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = "MockGeo 模块"
            textSize = 22f
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val body = TextView(this).apply {
            text = """
                模块已安装（v${StatusWriter.MODULE_VERSION}）。

                启用步骤：
                需使用支持 libxposed API 102 的 LSPosed 框架。
                1. 在 LSPosed 中启用本模块；
                2. 作用域勾选「系统框架」（system_server）；
                3. 手动重启手机使 hook 生效；
                4. 打开 MockGeo 主应用，引擎选择「LSPosed 模块」。

                说明：
                · 本模块把系统定位管线的输出替换为 MockGeo 下发的模拟位置；
                · 标准格式保留模拟标记；兼容性测试格式由主应用设置；
                · 应用内 GNSS/NMEA 等功能需将测试应用加入 LSPosed 作用域；
                · 停止模拟 = 所有 hook 直通真实数据；
                · system_server 重启后模拟自动复位为关闭（fail-safe）。

                诊断：logcat 过滤 tag "${ControlPlaneTag}"。
            """.trimIndent()
            textSize = 15f
            setLineSpacing(0f, 1.35f)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad * 2)
            setBackgroundColor(Color.WHITE)
            addView(title)
            addView(body, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = pad })
            gravity = Gravity.TOP
        }
        setContentView(root)
    }

    companion object {
        private const val ControlPlaneTag = "MockGeoModule"
    }
}
