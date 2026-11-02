package com.mockgeo.core.engine

import com.mockgeo.core.model.CheckResult
import com.mockgeo.core.model.RootSuPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Captures one executable for a complete deployment; path is passed as argv[0]. */
internal class SuCommandRunner(
    path: String,
    private val start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) {
    private val executable = RootSuPath.executable(path)

    fun requireRoot() {
        val identity = run("id", 30_000)
        check(Regex("(?:^|\\s)uid=0(?:\\(|\\s|$)").containsMatchIn(identity)) {
            "所选 su 未返回 uid=0，请先在提权工具中授权 MockGeo"
        }
    }

    fun run(command: String, timeoutMs: Long = 5_000): String {
        val process = try { start(listOf(executable, "-c", command)) }
        catch (error: IOException) {
            throw IllegalStateException("无法启动所选 su，请检查路径、执行权限及系统访问限制", error)
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
        try {
            process.outputStream.close()
            check(process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { "su 执行超时，请确认提权工具已授权并支持 su -c" }
            reader.join(2_000)
            check(!reader.isAlive && readError.get() == null) { "无法读取 su 执行结果" }
            check(process.exitValue() == 0) { "su 执行失败（退出码 ${process.exitValue()}）：${output.get().take(1500)}" }
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
    suspend fun check(path: String): CheckResult = withContext(Dispatchers.IO) {
        val attempt = runCatching { SuCommandRunner(path).requireRoot() }
        result(attempt.exceptionOrNull())
    }

    internal fun result(error: Throwable?): CheckResult = CheckResult("Root 权限", error == null,
        if (error == null) "已获超级用户授权（uid=0）"
        else "${error.message ?: "Root 授权失败"}。可在偏好设置 → Root 授权中配置 su 路径", actionKey = "NONE")
}
