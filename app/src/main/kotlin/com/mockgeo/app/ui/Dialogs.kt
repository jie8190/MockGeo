package com.mockgeo.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mockgeo.core.model.CheckResult
import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.LatLng
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoordinateDialog(current: LatLng?, onDismiss: () -> Unit, title: String = "输入坐标", onDelete: (() -> Unit)? = null, onConfirm: (LatLng) -> Unit) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(android.content.ClipboardManager::class.java) }
    var lat by rememberSaveable { mutableStateOf(current?.lat?.toString().orEmpty()) }
    var lng by rememberSaveable { mutableStateOf(current?.lng?.toString().orEmpty()) }
    var coordSys by rememberSaveable { mutableStateOf("wgs84") }
    var pasteHint by remember { mutableStateOf<String?>(null) }
    val latitude = lat.trim().toDoubleOrNull()
    val longitude = lng.trim().toDoubleOrNull()
    val latValid = latitude != null && latitude.isFinite() && latitude in -90.0..90.0
    val lngValid = longitude != null && longitude.isFinite() && longitude in -180.0..180.0
    val convertedWgs84 = remember(latitude, longitude, coordSys) {
        if (latValid && lngValid) {
            when (coordSys) {
                "gcj02" -> com.mockgeo.app.geo.AmapKeyStore.gcj02ToWgs84(latitude!!, longitude!!)
                "bd09" -> com.mockgeo.app.geo.AmapKeyStore.bd09ToWgs84(latitude!!, longitude!!)
                else -> LatLng(latitude!!, longitude!!)
            }
        } else null
    }
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择来源坐标系（高德/百度拾取器复制的坐标将自动反算为标准 WGS 84）：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("wgs84" to "WGS 84", "gcj02" to "高德/腾讯", "bd09" to "百度 BD-09").forEach { (id, label) ->
                        FilterChip(selected = coordSys == id, onClick = { coordSys = id }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            val parsed = com.mockgeo.app.geo.AmapKeyStore.parseCoordinateText(text)
                            if (parsed != null) {
                                lat = "%.6f".format(java.util.Locale.US, parsed.first)
                                lng = "%.6f".format(java.util.Locale.US, parsed.second)
                                pasteHint = if (parsed.third) "已自动按「经度,纬度」识别并填入" else "已从剪贴板填入坐标"
                            } else {
                                pasteHint = "剪贴板中未识别到有效经纬度数字"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("粘贴识别", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = {
                            val tmp = lat
                            lat = lng
                            lng = tmp
                            pasteHint = null
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("经纬互换", style = MaterialTheme.typography.labelMedium)
                    }
                }
                pasteHint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                OutlinedTextField(lat, { lat = it }, Modifier.fillMaxWidth(), label = { Text("纬度 (Lat)") }, placeholder = { Text("例如 39.904200") },
                    supportingText = { Text("−90 ～ 90，北纬为正") }, singleLine = true,
                    isError = lat.isNotBlank() && !latValid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                OutlinedTextField(lng, { lng = it }, Modifier.fillMaxWidth(), label = { Text("经度 (Lng)") }, placeholder = { Text("例如 116.407400") },
                    supportingText = { Text("−180 ～ 180，东经为正") }, singleLine = true,
                    isError = lng.isNotBlank() && !lngValid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                if (coordSys != "wgs84" && convertedWgs84 != null) {
                    Text(
                        "转换后 WGS 84：%.6f, %.6f".format(java.util.Locale.US, convertedWgs84.lat, convertedWgs84.lng),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                onDelete?.let { remove -> TextButton(onClick = remove) { Icon(Icons.Default.DeleteOutline, null, Modifier.size(17.dp)); Text("删除此途经点") } }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(convertedWgs84!!) }, enabled = convertedWgs84 != null) { Text(if (onDelete == null) "选中位置" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun NameDialog(title: String, initial: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(80) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ConfirmDialog(title: String, message: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(vm: HomeViewModel, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val sim by vm.sim.collectAsState()
    val message by vm.message.collectAsState()
    val idle = sim.engineState == EngineState.Idle
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.exportSettings(it)?.let(vm::postMessage) }
    }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImport = uri }
    FullPageDialog("偏好设置", onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            message?.let { InlineMessage(it) { vm.consumeMessage() } }
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                        Icon(Icons.Default.Explore, null, Modifier.padding(10.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("MockGeo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("自由探索每一处位置与路线", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            SectionLabel("地图与图层")
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.padding(16.dp)) { MapPreferences(vm) }
            }
            SectionLabel("运行模式")
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    EngineOption("标准模式", "无需 Root，通过系统模拟位置功能运行", settings.engineId == "provider", idle) { vm.setEngine("provider") }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    EngineOption("LSPosed 模式", "系统级深度接管，全面兼容各类应用与小程序", settings.engineId == "xposed", idle) { vm.setEngine("xposed") }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    EngineOption("Root 直连模式", "通过超级用户授权加载系统定位功能，无需另装模块", settings.engineId == "root", idle) { vm.setEngine("root") }
                }
            }
            Text(if (idle) {
                when (settings.engineId) {
                    "provider" -> "在系统「开发者选项 → 选择模拟位置信息应用」中选中 MockGeo 即可使用。"
                    "root" -> "首次开始时加载 Root 组件。若系统仍驻留 LSPosed，请先停用框架注入并手动重启；应用内传感器兼容项暂不支持。"
                    else -> "启用 MockGeo 模块后生效，全局接管定位请求。"
                }
            } else "正在模拟位置中，停止模拟后可切换运行模式。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings.engineId == "xposed" || settings.engineId == "root") {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)) {
                    Box(Modifier.padding(16.dp)) {
                        SwitchRow("深度无痕模式（推荐）", "隐藏模拟定位标记，并同步虚拟基站、Wi-Fi 与卫星信号，提升应用与小程序定位真实度（停止状态下可修改）", settings.compatibilityMode) {
                            vm.setCompatibilityMode(it)
                        }
                    }
                }
            }
            SectionLabel("外观与体验")
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色外观", "dark" to "深色外观").forEach { (id, label) ->
                            FilterChip(selected = settings.theme == id, onClick = { vm.setTheme(id) }, label = { Text(label) })
                        }
                    }
                    HorizontalDivider()
                    SwitchRow("模拟时保持屏幕常亮", "防止路线移动或摇杆操作时手机自动锁屏", settings.screenKeepOn) { vm.setScreenKeepOn(it) }
                    HorizontalDivider()
                    SettingSlider("历史记录上限", "${settings.historyLimit} 条", settings.historyLimit.toFloat(), 10f..1000f, defaultValue = 100f, unit = "条") { vm.setHistoryLimit(it.toInt()) }
                }
            }
            SectionLabel("数据备份与恢复")
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("收藏夹、历史记录和路线可在对应面板导出为通用 GPX 轨迹；此处可备份或恢复你的模拟参数与偏好设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { exporter.launch("mockgeo-settings.json") }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("备份设置") }
                        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, modifier = Modifier.weight(1f), enabled = idle) { Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("恢复备份") }
                    }
                }
            }
            SectionLabel("关于 MockGeo")
            Text("支持全球地图选点、沿道路路线模拟、悬浮摇杆实时控制与 GPX 轨迹导入导出。停止模拟后立即恢复手机真实定位。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("地图服务由高德地图或 © OpenStreetMap contributors 提供。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
    }
    pendingImport?.let { uri -> ConfirmDialog("恢复设置？", "将使用备份文件中的参数替换当前设置。", "恢复", { pendingImport = null }) {
        vm.importSettings(uri)?.let(vm::postMessage); pendingImport = null
    } }
}

@Composable
private fun EngineOption(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
fun DiagnosticsDialog(vm: HomeViewModel, onDismiss: () -> Unit) {
    val sim by vm.sim.collectAsState()
    val checks by vm.checks.collectAsState()
    val message by vm.message.collectAsState()
    var logs by remember { mutableStateOf(vm.diagnostics()) }
    var refreshed by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val onCheck = rememberPermissionAction(vm)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { vm.exportDiagnostics(it)?.let(vm::postMessage) }
    }
    LaunchedEffect(Unit) {
        vm.refreshChecks()
        while (true) { logs = vm.diagnostics(); delay(1500) }
    }
    FullPageDialog("运行状态", onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            message?.let { InlineMessage(it) { vm.consumeMessage() } }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (sim.engineState is EngineState.Running) Icons.Default.Verified else Icons.Default.Shield,
                        null,
                        Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(when (sim.engineState) {
                            EngineState.Idle -> "就绪 · 当前使用真实物理位置"
                            is EngineState.Starting -> "正在启动位置模拟…"
                            is EngineState.Stopping -> "正在停止并恢复真实定位…"
                            is EngineState.Running -> if (sim.paused) "路线已暂停 · 位置保持中" else "位置模拟运行中"
                            is EngineState.Failed -> "需要完成一项设置"
                        }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        sim.currentPosition?.let { Text("当前模拟坐标：${coordinates(it)}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall) }
                        (sim.engineState as? EngineState.Failed)?.let { Text(errorLabel(it), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("运行环境")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.refreshChecks(); logs = vm.diagnostics(); refreshed = System.currentTimeMillis() }) { Icon(Icons.Default.Refresh, null, Modifier.size(17.dp)); Text("重新检查") }
            }
            checks.forEach { check ->
                Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = check.actionKey != "NONE" || !check.ok) { onCheck(check) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (check.ok) Icons.Default.CheckCircle else Icons.Default.Info, null, Modifier.size(22.dp), tint = if (check.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(check.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(check.message ?: "已就绪", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (!check.ok) Icon(Icons.Default.ChevronRight, "前往设置", Modifier.size(18.dp))
                    }
                }
            }
            Text("检查时间 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(refreshed))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("运行日志 · ${logs.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { exporter.launch("mockgeo-diagnostics.txt") }) { Icon(Icons.Default.FileDownload, null, Modifier.size(17.dp)); Text("导出") }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (logs.isEmpty()) Text("还没有会话日志。开始模拟后会显示状态与错误信息。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    logs.forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullPageDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = { TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "返回地图") } }) }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) { content() }
            }
        }
    }
}

@Composable
internal fun rememberPermissionAction(vm: HomeViewModel): (CheckResult) -> Unit {
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.refreshChecks()
        if (result.values.any { !it }) vm.postMessage("权限未授予，可在系统应用设置中开启")
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshChecks() }
    return { check ->
        val intent = when (check.actionKey) {
            "DEV_OPTIONS" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            "OVERLAY" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            "APP_DETAILS" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            "BATTERY" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else -> null
        }
        when {
            intent != null -> runCatching { settingsLauncher.launch(intent) }.onFailure {
                runCatching { settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                    .onFailure { vm.postMessage("请手动打开系统设置，查看应用权限") }
            }
            check.actionKey == "PERMISSION" -> {
                if (check.name.contains("通知") && Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            else -> vm.postMessage("安装 MockGeo 模块，在 LSPosed 中启用并勾选系统框架，然后重启设备")
        }
    }
}

@Composable
internal fun InlineMessage(message: String, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭提示", Modifier.size(16.dp)) }
        }
    }
}
