package com.mockgeo.core.engine

import android.content.Context
import android.os.Build
import com.mockgeo.core.model.CheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Deploys only bundled, hash-checked artifacts. Does not install an LSPosed/Magisk module. */
class RootDeployment(private val context: Context) : RootEnvironment {
    private val manifest by lazy {
        Properties().apply { context.assets.open("root/manifest.properties").use { load(it) } }
    }
    override val version: String get() = manifest.getProperty("version") ?: error("Root payload version missing")

    override suspend fun readiness(): List<CheckResult> = withContext(Dispatchers.IO) {
        val artifacts = runCatching { version.isNotBlank() && ARTIFACTS.all { manifest.getProperty(it)?.matches(HASH) == true } }.getOrDefault(false)
        val hasRoot = runCatching { requireRoot(); true }.getOrDefault(false)
        val runtimeCheck = runCatching {
            check(hasRoot) { "获得 Root 权限后才能检查系统环境" }
            val pid = root("pidof system_server").trim().toInt()
            requireIndependentRuntime(pid)
            true
        }
        val frameworkFree = runtimeCheck.getOrDefault(false)
        listOf(
            CheckResult("Root 权限", hasRoot, if (hasRoot) "已获超级用户授权" else "请在 KernelSU/Magisk 中授予 MockGeo Root 权限"),
            CheckResult("Root 独立注入", artifacts && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
                if (!artifacts) "安装包缺少 Root 注入组件" else if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a")
                    "当前 Root 直连仅支持 ARM64 设备" else "本地组件已就绪，尚未因此判定系统钩子已加载"),
            CheckResult("Root 独立注入", frameworkFree,
                if (frameworkFree) "系统未驻留 LSPosed 注入框架" else runtimeCheck.exceptionOrNull()?.message ?: "系统环境检查失败"),
        )
    }

    override suspend fun load(): RootReceipt = withContext(Dispatchers.IO) {
        require(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") { "Root loader requires ARM64" }
        requireRoot()
        val pid = root("pidof system_server").trim().toIntOrNull() ?: error("system_server PID unavailable")
        requireIndependentRuntime(pid)
        val startTicks = root("cat /proc/$pid/stat").substringAfterLast(") ").split(' ')[19]
        readReceipt()?.takeIf { it.optInt("pid") == pid && it.optString("startTicks") == startTicks }?.let {
            error("当前系统进程已有 Root 加载记录，但控制通道未就绪；请手动重启后重试")
        }

        val digest = manifest.getProperty("payload.apk").also { require(it?.matches(HASH) == true) }
        val staging = File(context.noBackupFilesDir, "root-stage/$digest").apply { mkdirs() }
        for (name in ARTIFACTS) {
            val file = File(staging, name)
            context.assets.open("root/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            check(sha256(file) == manifest.getProperty(name)) { "Root artifact hash mismatch: $name" }
        }
        val release = "$DIRECTORY/releases/$digest"
        val commands = mutableListOf(
            "set -e",
            "mkdir -p /data/misc/mockgeo $DIRECTORY $DIRECTORY/releases $release $STATE",
            "chown system:system /data/misc/mockgeo",
            "chmod 750 /data/misc/mockgeo",
            "chown root:system $DIRECTORY $DIRECTORY/releases $release",
            "chmod 750 $DIRECTORY $DIRECTORY/releases $release",
            "chown system:system $STATE",
            "chmod 750 $STATE",
            "if [ ! -e /data/misc/mockgeo/exempt.txt ]; then touch /data/misc/mockgeo/exempt.txt; chown system:system /data/misc/mockgeo/exempt.txt; chmod 640 /data/misc/mockgeo/exempt.txt; fi",
        )
        for (name in ARTIFACTS) {
            commands += "cp ${quote(File(staging, name).absolutePath)} ${quote("$release/$name") }"
            commands += "chown root:system ${quote("$release/$name") }"
            commands += "chmod ${if (name == "payload.apk") "440" else "550"} ${quote("$release/$name") }"
            commands += "echo ${quote(manifest.getProperty(name) + "  $release/$name")} | sha256sum -c"
        }
        commands += "if [ \"\$(getenforce)\" != Disabled ]; then chcon u:object_r:system_data_file:s0 /data/misc/mockgeo /data/misc/mockgeo/exempt.txt $DIRECTORY $DIRECTORY/releases $release $STATE; chcon u:object_r:system_file:s0 $release/payload.apk $release/libmockgeo_root.so $release/loader; fi"
        root(commands.joinToString("\n"), 15_000)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        // The loader has its own per-call deadlines and restores the traced thread before exiting.
        // The outer deadline is longer than all of those deadlines combined.
        root("$release/loader $pid $release/libmockgeo_root.so $release/payload.apk $nonce system", 90_000)
        var receipt: JSONObject? = null
        repeat(40) {
            if (receipt == null) {
                readReceipt()?.takeIf { it.optString("nonce") == nonce }?.let { receipt = it }
                if (receipt == null) delay(200)
            }
        }
        val result = checkNotNull(receipt) { "Root 载荷启动后没有系统回执" }
        check(result.optBoolean("ready") && result.optString("mode") == "system" && result.optString("backend") == "root" &&
            result.optInt("pid") == pid && result.optInt("uid") == 1000 && result.optString("startTicks") == startTicks &&
            result.optString("version") == version && root("pidof system_server").trim() == pid.toString()) {
            "Root 载荷未就绪：${result.optString("error", result.optString("stage", "identity mismatch"))}"
        }
        RootReceipt(pid, nonce, version)
    }

    private fun readReceipt(): JSONObject? = runCatching {
        val text = root("if [ -f $STATE/receipt.json ]; then cat $STATE/receipt.json; fi")
        if (text.isBlank()) null else JSONObject(text)
    }.getOrNull()

    private fun requireRoot() {
        check(Regex("(?:^|\\s)uid=0(?:\\(|\\s|$)").containsMatchIn(root("id"))) { "Root authorization denied" }
    }
    private fun requireIndependentRuntime(pid: Int) {
        val query = "BEGIN {found=0} /lsposed|\\/lspd\\// {found=1} END {print found}"
        check(root("awk ${quote(query)} /proc/$pid/maps").trim() == "0") { RootEngine.CONFLICT_REASON }
    }
    private fun root(command: String, timeoutMs: Long = 5_000): String {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = AtomicReference("")
        val reader = Thread({
            output.set(runCatching { process.inputStream.bufferedReader().use { it.readText().take(64 * 1024) } }.getOrDefault(""))
        }, "MockGeo-RootOutput").apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("Root command timed out")
        }
        reader.join(2000)
        check(process.exitValue() == 0) { "Root command failed: ${output.get().take(1500)}" }
        return output.get()
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        private const val DIRECTORY = "/data/misc/mockgeo/root"
        private const val STATE = "$DIRECTORY/state"
        private val ARTIFACTS = listOf("payload.apk", "libmockgeo_root.so", "loader")
        private val HASH = Regex("[0-9a-f]{64}")
        internal fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
