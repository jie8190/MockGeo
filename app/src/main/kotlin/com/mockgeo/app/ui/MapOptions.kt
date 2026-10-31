package com.mockgeo.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mockgeo.app.BuildConfig
import com.mockgeo.app.geo.AmapKeyStore
import com.mockgeo.core.model.LatLng

@Composable
internal fun AmapConsentDialog(onDecline: () -> Unit, onAccept: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDecline,
        icon = { Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("启用高德地图") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("高德地图适合国内地点浏览、搜索和路线规划。")
                Text("启用后，高德 SDK 将处理设备与网络信息，并向高德发送地图请求、搜索词，以及你选择的坐标，用于加载地图和提供地点服务。", style = MaterialTheme.typography.bodyMedium)
                Text("你可以继续使用 OpenStreetMap，也可随时在设置中切换地图并撤回授权。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com/pages/privacy/"))) }
                }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("阅读高德隐私政策") }
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("同意并启用") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("继续使用 OSM") } })
}

@Composable
internal fun MapOptionsDialog(vm: HomeViewModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("地图与图层") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { MapPreferences(vm) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@Composable
internal fun MapPreferences(vm: HomeViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val customKey by AmapKeyStore.customKeyFlow(context).collectAsState()
    var keyDialogOpen by remember { mutableStateOf(false) }
    var enableAfterKeySave by remember { mutableStateOf(false) }
    val hasAmap = !customKey.isNullOrBlank() || BuildConfig.AMAP_API_KEY.isNotBlank()
    val usingAmap = hasAmap && settings.mapProvider == "amap" && settings.amapConsent
    val keySourceLabel = when {
        !customKey.isNullOrBlank() -> "正在使用个人独立 Key"
        BuildConfig.AMAP_API_KEY.isNotBlank() -> "内置服务已就绪"
        else -> "需配置 Key 后启用"
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MapChoice("高德地图", "国内地图、地点搜索与道路规划（$keySourceLabel）", usingAmap, true) {
            if (!hasAmap) {
                enableAfterKeySave = true
                keyDialogOpen = true
            } else vm.setMapProvider("amap") // HomeScreen owns the single consent dialog.
        }
        MapChoice("OpenStreetMap", "全球开放地图，免配置即用", !usingAmap, true) { vm.setMapProvider("osm") }
        if (usingAmap) {
            HorizontalDivider()
            Text("地图图层", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = !settings.mapSatellite, onClick = { vm.setMapSatellite(false) }, label = { Text("标准地图") }, leadingIcon = { Icon(Icons.Default.Map, null, Modifier.size(17.dp)) })
                FilterChip(selected = settings.mapSatellite, onClick = { vm.setMapSatellite(true) }, label = { Text("卫星影像") }, leadingIcon = { Icon(Icons.Default.SatelliteAlt, null, Modifier.size(17.dp)) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { enableAfterKeySave = false; keyDialogOpen = true }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Key, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (customKey.isNullOrBlank()) "自定义高德密钥 (可选)" else "管理个人高德密钥")
            }
            if (settings.amapConsent) {
                TextButton(onClick = { vm.setMapProvider("osm"); vm.setAmapConsent(false) }, contentPadding = PaddingValues(0.dp)) {
                    Text("撤回授权")
                }
            }
        }
    }
    if (keyDialogOpen) AmapKeyConfigDialog(
        currentCustomKey = customKey.orEmpty(),
        hasBuiltInKey = BuildConfig.AMAP_API_KEY.isNotBlank(),
        onDismiss = { keyDialogOpen = false },
        onSave = { newKey ->
            AmapKeyStore.setCustomApiKey(context, newKey)
            keyDialogOpen = false
            val keyAvailable = newKey.isNotBlank() || BuildConfig.AMAP_API_KEY.isNotBlank()
            if (enableAfterKeySave && keyAvailable) {
                vm.setMapProvider("amap")
            }
            enableAfterKeySave = false
            vm.postMessage(when {
                newKey.isNotBlank() -> "自定义高德 Key 已保存"
                keyAvailable -> "已恢复内置高德 Key"
                else -> "个人 Key 已清除，当前使用 OpenStreetMap"
            })
        },
    )
}

@Composable
internal fun AmapKeyConfigDialog(
    currentCustomKey: String,
    hasBuiltInKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    val pkgName = context.packageName
    val sha1 = remember { AmapKeyStore.getSigningSha1(context) }
    var keyInput by remember { mutableStateOf(currentCustomKey) }
    var showCertInfo by remember { mutableStateOf(false) }
    val validFormat = keyInput.isBlank() || keyInput.trim().matches(Regex("[A-Za-z0-9]{16,64}"))

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("高德地图独立密钥") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (hasBuiltInKey) "可填入个人高德 Key，使用自己的搜索与路线规划配额。"
                    else "此安装包未内置高德 Key。填写个人 Android SDK Key 后可启用高德地图、搜索与路线规划。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("个人高德 Key") },
                    placeholder = { Text(if (hasBuiltInKey) "留空则使用默认内置服务" else "粘贴 32 位高德 Key") },
                    singleLine = true,
                    isError = !validFormat,
                    supportingText = {
                        Text(if (!validFormat) "请输入 16~64 位字母或数字" else "保存后自动生效")
                    },
                )
                TextButton(
                    onClick = { showCertInfo = !showCertInfo },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(if (showCertInfo) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (showCertInfo) "收起申请信息" else "查看申请 Key 所需的包名与签名信息", style = MaterialTheme.typography.labelMedium)
                }
                if (showCertInfo) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("应用包名", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(pkgName, style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("PackageName", pkgName))
                                }) { Text("复制") }
                            }
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("签名 SHA1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(sha1, style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("SHA1", sha1))
                                }) { Text("复制") }
                            }
                        }
                    }
                }
                if (currentCustomKey.isNotBlank() && hasBuiltInKey) {
                    TextButton(onClick = { onSave("") }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.Restore, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("恢复默认内置服务")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(keyInput.trim()) }, enabled = validFormat) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun MapChoice(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, enabled = enabled)
        Column(Modifier.padding(start = 5.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun positionText(name: String?, position: LatLng): String = buildString {
    name?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
    append("${coordinates(position)} (WGS 84)")
}

internal fun copyPosition(context: Context, name: String?, position: LatLng) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("位置", positionText(name, position)))
}

internal fun sharePosition(context: Context, name: String?, position: LatLng) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, positionText(name, position)) }
    runCatching { context.startActivity(Intent.createChooser(send, "分享位置")) }
}
