package com.mockgeo.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mockgeo.app.BuildConfig
import com.mockgeo.app.diagnostics.DiagnosticFiles
import com.mockgeo.app.diagnostics.SupportContact

/** A guide, not a claimed successful check of an OEM-private power policy. */
@Composable
internal fun BackgroundRunGuide(vm: HomeViewModel) {
    val context = LocalContext.current
    var showing by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshChecks() }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BatteryChargingFull, null, Modifier.size(22.dp))
                Text("后台持续运行", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text("自动省电可能冻结应用。请将 MockGeo 的应用省电策略设为“不受限制 / 无限制”，避免熄屏或切到后台后模拟中断。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showing = true }, modifier = Modifier.align(Alignment.End)) { Text("设置不受限制") }
        }
    }
    if (showing) AlertDialog(
        onDismissRequest = { showing = false },
        icon = { Icon(Icons.Default.BatteryChargingFull, null) },
        title = { Text("允许后台持续运行") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. 打开 MockGeo 的应用信息，进入“电池 / 耗电管理 / 应用省电策略”。")
                Text("2. 选择“不受限制 / 无限制”，并允许后台运行；如有自启动选项，也请开启。")
                Text("小米 / HyperOS 的“无限制”与系统“忽略电池优化”可能是两项不同设置，请确认应用省电策略。")
                Text("3. 如使用墓碑或后台冻结管理，请将 MockGeo 加入不冻结名单。不同品牌的设置名称可能不同。")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val opened = BackgroundSettings.intents(context).firstOrNull { intent ->
                    runCatching { launcher.launch(intent) }.isSuccess
                } != null
                if (!opened) vm.postMessage("请手动进入系统设置 → 应用 → MockGeo → 电池，选择不受限制")
                showing = false
            }) { Text("打开省电设置") }
        },
        dismissButton = { TextButton(onClick = { showing = false }) { Text("稍后设置") } },
    )
}

object BackgroundSettings {
    fun intents(context: Context): List<Intent> = buildList {
        if (Build.MANUFACTURER.lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "redmi", "poco")) {
            add(Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", context.packageName).putExtra("package_label", "MockGeo"))
        }
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}

@Composable
internal fun DiagnosticExportCard(vm: HomeViewModel) {
    val busy by vm.diagnosticBusy.collectAsState()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { vm.exportDiagnostics(it) }
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("遇到定位异常时，先复现问题，再导出日志或发送邮件反馈。日志包含设备、运行环境和当前进程的近期事件，已过滤地图 Key 等敏感字段。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = { exporter.launch(DiagnosticFiles.suggestedName()) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("保存诊断日志")
            }
            OutlinedButton(onClick = vm::sendDiagnosticFeedback, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Email, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("邮件反馈（附日志）")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("邮件将发往 ${SupportContact.EMAIL}，由你确认后发送；也可先保存查看附件。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ContactCard(vm: HomeViewModel) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MockGeo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            Text("开发者联系邮箱", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(SupportContact.EMAIL, style = MaterialTheme.typography.bodyLarge) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    runCatching { context.startActivity(SupportContact.emailIntent()) }
                        .onFailure { vm.postMessage("未找到邮件应用，请复制邮箱地址后发送") }
                }) { Icon(Icons.Default.Email, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("邮件联系") }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MockGeo 联系邮箱", SupportContact.EMAIL))
                    vm.postMessage("邮箱地址已复制")
                }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("复制邮箱") }
            }
        }
    }
}
