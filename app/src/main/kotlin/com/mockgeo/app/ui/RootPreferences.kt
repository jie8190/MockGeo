package com.mockgeo.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mockgeo.core.model.RootSuPath
import com.mockgeo.core.model.RootSuMode

@Composable
internal fun RootPreferences(vm: HomeViewModel, idle: Boolean) {
    val settings by vm.settings.collectAsState()
    val checking by vm.checkingRoot.collectAsState()
    val saving by vm.savingRootPath.collectAsState()
    val result by vm.rootAccessCheck.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Root 授权", style = MaterialTheme.typography.titleSmall)
            Text(if (settings.rootSuPath.isBlank()) "su 路径：默认" else "su 路径：${settings.rootSuPath}",
                style = MaterialTheme.typography.bodySmall)
            Text("调用方式：${settings.rootSuMode.label}", style = MaterialTheme.typography.bodySmall)
            Text("临时 Root 可填写提权工具提供的 su 完整路径。授权检测只检查权限，不启动位置模拟。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = true }, enabled = idle && !checking && !saving) { Text(if (saving) "正在保存…" else "设置路径") }
                TextButton(onClick = vm::checkRootAccess, enabled = idle && !checking && !saving) { Text(if (checking) "正在检测…" else "检测授权") }
            }
            result?.let { Text(it.message.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        }
    }
    if (editing) {
        var path by rememberSaveable { mutableStateOf(settings.rootSuPath) }
        var mode by rememberSaveable { mutableStateOf(settings.rootSuMode) }
        val validated = remember(path) { runCatching { RootSuPath.normalize(path) } }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text("自定义 su 路径") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(),
                        label = { Text("su 可执行文件路径") }, placeholder = { Text("例如 /data/local/tmp/su") },
                        singleLine = true, isError = validated.isFailure,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        supportingText = { Text(validated.exceptionOrNull()?.message ?: "留空使用默认 su；只填写文件路径，不加引号或命令参数。") })
                    RootSuMode.entries.forEach { option ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = mode == option, onClick = { mode = option })
                            TextButton(onClick = { mode = option }) { Text(option.label) }
                        }
                    }
                    Text("自动检测先尝试 -c；自定义程序不支持时再尝试标准输入。若 -c 一直等待，请手动选择标准输入。程序仍需允许本应用执行。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { path = "" }) { Text("恢复默认路径") }
                }
            },
            confirmButton = { Button(onClick = { vm.setRootSuPath(validated.getOrThrow(), mode); editing = false },
                enabled = idle && !checking && !saving && validated.isSuccess) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
    }
}
