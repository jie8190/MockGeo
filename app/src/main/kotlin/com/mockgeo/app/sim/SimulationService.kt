package com.mockgeo.app.sim

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.mockgeo.app.MainActivity
import com.mockgeo.app.overlay.JoystickOverlayService
import com.mockgeo.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.koin.android.ext.android.inject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Serialized FGS commands: stop is queued, never discarded by a busy start operation. */
class SimulationService : Service() {
    private val controller: SimController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<Intent>(Channel.UNLIMITED)
    private var commandActive = false
    private var acceptedCommand = false
    private var latestStartId = 0
    private var finishing = false
    private var foreground = false
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MockGeo:simulation")
            .apply { setReferenceCounted(false) }
    }
    private val powerLease by lazy {
        SessionPowerLease({ timeout -> wakeLock.acquire(timeout) }, { if (wakeLock.isHeld) wakeLock.release() })
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "位置模拟状态", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            while (isActive) {
                refreshPowerLease()
                delay(SessionPowerLease.RENEW_MS)
            }
        }
        scope.launch {
            controller.ui.collect { ui ->
                refreshPowerLease()
                if (ui.engineState is EngineState.Running) {
                    notifications.notify(NOTIF_ID, buildNotification(ui))
                } else if (acceptedCommand && !commandActive) settle()
            }
        }
        scope.launch {
            for (intent in commands) {
                commandActive = true
                refreshPowerLease()
                try {
                    (application as com.mockgeo.app.MockGeoApp).ready.await()
                    when (intent.action) {
                        ACTION_START_FIXED -> controller.startFixed(position(intent))
                        ACTION_START_JOYSTICK -> {
                            controller.startJoystick(position(intent), number(intent, EXTRA_ROUTE_SPEED, 1.4))
                            if (controller.ui.value.engineState is EngineState.Running) JoystickOverlayService.start(this@SimulationService)
                        }
                        ACTION_START_ROUTE -> {
                            val draft = pendingRoutes.remove(intent.getStringExtra(EXTRA_ROUTE_TOKEN))
                                ?: throw IllegalArgumentException("路线已过期，请重新开始")
                            controller.startRoute(draft.points, draft.mode, draft.speed, draft.details)
                        }
                        ACTION_PAUSE -> controller.pause()
                        ACTION_RESUME -> controller.resume()
                        ACTION_STOP -> controller.stop()
                        else -> throw IllegalArgumentException("未知模拟操作")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    controller.reportError("操作失败：${error.message ?: "请查看诊断"}")
                    notifyToast("操作失败：${error.message ?: "请查看诊断"}")
                } finally {
                    commandActive = false
                    settle()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        try {
            if (!foreground) {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, buildNotification(controller.ui.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                else startForeground(NOTIF_ID, buildNotification(controller.ui.value))
                foreground = true
            }
        } catch (error: Exception) {
            intent.getStringExtra(EXTRA_ROUTE_TOKEN)?.let { pendingRoutes.remove(it) }
            controller.reportError("无法启动前台服务：请授予定位权限并在应用前台开始。${error.message.orEmpty()}")
            notifyToast("无法启动，请检查定位权限")
            scope.launch { controller.stop(); settle() }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acceptedCommand = true
        finishing = false
        commands.trySend(intent)
        return START_NOT_STICKY // Process death must never resume an old route implicitly.
    }

    private fun settle() {
        refreshPowerLease()
        if (commandActive || !commands.isEmpty || !acceptedCommand) return
        when (val state = controller.ui.value.engineState) {
            EngineState.Idle -> {
                finishing = true
                JoystickOverlayService.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(latestStartId)
            }
            is EngineState.Failed -> {
                notifications.notify(NOTIF_ID, base("模拟异常", state.detail ?: "请打开诊断查看原因")
                    .setOngoing(false).setAutoCancel(true)
                    .addAction(Notification.Action.Builder(null, "重试停止", action(ACTION_STOP, 1)).build()).build())
                // Keep the foreground service when cleanup is uncertain, with an actionable stop.
            }
            else -> Unit
        }
    }

    override fun onDestroy() {
        commands.close()
        while (true) {
            val pending = commands.tryReceive().getOrNull() ?: break
            pending.getStringExtra(EXTRA_ROUTE_TOKEN)?.let { pendingRoutes.remove(it) }
        }
        scope.cancel()
        powerLease.close()
        JoystickOverlayService.stop(this)
        if (!finishing) {
            val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            cleanup.launch { try { controller.stop() } finally { cleanup.cancel() } }
        }
        super.onDestroy()
    }

    private fun refreshPowerLease() {
        val state = controller.ui.value.engineState
        runCatching {
            powerLease.refresh(commandActive || state is EngineState.Starting ||
                state is EngineState.Running || state is EngineState.Stopping)
        }.onFailure { android.util.Log.w("MockGeoPower", "CPU wake lease unavailable", it) }
    }

    private fun position(intent: Intent) = LatLng(number(intent, EXTRA_LAT), number(intent, EXTRA_LNG))
    @Suppress("DEPRECATION")
    private fun number(intent: Intent, key: String, fallback: Double = Double.NaN) =
        (intent.extras?.get(key) as? Number)?.toDouble() ?: fallback
    private fun action(value: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, SimulationService::class.java).setAction(value), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun base(title: String, text: String): Notification.Builder = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(title).setContentText(text)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).setOnlyAlertOnce(true).setOngoing(true)
    private fun buildNotification(ui: SimController.SimUiState): Notification {
        val running = ui.engineState is EngineState.Running
        val name = when (ui.mode) { SimController.Mode.FIXED -> "定点"; SimController.Mode.ROUTE -> "路线"; SimController.Mode.JOYSTICK -> "摇杆" }
        val title = if (running) { if (ui.paused) "已暂停移动 · 仍在模拟" else "正在模拟 · $name" } else "正在处理模拟请求"
        val builder = base(title, ui.currentPosition?.let { "%.6f, %.6f".format(java.util.Locale.ROOT, it.lat, it.lng) } ?: "请稍候")
        if (running && ui.mode != SimController.Mode.FIXED) builder.addAction(Notification.Action.Builder(null,
            if (ui.paused) "继续" else "暂停", action(if (ui.paused) ACTION_RESUME else ACTION_PAUSE, 2)).build())
        return builder.addAction(Notification.Action.Builder(null, "停止模拟", action(ACTION_STOP, 1)).build()).build()
    }
    private fun notifyToast(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

    private data class PendingRoute(val points: List<LatLng>, val mode: RouteMode, val speed: Double, val details: List<RoutePoint>)
    companion object {
        private const val CHANNEL_ID = "sim_status"
        private const val NOTIF_ID = 100
        const val ACTION_START_FIXED = "com.mockgeo.action.START_FIXED"
        const val ACTION_START_ROUTE = "com.mockgeo.action.START_ROUTE"
        const val ACTION_START_JOYSTICK = "com.mockgeo.action.START_JOYSTICK"
        const val ACTION_STOP = "com.mockgeo.action.STOP"
        const val ACTION_PAUSE = "com.mockgeo.action.PAUSE"
        const val ACTION_RESUME = "com.mockgeo.action.RESUME"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ROUTE_SPEED = "route_speed"
        private const val EXTRA_ROUTE_TOKEN = "route_token"
        private val pendingRoutes = ConcurrentHashMap<String, PendingRoute>()
        fun startFixed(ctx: Context, pos: LatLng) {
            ctx.startForegroundService(Intent(ctx, SimulationService::class.java).setAction(ACTION_START_FIXED)
                .putExtra(EXTRA_LAT, pos.lat).putExtra(EXTRA_LNG, pos.lng))
        }
        fun startRoute(ctx: Context, name: String, points: List<LatLng>, mode: RouteMode, speedMps: Double,
                       details: List<RoutePoint> = emptyList()) {
            val token = UUID.randomUUID().toString()
            pendingRoutes[token] = PendingRoute(points.toList(), mode, speedMps, details.toList())
            try { ctx.startForegroundService(Intent(ctx, SimulationService::class.java).setAction(ACTION_START_ROUTE).putExtra(EXTRA_ROUTE_TOKEN, token)) }
            catch (error: Exception) { pendingRoutes.remove(token); throw error }
        }
        fun startJoystick(ctx: Context, origin: LatLng, speedMps: Double) {
            ctx.startForegroundService(Intent(ctx, SimulationService::class.java).setAction(ACTION_START_JOYSTICK)
                .putExtra(EXTRA_LAT, origin.lat).putExtra(EXTRA_LNG, origin.lng).putExtra(EXTRA_ROUTE_SPEED, speedMps))
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, SimulationService::class.java).setAction(ACTION_STOP)) }
        fun pause(ctx: Context) { ctx.startService(Intent(ctx, SimulationService::class.java).setAction(ACTION_PAUSE)) }
        fun resume(ctx: Context) { ctx.startService(Intent(ctx, SimulationService::class.java).setAction(ACTION_RESUME)) }
    }
}
