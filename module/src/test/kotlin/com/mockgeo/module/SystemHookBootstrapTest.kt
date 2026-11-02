package com.mockgeo.module

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SystemHookBootstrapTest {
    private val loader = object : ClassLoader() {}

    @Test fun `missing early callback still installs after local service publication`() {
        var local: ClassLoader? = null
        var worker: (() -> Unit)? = null
        var waits = 0
        var installs = 0
        val bootstrap = SystemHookBootstrap({ local }, { installs++; assertThat(it).isSameInstanceAs(loader); true },
            { worker = it }, { _, _ -> }, { if (++waits == 2) local = loader })
        bootstrap.discoverWhenReady()
        worker!!()
        assertThat(installs).isEqualTo(1)
        assertThat(waits).isEqualTo(2)
    }

    @Test fun `remote service or no local service never authorizes package fallback hooks`() {
        var worker: (() -> Unit)? = null
        var elapsed = 0L
        val reports = mutableListOf<String>()
        val bootstrap = SystemHookBootstrap({ null }, { error("must not hook") }, { worker = it },
            { message, _ -> reports += message }, { elapsed += it })
        bootstrap.discoverWhenReady()
        worker!!()
        assertThat(elapsed).isEqualTo(65_000)
        assertThat(reports.single()).contains("exhausted")
    }

    @Test fun `early callback and later local discovery share one bounded worker`() {
        var local: ClassLoader? = null
        val scheduled = mutableListOf<() -> Unit>()
        var installs = 0
        val bootstrap = SystemHookBootstrap({ local }, { installs++; true }, { scheduled += it }, { _, _ -> }, {})
        bootstrap.discoverWhenReady()
        bootstrap.onSystemServerStarting(loader)
        bootstrap.discoverWhenReady()
        assertThat(installs).isEqualTo(1)
        assertThat(scheduled).hasSize(1)
        local = loader
        scheduled.single()()
        bootstrap.onSystemServerStarting(loader)
        assertThat(installs).isEqualTo(2)
        assertThat(scheduled).hasSize(1)
    }

    @Test fun `failed install retries until ready instead of disabling discovery`() {
        var worker: (() -> Unit)? = null
        var installs = 0
        val failures = mutableListOf<Throwable?>()
        val bootstrap = SystemHookBootstrap({ loader }, { if (++installs == 1) error("fixture"); true },
            { worker = it }, { _, error -> failures += error }, {})
        bootstrap.discoverWhenReady()
        worker!!()
        assertThat(installs).isEqualTo(2)
        assertThat(failures.single()?.message).isEqualTo("fixture")
    }

    @Test fun `system UID vendor names can discover local service without becoming client targets`() {
        assertThat(SystemHookBootstrap.isSystemHostCandidate(false, 1000, "com.hihonor.systemserver")).isTrue()
        assertThat(SystemHookBootstrap.isSystemHostCandidate(false, 10123, "com.hihonor.systemserver")).isFalse()
        assertThat(SystemHookBootstrap.isSystemHostCandidate(false, 1000, "unrelated.app")).isTrue()
        assertThat(SystemHookBootstrap.isSystemHostCandidate(true, 1000, "android")).isTrue()
    }

    @Test fun `client hooks never enter shared system isolated or SDK sandbox processes`() {
        for (uid in listOf(0, 1000, 1001, 101000, 20000, 99000, 199000)) {
            assertThat(ProcessHookPolicy.allowClientHooks(false, uid, "com.vendor.service")).isFalse()
        }
        assertThat(ProcessHookPolicy.allowClientHooks(true, 10123, "com.test.app")).isFalse()
        assertThat(ProcessHookPolicy.allowClientHooks(false, 10123, "com.test.app")).isTrue()
        assertThat(ProcessHookPolicy.allowClientHooks(false, 110123, "com.test.app")).isTrue()
    }

    @Test fun `framework log failures cannot interrupt system hooks and debug does not flood manager`() {
        val messages = mutableListOf<String>()
        ModuleLog.bindFramework { _, message, _ -> messages += message; error("fixture logger unavailable") }
        try {
            ModuleLog.d("per-frame debug")
            ModuleLog.i("startup")
            ModuleLog.w("receipt failure")
            ModuleLog.e("install error", IllegalStateException("fixture"))
            assertThat(messages).containsExactly("startup", "receipt failure", "install error").inOrder()
        } finally { ModuleLog.bindFramework(null) }
    }
}
