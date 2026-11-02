package com.mockgeo.core.engine

import com.google.common.truth.Truth.assertThat
import com.mockgeo.core.model.RootSuMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class SuCommandRunnerTest {
    private class StubProcess(text: String, private val code: Int = 0, private val timeout: Boolean = false) : Process() {
        private val input = ByteArrayInputStream(text.toByteArray())
        val output = ByteArrayOutputStream()
        var alive = true
        var killed = false
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = output
        override fun waitFor(): Int { alive = false; return code }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { alive = this.timeout; return !this.timeout }
        override fun exitValue() = code
        override fun isAlive() = alive
        override fun destroy() { killed = true; alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun `custom path is one literal argument for every command`() {
        val calls = mutableListOf<List<String>>()
        val path = "/data/local/tmp/root 'file';echo injected"
        val runner = SuCommandRunner(path) { args -> calls += args; StubProcess("uid=0(root) gid=0(root)") }
        runner.requireRoot()
        runner.run("pidof system_server")
        runner.run("example loader command", 90_000)
        assertThat(calls).containsExactly(listOf(path, "-c", "id"), listOf(path, "-c", "pidof system_server"),
            listOf(path, "-c", "example loader command")).inOrder()
    }
    @Test fun `default executable remains su`() {
        var args = emptyList<String>()
        SuCommandRunner("") { args = it; StubProcess("uid=0(root)") }.requireRoot()
        assertThat(args).containsExactly("su", "-c", "id").inOrder()
    }
    @Test fun `zero exit without root identity is not authorization`() {
        for (identity in listOf("uid=10000(app)", "uid=01", "euid=0(root)", "")) {
            assertThrows<IllegalStateException> { SuCommandRunner("") { StubProcess(identity) }.requireRoot() }
        }
    }
    @Test fun `missing executable and denied authorization are actionable failures`() {
        val missing = assertThrows<IllegalStateException> {
            SuCommandRunner("/missing/su") { throw IOException("missing") }.requireRoot()
        }
        assertThat(missing.message).contains("无法启动所选 su")
        val denied = assertThrows<IllegalStateException> {
            SuCommandRunner("") { StubProcess("denied", 1) }.requireRoot()
        }
        assertThat(denied.message).contains("退出码 1")
        assertThat(RootAccess.result(denied).ok).isFalse()
    }
    @Test fun `timeout destroys the launched process`() {
        val process = StubProcess("", timeout = true)
        assertThrows<IllegalStateException> { SuCommandRunner("") { process }.run("id", 1) }
        assertThat(process.killed).isTrue()
    }
    @Test fun `output is drained but stored response is bounded`() {
        val answer = SuCommandRunner("") { StubProcess("x".repeat(200_000)) }.run("id")
        assertThat(answer.length).isEqualTo(64 * 1024)
    }

    @Test fun `custom stdin only executable is negotiated with id then used once per command`() {
        val calls = mutableListOf<List<String>>()
        val processes = mutableListOf<StubProcess>()
        val runner = SuCommandRunner("/custom/su") { args ->
            calls += args
            StubProcess(if (args.size == 1) "uid=0(root)" else "unsupported option", if (args.size == 1) 0 else 2)
                .also { processes += it }
        }
        runner.requireRoot()
        runner.run("echo test\nfalse")
        assertThat(calls).containsExactly(listOf("/custom/su", "-c", "id"), listOf("/custom/su"), listOf("/custom/su")).inOrder()
        assertThat(processes[1].output.toString("UTF-8")).isEqualTo("id\nexit \$?\n")
        assertThat(processes[2].output.toString("UTF-8")).isEqualTo("echo test\nfalse\nexit \$?\n")
        assertThat(runner.selectedMode).isEqualTo(RootSuMode.STDIN)
    }

    @Test fun `explicit stdin bypasses incompatible argument parser`() {
        val calls = mutableListOf<List<String>>()
        SuCommandRunner("/custom/su", RootSuMode.STDIN) { args ->
            calls += args; StubProcess("uid=0(root)")
        }.requireRoot()
        assertThat(calls).containsExactly(listOf("/custom/su"))
    }

    @Test fun `failed real commands and authorization timeout never trigger automatic replay`() {
        var calls = 0
        val runner = SuCommandRunner("/custom/su") { calls++; StubProcess("failed", 1) }
        assertThrows<IllegalStateException> { runner.run("payload loader") }
        assertThat(calls).isEqualTo(1)
        calls = 0
        assertThrows<IllegalStateException> {
            SuCommandRunner("/custom/su") { calls++; StubProcess("", timeout = true) }.requireRoot()
        }
        assertThat(calls).isEqualTo(1)
    }

    @Test fun `permission failure does not leak custom executable or repeat authorization`() {
        var calls = 0
        val error = assertThrows<IllegalStateException> {
            SuCommandRunner("/custom/secret-su") {
                calls++; throw IOException("Cannot run /custom/secret-su: error=13, Permission denied")
            }.requireRoot()
        }
        assertThat(calls).isEqualTo(1)
        assertThat(error.message).contains("系统禁止")
        assertThat(error.toString()).doesNotContain("secret-su")
    }
}
