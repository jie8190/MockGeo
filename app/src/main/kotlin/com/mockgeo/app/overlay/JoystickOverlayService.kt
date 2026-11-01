package com.mockgeo.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mockgeo.app.MainActivity
import com.mockgeo.app.R
import com.mockgeo.app.sim.SimController
import com.mockgeo.app.sim.SimulationService
import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.JoystickVector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale

/** Floating controls share the actual session state, including stops from the notification. */
class JoystickOverlayService : Service() {
    private val controller: SimController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { getSharedPreferences("floating_controls", MODE_PRIVATE) }
    private var windowManager: WindowManager? = null
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var joystickView: JoystickView? = null
    private var statusView: TextView? = null
    private var pauseButton: Button? = null
    private var stopButton: Button? = null
    private var attached = false
    private var initialized = false
    private var hasActiveSession = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            check(Settings.canDrawOverlays(this)) { "请先在设置中允许显示悬浮窗" }
            createPanel()
            initialized = true
            observeSession()
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Cannot open floating controls", error)
            showMessage("悬浮控制条未能打开：${error.message ?: "请检查悬浮窗权限"}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_CLOSE || !initialized) stopSelf()
        return START_NOT_STICKY
    }

    private fun createPanel() {
        val manager = getSystemService(WindowManager::class.java)
        windowManager = manager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(247, 252, 250))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(212, 229, 221))
            }
            elevation = dp(8).toFloat()
        }
        panel = root
        val layout = WindowManager.LayoutParams(
            dp(248), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = preferences.getInt("x", dp(16))
            y = preferences.getInt("y", dp(100))
        }
        params = layout
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val handle = TextView(this).apply {
            text = "↕  悬浮控制"
            textSize = 14f
            setTextColor(Color.rgb(19, 60, 45))
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "拖动悬浮控制条"
            setOnTouchListener(DragHandle())
        }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(44), 1f))
        val close = TextView(this).apply {
            text = "×"
            textSize = 26f
            gravity = Gravity.CENTER
            contentDescription = "关闭悬浮控制条，保持模拟"
            setTextColor(Color.rgb(60, 85, 74))
            setOnClickListener { stopSelf() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)
        statusView = TextView(this).apply {
            text = "等待模拟启动…"
            textSize = 12f
            setTextColor(Color.rgb(78, 102, 90))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        }.also { root.addView(it) }
        joystickView = JoystickView(this) { vector -> controller.setJoystick(vector) }.also {
            root.addView(it, LinearLayout.LayoutParams(dp(144), dp(144)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        pauseButton = actionButton("暂停", Color.rgb(0, 117, 100)) {
            scope.launch {
                try {
                    if (controller.ui.value.paused) SimulationService.resume(this@JoystickOverlayService)
                    else SimulationService.pause(this@JoystickOverlayService)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    showMessage("操作失败：${error.message ?: "请返回应用重试"}")
                }
            }
        }
        stopButton = actionButton("停止", Color.rgb(166, 47, 53)) {
            controller.setJoystick(JoystickVector(0f, 0f))
            scope.launch {
                try {
                    SimulationService.stop(this@JoystickOverlayService)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    showMessage("停止失败：${error.message ?: "请返回应用重试"}")
                }
            }
        }
        actions.addView(pauseButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(stopButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions)
        clampToScreen()
        manager.addView(root, layout)
        attached = true
        root.post { clampToScreen(update = true) }
    }

    private fun actionButton(label: String, color: Int, click: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(color)
        setOnClickListener { click() }
    }

    private fun observeSession() {
        scope.launch {
            controller.ui.collect { ui ->
                val running = ui.engineState is EngineState.Running
                if (running || ui.engineState is EngineState.Starting || ui.engineState is EngineState.Stopping) {
                    hasActiveSession = true
                }
                when (val state = ui.engineState) {
                    EngineState.Idle -> if (hasActiveSession) { stopSelf(); return@collect }
                    is EngineState.Failed -> {
                        showMessage("模拟已结束：${state.detail ?: "请查看应用诊断"}")
                        stopSelf()
                        return@collect
                    }
                    else -> Unit
                }
                pauseButton?.text = if (ui.paused) "继续" else "暂停"
                pauseButton?.isEnabled = running
                stopButton?.isEnabled = running
                val joystick = ui.mode == SimController.Mode.JOYSTICK
                val visibility = if (joystick) View.VISIBLE else View.GONE
                if (joystickView?.visibility != visibility) {
                    joystickView?.visibility = visibility
                    panel?.post { clampToScreen(update = true) }
                }
                joystickView?.isEnabled = joystick && running && !ui.paused
                if (!running || ui.paused) joystickView?.release()
                statusView?.text = when (ui.engineState) {
                    is EngineState.Starting -> "正在启动…"
                    is EngineState.Stopping -> "正在停止…"
                    is EngineState.Running -> {
                        val mode = when (ui.mode) {
                            SimController.Mode.FIXED -> "定点"
                            SimController.Mode.ROUTE -> "路线"
                            SimController.Mode.JOYSTICK -> "摇杆"
                        }
                        if (ui.paused) "$mode · 已暂停"
                        else "$mode · ${String.format(Locale.ROOT, "%.1f", ui.currentSpeedMps * 3.6)} km/h"
                    }
                    else -> "等待模拟启动…"
                }
            }
        }
        // start() can precede the simulation service's initial Idle emission.
        scope.launch {
            delay(8_000)
            if (!hasActiveSession && controller.ui.value.engineState == EngineState.Idle) {
                showMessage("请先开始位置模拟")
                stopSelf()
            }
        }
    }

    private inner class DragHandle : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var panelX = 0
        private var panelY = 0
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val layout = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    panelX = layout.x; panelY = layout.y
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = panelX + (event.rawX - startX).toInt()
                    layout.y = panelY + (event.rawY - startY).toInt()
                    clampToScreen(update = true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePosition()
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                }
            }
            return true
        }
    }

    private fun clampToScreen(update: Boolean = false) {
        val layout = params ?: return
        val manager = windowManager ?: return
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            width = metrics.bounds.width() - insets.left - insets.right
            height = metrics.bounds.height() - insets.top - insets.bottom
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        layout.width = minOf(dp(248), width)
        val panelHeight = panel?.height?.takeIf { it > 0 } ?: dp(270)
        layout.x = layout.x.coerceIn(0, (width - layout.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(0, (height - panelHeight).coerceAtLeast(0))
        if (update && attached) {
            try {
                manager.updateViewLayout(panel, layout)
            } catch (error: Exception) {
                showMessage("悬浮窗已关闭：${error.message ?: "窗口不可用"}")
                stopSelf()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel?.post { clampToScreen(update = true); savePosition() }
    }

    private fun savePosition() {
        params?.let { preferences.edit().putInt("x", it.x).putInt("y", it.y).apply() }
    }

    override fun onDestroy() {
        savePosition()
        controller.setJoystick(JoystickVector(0f, 0f))
        scope.cancel()
        if (attached) panel?.let { runCatching { windowManager?.removeView(it) } }
        attached = false
        panel = null
        joystickView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "悬浮控制", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopIntent = PendingIntent.getService(this, 102,
            Intent(this, SimulationService::class.java).setAction(SimulationService.ACTION_STOP), flags)
        val closeIntent = PendingIntent.getService(this, 103,
            Intent(this, JoystickOverlayService::class.java).setAction(ACTION_CLOSE), flags)
        val openIntent = PendingIntent.getActivity(this, 104, Intent(this, MainActivity::class.java), flags)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mockgeo)
            .setContentTitle("悬浮控制已开启")
            .setContentText("拖动标题可移动，× 仅关闭面板")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "停止模拟", stopIntent).build())
            .addAction(Notification.Action.Builder(null, "关闭面板", closeIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private class JoystickView(context: Context, private val onVector: (JoystickVector) -> Unit) : View(context) {
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 238, 228) }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 117, 100) }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 186, 165)
            strokeWidth = resources.displayMetrics.density
        }
        private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(53, 91, 72)
            textSize = 11 * resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }
        private var knobX = 0f
        private var knobY = 0f

        init { contentDescription = "方向摇杆，上方为北；松开后停止移动" }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) * 0.9f
            canvas.drawCircle(cx, cy, radius, basePaint)
            canvas.drawLine(cx - radius * .7f, cy, cx + radius * .7f, cy, linePaint)
            canvas.drawLine(cx, cy - radius * .7f, cx, cy + radius * .7f, linePaint)
            canvas.drawText("N", cx, cy - radius * .72f, northPaint)
            knobPaint.alpha = if (isEnabled) 255 else 100
            canvas.drawCircle(cx + knobX * radius * .67f, cy + knobY * radius * .67f, radius * .28f, knobPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val radius = minOf(width, height) / 2f * .9f
                    val dx = (event.x - width / 2f) / radius
                    val dy = (event.y - height / 2f) / radius
                    val magnitude = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    updateKnob(dx / magnitude, dy / magnitude)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    release()
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                }
                else -> return super.onTouchEvent(event)
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick()

        fun release() { if (knobX != 0f || knobY != 0f) updateKnob(0f, 0f) }

        private fun updateKnob(dx: Float, dy: Float) {
            knobX = dx; knobY = dy
            onVector(JoystickVector.fromRaw(dx, dy))
            invalidate()
        }
    }

    companion object {
        private const val TAG = "JoystickOverlay"
        private const val CHANNEL_ID = "joystick_overlay"
        private const val NOTIF_ID = 101
        private const val ACTION_CLOSE = "com.mockgeo.action.CLOSE_OVERLAY"

        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) {
                Toast.makeText(ctx, "请在设置中授予悬浮窗权限", Toast.LENGTH_LONG).show()
                return
            }
            try {
                ctx.startForegroundService(Intent(ctx, JoystickOverlayService::class.java))
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Foreground service launch failed", error)
                Toast.makeText(ctx, "悬浮控制条启动失败：${error.message}", Toast.LENGTH_LONG).show()
            }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, JoystickOverlayService::class.java)) }
    }
}
