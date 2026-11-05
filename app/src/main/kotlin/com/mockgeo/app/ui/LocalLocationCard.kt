package com.mockgeo.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mockgeo.core.model.EngineState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LocalLocationCard(vm: HomeViewModel) {
    val capturing by vm.capturingLocation.collectAsState()
    val result by vm.localLocationReport.collectAsState()
    val sim by vm.sim.collectAsState()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.exportLocalLocation(it)?.let(vm::postMessage) }
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("本机定位采集", style = MaterialTheme.typography.titleSmall)
            Text("读取系统返回的坐标、精度、时间、来源和模拟标记。请先停止模拟；其他定位工具仍可能影响结果，采集值不保证是真实位置。", style = MaterialTheme.typography.bodySmall)
            Text("结果包含精确坐标，仅保留在当前应用会话；不会加入常规诊断或自动上传，保存前请确认分享范围。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::captureLocalLocation, enabled = !capturing && sim.engineState == EngineState.Idle) {
                    Text(if (capturing) "正在采集…" else "采集一次（最多 10 秒）")
                }
                if (capturing) TextButton(onClick = vm::cancelLocalLocationCapture) { Text("取消") }
            }
            result?.let { report ->
                report.fixes.forEach { fix ->
                    Text(buildString {
                        append("${fix.requestedProvider} · ${when(fix.providerEnabled) { true -> "已启用"; false -> "未启用"; null -> "状态未知" }}")
                        if (fix.latitude != null && fix.longitude != null) {
                            append("\n${fix.latitude}, ${fix.longitude}\n精度 ${fix.accuracyM ?: "未知"} 米 · 模拟标记 ${fix.mock}")
                            append("\n来源 ${fix.source} / ${fix.provider} · 年龄 ${fix.ageMs ?: "未知"} ms")
                            append("\n定位时间 ${fix.timeMs?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it)) }}")
                        }
                        fix.note?.let { append("\n$it") }
                    }, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { exporter.launch("mockgeo-local-location-${report.capturedAtMs}.json") }) { Text("保存定位记录") }
                    TextButton(onClick = vm::clearLocalLocationCapture) { Text("清除结果") }
                }
            }
        }
    }
}
