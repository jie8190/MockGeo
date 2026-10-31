package com.mockgeo.module

import com.mockgeo.core.model.ControlPlane

/** Process-local identity; the Root payload and LSPosed have distinct command providers. */
object ModuleRuntime {
    var backend: String = "xposed"
        private set
    var provider: String = ControlPlane.PROVIDER
        private set
    var version: String = StatusWriter.MODULE_VERSION
        private set
    var instanceNonce: String = ""
        private set
    @Volatile var responseNonce: String = ""

    fun activateRoot(version: String, nonce: String) {
        check(HookInstaller.installedCount == 0 && backend == "xposed")
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        backend = "root"
        provider = ControlPlane.ROOT_PROVIDER
        this.version = version
        instanceNonce = nonce
    }
}
