package com.mockgeo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.mockgeo.app.sim.SimController
import com.mockgeo.app.BuildConfig
import com.mockgeo.app.ui.map.MapControllerFactory
import com.mockgeo.app.ui.map.RouteMapEdit
import com.mockgeo.app.ui.theme.MockGeoTheme
import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.HistorySource
import com.mockgeo.core.model.LatLng
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

internal enum class HomePanel(val title: String) {
    SEARCH("搜索位置"), FAVORITES("我的收藏"), HISTORY("历史记录"),
    ROUTE("路线模拟"), PARAMS("模拟参数"), TOOLS("工具箱"),
}

@Composable
fun HomeScreen(vm: HomeViewModel = koinInject()) {
    val settings by vm.settings.collectAsState()
    val settingsReady by vm.settingsReady.collectAsState()
    MockGeoTheme(theme = settings.theme) {
        if (settingsReady) MapHome(vm)
        else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapHome(vm: HomeViewModel) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val sim by vm.sim.collectAsState()
    val selected by vm.selected.collectAsState()
    val selectedName by vm.selectedName.collectAsState()
    val settings by vm.settings.collectAsState()
    val checks by vm.checks.collectAsState()
    val message by vm.message.collectAsState()
    val draft by vm.routeDraft.collectAsState()
    val routeControls by vm.routeControls.collectAsState()
    var panel by rememberSaveable { mutableStateOf<HomePanel?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var coordinatesOpen by rememberSaveable { mutableStateOf(false) }
    var favoriteOpen by rememberSaveable { mutableStateOf(false) }
    var mapOptionsOpen by rememberSaveable { mutableStateOf(false) }
    var amapConsentOpen by rememberSaveable { mutableStateOf(false) }
    var positionMenuOpen by remember { mutableStateOf(false) }
    var editingRoutePoint by remember { mutableStateOf<Int?>(null) }
    var routeEditing by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var pendingMapSelection by remember { mutableStateOf<LatLng?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val customAmapKey by com.mockgeo.app.geo.AmapKeyStore.customKeyFlow(context).collectAsState()
    val hasAmapKey = !customAmapKey.isNullOrBlank() || BuildConfig.AMAP_API_KEY.isNotBlank()
    val effectiveProvider = if (settings.mapProvider == "amap" && settings.amapConsent && hasAmapKey) "amap" else "osm"
    val map = remember(effectiveProvider, customAmapKey) { MapControllerFactory.create(context, effectiveProvider) }
    val mapStatus by map.status.collectAsState()
    var slowMap by remember(map) { mutableStateOf(false) }
    var retryCount by remember(map) { mutableIntStateOf(0) }
    val darkMap = MaterialTheme.colorScheme.background.luminance() < .5f
    val running = sim.engineState is EngineState.Running
    val busy = sim.engineState is EngineState.Starting || sim.engineState is EngineState.Stopping
    val openCheck = rememberPermissionAction(vm)

    DisposableEffect(hostView, settings.screenKeepOn, running) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        fun updateKeepScreenOn() {
            hostView.keepScreenOn = settings.screenKeepOn && running &&
                (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true)
        }
        val observer = LifecycleEventObserver { _, _ -> updateKeepScreenOn() }
        lifecycle?.addObserver(observer)
        updateKeepScreenOn()
        onDispose {
            lifecycle?.removeObserver(observer)
            hostView.keepScreenOn = false
        }
    }

    DisposableEffect(map) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { map.onResume(); vm.refreshChecks() }
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        map.onResume()
        onDispose { lifecycle?.removeObserver(observer); map.onPause(); map.dispose() }
    }
    LaunchedEffect(map) {
        map.setMarker(selected)
        launch { map.gestureEvents.collect { follow = false } }
        launch {
            map.routeEdits.collect { edit ->
                if (routeEditing) {
                    follow = false
                    when (edit) {
                        is RouteMapEdit.Move -> vm.moveRoutePoint(edit.index, edit.position)
                        is RouteMapEdit.Select -> editingRoutePoint = edit.index
                    }
                }
            }
        }
        launch {
            map.tapEvents.collect { position ->
                pendingMapSelection = position
                follow = false; vm.select(position)
                if (routeEditing) vm.addRoutePoint(position)
            }
        }
        launch {
            map.longPressEvents.collect { position ->
                pendingMapSelection = position
                follow = false; vm.select(position); vm.addHistory(position, HistorySource.MAP_TAP)
                if (routeEditing) vm.addRoutePoint(position)
            }
        }
    }
    LaunchedEffect(map, selected) {
        val selectedOnMap = selected != null && selected == pendingMapSelection
        pendingMapSelection = null
        map.setMarker(selected)
        if (!selectedOnMap) selected?.let { map.moveTo(it) }
    }
    LaunchedEffect(map, sim.currentPosition, follow, running) {
        map.setFollowMode(running && follow)
        if (running && follow) sim.currentPosition?.let { map.moveTo(it) }
        map.setMarker(if (running) sim.currentPosition else null, "simulation")
    }
    LaunchedEffect(map, draft, routeControls, sim.routePoints, running, routeEditing) {
        map.clearRouteOverlays()
        map.addRouteOverlay(if (running && sim.mode == SimController.Mode.ROUTE && !routeEditing) sim.routePoints else draft)
        map.setRouteHandles(routeControls, routeEditing)
    }
    LaunchedEffect(map, settings.mapSatellite, darkMap) { map.setStyle(settings.mapSatellite && effectiveProvider == "amap", darkMap) }
    LaunchedEffect(map, mapStatus.loading, retryCount) {
        slowMap = false
        if (mapStatus.loading) { delay(8000); slowMap = true }
    }
    LaunchedEffect(settings.mapProvider, settings.amapConsent, hasAmapKey) {
        if (settings.mapProvider == "amap" && !settings.amapConsent && hasAmapKey) {
            delay(200)
            amapConsentOpen = true
        } else amapConsentOpen = false
    }
    LaunchedEffect(settings.engineId) { vm.refreshChecks() }
    LaunchedEffect(message, panel, settingsOpen, diagnosticsOpen) {
        if (panel == null && !settingsOpen && !diagnosticsOpen) message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    BackHandler(enabled = routeEditing && panel == null) { routeEditing = false }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        key(map) { AndroidView(factory = { map.view }, modifier = Modifier.fillMaxSize()) }
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)) {
            Surface(shape = RoundedCornerShape(22.dp), shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { panel = HomePanel.TOOLS }) { Icon(Icons.Default.Menu, "功能菜单") }
                    Row(Modifier.weight(1f).fillMaxHeight().clickable { panel = HomePanel.SEARCH }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("搜索地点、地标或地址", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { coordinatesOpen = true }) { Icon(Icons.Default.PinDrop, "输入坐标", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, "偏好设置", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = CircleShape, color = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = .96f), shadowElevation = 2.dp) {
                    Row(Modifier.clickable { diagnosticsOpen = true }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(if (running) "模拟运行中" else "已就绪", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        val engineLabel = when (settings.engineId) {
                            "provider" -> "标准模式"
                            "root" -> "Root 直连"
                            else -> "LSPosed"
                        }
                        Text(" · $engineLabel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (settings.compatibilityMode && settings.engineId != "provider") {
                            Text(" · 无痕", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), shadowElevation = 2.dp) {
                    Row(Modifier.clickable { mapOptionsOpen = true }.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Layers, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(5.dp))
                        Text(if (map.providerId == "amap") (if (settings.mapSatellite) "高德卫星" else "高德地图") else "开放地图", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (slowMap || (!mapStatus.loading && mapStatus.firstFrameMs == null && mapStatus.detail != null)) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .97f), shadowElevation = 1.dp) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WifiOff, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("地图加载较慢，可重试或切换", Modifier.weight(1f).padding(start = 7.dp), style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { map.retry(); retryCount++ }) { Text("重试") }
                        IconButton(onClick = { mapOptionsOpen = true }, Modifier.size(32.dp)) { Icon(Icons.Default.Layers, "切换地图", Modifier.size(18.dp)) }
                    }
                }
            }
            if (routeEditing) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Route, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("点选添加，长按拖动 · ${routeControls.size} 点", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { routeEditing = false; panel = HomePanel.ROUTE }) { Text("完成") }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MapButton(Icons.Default.Add, "放大地图") { map.zoomIn() }
            MapButton(Icons.Default.Remove, "缩小地图") { map.zoomOut() }
            MapButton(Icons.Default.MyLocation, "回到真实位置") { vm.locateRealPosition(); follow = false }
            if (running) MapButton(if (follow) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed, "跟随模拟位置") {
                follow = !follow; if (follow) sim.currentPosition?.let { map.moveTo(it) }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp)) {
            if (map.providerId == "osm") Text("© OpenStreetMap contributors", Modifier.padding(start = 3.dp, bottom = 5.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .85f), RoundedCornerShape(4.dp)).padding(3.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val failedChecks = checks.filter { !it.ok && it.actionKey in listOf("PERMISSION", "DEV_OPTIONS", "NONE") }
            if (!running && failedChecks.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { diagnosticsOpen = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("还有 ${failedChecks.size} 项运行设置待完成", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Default.ChevronRight, "查看设置", Modifier.size(18.dp))
                    }
                }
            }
            Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(if (running && sim.mode == SimController.Mode.ROUTE) Icons.Default.Route else Icons.Default.LocationOn,
                                null, Modifier.padding(11.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(when {
                                running && sim.paused -> "已暂停移动"
                                running && sim.mode == SimController.Mode.ROUTE -> "路线模拟中"
                                running && sim.mode == SimController.Mode.JOYSTICK -> "摇杆控制中"
                                running -> "定点模拟中"
                                sim.engineState is EngineState.Starting -> "正在启动…"
                                sim.engineState is EngineState.Stopping -> "正在恢复真实定位…"
                                else -> selectedName ?: if (selected == null) "选择你的目的地" else "已选位置"
                            }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (running) sim.currentPosition else selected)?.let(::coordinates) ?: "轻触地图选点，或搜索目的地",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                            if (selected != null || running) {
                                Row(
                                    Modifier.padding(top = 6.dp).clickable { panel = HomePanel.PARAMS },
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "卫星 ${settings.satellites}颗 · 精度 %.0fm · 海拔 %.0fm".format(settings.gpsAccuracyM, settings.altitudeM) +
                                            if (settings.jitterEnabled) " · 浮动 %.1fm".format(settings.jitterMeters) else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { favoriteOpen = true }, enabled = selected != null || sim.currentPosition != null, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.StarBorder, "收藏当前位置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { positionMenuOpen = true }, enabled = selected != null || (running && sim.currentPosition != null), modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.MoreVert, "位置操作", Modifier.size(20.dp))
                            }
                            DropdownMenu(expanded = positionMenuOpen, onDismissRequest = { positionMenuOpen = false }) {
                                val position = if (running) sim.currentPosition else selected
                                DropdownMenuItem(text = { Text("复制位置") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                                    positionMenuOpen = false; position?.let { copyPosition(context, selectedName, it); vm.postMessage("位置已复制") }
                                })
                                DropdownMenuItem(text = { Text("分享位置") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                    positionMenuOpen = false; position?.let { sharePosition(context, selectedName, it) }
                                })
                                DropdownMenuItem(text = { Text("编辑坐标") }, leadingIcon = { Icon(Icons.Default.EditLocationAlt, null) }, onClick = { positionMenuOpen = false; coordinatesOpen = true })
                            }
                        }
                    }
                    if (sim.engineState is EngineState.Failed) {
                        Text(errorLabel(sim.engineState as EngineState.Failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 10.dp).clickable { diagnosticsOpen = true }, maxLines = 3)
                    }
                    if (running && sim.mode == SimController.Mode.ROUTE) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(progress = { sim.routeProgress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${distanceLabel(sim.routeDistanceM * sim.routeProgress)} / ${distanceLabel(sim.routeDistanceM)}" +
                                if (sim.routeMode == com.mockgeo.core.model.RouteMode.LOOP) " · 已循环 ${sim.completedLaps} 圈" else "",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("%.1f km/h".format(sim.currentSpeedMps * 3.6), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (running) {
                            if (sim.mode != SimController.Mode.FIXED) {
                                OutlinedButton(onClick = { if (sim.paused) vm.resumeSim() else vm.pauseSim() }, modifier = Modifier.weight(1f).height(48.dp)) {
                                    Icon(if (sim.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(19.dp))
                                    Spacer(Modifier.width(5.dp)); Text(if (sim.paused) "继续" else "暂停")
                                }
                            }
                            Button(onClick = { vm.stopSim(context) }, modifier = Modifier.weight(1f).height(48.dp)) {
                                Icon(Icons.Default.Stop, null, Modifier.size(19.dp)); Spacer(Modifier.width(5.dp)); Text("停止模拟")
                            }
                        } else if (sim.engineState is EngineState.Failed) {
                            Button(onClick = { vm.stopSim(context) }, modifier = Modifier.weight(1f).height(48.dp)) {
                                Icon(Icons.Default.Stop, null, Modifier.size(19.dp)); Spacer(Modifier.width(5.dp)); Text("停止并恢复定位")
                            }
                            OutlinedButton(onClick = { diagnosticsOpen = true }, modifier = Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 13.dp)) {
                                Icon(Icons.Default.HealthAndSafety, "查看诊断", Modifier.size(22.dp))
                            }
                        } else {
                            Button(onClick = { vm.startFixed(context) }, enabled = selected != null && !busy, modifier = Modifier.weight(1f).height(48.dp)) {
                                if (busy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp) else Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp)); Text(if (busy) "处理中…" else "开始模拟")
                            }
                            OutlinedButton(onClick = {
                                val overlay = checks.firstOrNull { it.actionKey == "OVERLAY" && !it.ok }
                                if (overlay != null) openCheck(overlay) else vm.startJoystick(context)
                            }, enabled = selected != null && !busy, modifier = Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 13.dp)) {
                                Icon(Icons.Default.Gamepad, "开启悬浮摇杆", Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeShortcut("路线", Icons.Default.Route, Modifier.weight(1f)) { panel = HomePanel.ROUTE }
                HomeShortcut("收藏", Icons.Default.BookmarkBorder, Modifier.weight(1f)) { panel = HomePanel.FAVORITES }
                HomeShortcut("历史", Icons.Default.History, Modifier.weight(1f)) { panel = HomePanel.HISTORY }
                HomeShortcut("参数", Icons.Default.Tune, Modifier.weight(1f)) { panel = HomePanel.PARAMS }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 275.dp))
    }
    if (panel != null) {
        val current = panel!!
        ModalBottomSheet(onDismissRequest = { panel = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface, dragHandle = { BottomSheetDefaults.DragHandle() }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(current.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { panel = null }) { Icon(Icons.Default.Close, "关闭面板") }
                }
                message?.let { InlineMessage(it) { vm.consumeMessage() } }
                when (current) {
                    HomePanel.SEARCH -> SearchPanel(vm, onChosen = { panel = null }, onCoordinates = { panel = null; coordinatesOpen = true })
                    HomePanel.FAVORITES -> FavoritesPanel(vm, onChosen = { panel = null }, onAdd = { favoriteOpen = true })
                    HomePanel.HISTORY -> HistoryPanel(vm) { panel = null }
                    HomePanel.ROUTE -> RoutePanel(vm, onEditMap = { panel = null; routeEditing = true; follow = false },
                        onPreview = { points ->
                            panel = null; follow = false
                            if (points.size >= 2) map.fitRoute(points) else points.firstOrNull()?.let { map.moveTo(it, zoom = 14.0) }
                        },
                        onStart = { panel = null; routeEditing = false; follow = true })
                    HomePanel.PARAMS -> ParametersPanel(vm)
                    HomePanel.TOOLS -> ToolsPanel(onPanel = { panel = it },
                        onCoordinates = { panel = null; coordinatesOpen = true }, onSettings = { panel = null; settingsOpen = true },
                        onDiagnostics = { panel = null; diagnosticsOpen = true }, onJoystick = {
                            panel = null
                            val overlay = checks.firstOrNull { it.actionKey == "OVERLAY" && !it.ok }
                            if (overlay != null) openCheck(overlay) else vm.startJoystick(context)
                        })
                }
            }
        }
    }
    if (coordinatesOpen) CoordinateDialog(selected, onDismiss = { coordinatesOpen = false }) {
        vm.select(it); vm.addHistory(it, HistorySource.MANUAL); coordinatesOpen = false; follow = false
    }
    if (favoriteOpen) NameDialog("收藏位置", selectedName.orEmpty(), "位置名称", onDismiss = { favoriteOpen = false }) { name ->
        (if (running) sim.currentPosition else selected)?.let { vm.addFavorite(name, it) }
        favoriteOpen = false
    }
    if (settingsOpen) SettingsDialog(vm) { settingsOpen = false }
    if (diagnosticsOpen) DiagnosticsDialog(vm) { diagnosticsOpen = false }
    if (mapOptionsOpen) MapOptionsDialog(vm) { mapOptionsOpen = false }
    if (amapConsentOpen) AmapConsentDialog(onDecline = { amapConsentOpen = false; vm.setMapProvider("osm") }, onAccept = {
        amapConsentOpen = false; vm.setAmapConsent(true)
    })
    editingRoutePoint?.let { index ->
        routeControls.getOrNull(index)?.let { point ->
            CoordinateDialog(point, onDismiss = { editingRoutePoint = null }, title = "编辑途经点 ${index + 1}", onDelete = {
                vm.removeRoutePoint(index); editingRoutePoint = null
            }) { vm.moveRoutePoint(index, it); editingRoutePoint = null }
        }
    }
}

@Composable
private fun MapButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        IconButton(onClick, Modifier.size(44.dp)) { Icon(icon, label, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun HomeShortcut(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .98f), shadowElevation = 1.dp) {
        Row(Modifier.clickable(onClick = onClick).padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(5.dp)); Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun coordinates(pos: LatLng): String = "%.6f, %.6f".format(java.util.Locale.US, pos.lat, pos.lng)
internal fun distanceLabel(meters: Double): String = if (meters >= 1000) "%.2f km".format(meters / 1000) else "%.0f m".format(meters)
internal fun errorLabel(failed: EngineState.Failed): String = when (failed.reason) {
    com.mockgeo.core.model.SimError.MOCK_APPOP_DENIED -> "请在开发者选项中将 MockGeo 设为模拟位置应用"
    com.mockgeo.core.model.SimError.PERMISSION_DENIED -> "定位权限未开启，点击查看设置"
    com.mockgeo.core.model.SimError.MODULE_UNREACHABLE -> failed.detail?.takeIf { it.isNotBlank() }
        ?: "无法连接系统定位服务，点击查看诊断"
    com.mockgeo.core.model.SimError.ACK_TIMEOUT -> "引擎响应超时，请查看诊断后重试"
    else -> failed.detail ?: "模拟启动失败，请查看诊断后重试"
}
