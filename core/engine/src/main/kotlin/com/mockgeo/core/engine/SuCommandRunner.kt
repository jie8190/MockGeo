package com.mockgeo.core.engine

import com.mockgeo.core.model.CheckResult
import com.mockgeo.core.model.RootSuPath
import com.mockgeo.core.model.RootSuMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Captures one executable for a complete deployment; path is passed as argv[0]. */
internal class SuCommandRunner(
    path: String,
    private val mode: RootSuMode = RootSuMode.AUTO,
    private val start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) {
    private val executable = RootSuPath.executable(path)
    private var selected = if (mode == RootSuMode.AUTO) RootSuMode.COMMAND else mode
    val selectedMode: RootSuMode get() = selected

    private class LaunchFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
    private class TimedOut : IllegalStateException("su 执行超时，请确认授权；若工具不支持 -c，可在设置中选择标准输入")

    fun requireRoot() {
        try {
            checkIdentity()
        } catch (first: IllegalStateException) {
            // Negotiate with read-only `id` only. Never retry a deployment or injection.
            // Missing/blocked executables and authorization timeouts are not protocol mismatches.
            if (mode != RootSuMode.AUTO || executable == "su" || first is LaunchFailure || first is TimedOut) throw first
            selected = RootSuMode.STDIN
            try { checkIdentity() } catch (second: IllegalStateException) {
                throw IllegalStateException("-c 方式失败：${first.message}；标准输入方式失败：${second.message}")
            }
        }
    }

    private fun checkIdentity() {
        val identity = run("id", 30_000)
        check(Regex("(?m)^uid=0(?:\\(|\\s|$)").containsMatchIn(identity)) {
            "所选 su 未返回 uid=0，请先在提权工具中授权 MockGeo"
        }
    }

    fun run(command: String, timeoutMs: Long = 5_000): String {
        val stdin = selected == RootSuMode.STDIN
        val process = try { start(if (stdin) listOf(executable) else listOf(executable, "-c", command)) }
        catch (error: IOException) {
            val detail = when {
                error.message?.contains("error=13") == true -> "文件无执行权限或系统禁止应用访问该路径"
                error.message?.contains("error=2") == true -> "文件不存在，或它的解释器/动态链接器不存在"
                error.message?.contains("error=8") == true -> "可执行文件格式或 CPU 架构不受支持"
                else -> "请检查路径、执行权限及系统访问限制"
            }
            throw LaunchFailure("无法启动所选 su：$detail")
        }
        val output = AtomicReference("")
        val readError = AtomicReference<Exception?>(null)
        val reader = Thread({
            try {
                process.inputStream.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val keep = minOf(count, (64 * 1024 - bytes.size()).coerceAtLeast(0))
                        bytes.write(buffer, 0, keep)
                    }
                    output.set(bytes.toString("UTF-8"))
                }
            } catch (error: Exception) { readError.set(error) }
        }, "MockGeo-RootOutput").apply { isDaemon = true; start() }
        val writeError = AtomicReference<Exception?>(null)
        // A shell that never reads stdin must not block the caller before the deadline starts.
        val writer = Thread({
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    if (stdin) it.write("$command\nexit \$?\n")
                }
            } catch (error: Exception) { writeError.set(error) }
        }, "MockGeo-RootInput").apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw TimedOut()
            writer.join(2_000)
            reader.join(2_000)
            check(!reader.isAlive && readError.get() == null) { "无法读取 su 执行结果" }
            check(process.exitValue() == 0) { "su 执行失败（退出码 ${process.exitValue()}）：${output.get().replace(executable, "[su]").take(1500)}" }
            check(!writer.isAlive && writeError.get() == null) { "无法向 su 写入命令，请确认工具支持所选调用方式" }
            return output.get()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}

/** Only runs `id`; does not deploy a payload or change system settings. */
object RootAccess {
    suspend fun check(path: String, mode: RootSuMode = RootSuMode.AUTO): CheckResult = withContext(Dispatchers.IO) {
        var selected: RootSuMode? = null
        val attempt = runCatching {
            val runner = SuCommandRunner(path, mode)
            runner.requireRoot()
            selected = runner.selectedMode
        }
        val check = result(attempt.exceptionOrNull())
        if (attempt.isSuccess) check.copy(message = "已获超级用户授权（uid=0，${selected?.label}）") else check
    }

    internal fun result(error: Throwable?): CheckResult = CheckResult("Root 权限", error == null,
        if (error == null) "已获超级用户授权（uid=0）"
        else "${error.message ?: "Root 授权失败"}。可在偏好设置 → Root 授权中配置 su 路径", actionKey = "NONE")
}
