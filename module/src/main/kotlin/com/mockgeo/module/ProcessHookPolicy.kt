package com.mockgeo.module

/** Android application IDs are per user; shared system UIDs are never client hook targets. */
internal object ProcessHookPolicy {
    fun isApplicationUid(uid: Int): Boolean = uid >= 0 && uid % 100_000 in 10_000..19_999
    fun allowClientHooks(systemServer: Boolean, uid: Int, packageName: String): Boolean =
        !systemServer && isApplicationUid(uid) && packageName != "android" &&
            packageName != com.mockgeo.core.model.ControlPlane.APP_PACKAGE && packageName != "com.mockgeo.module"
}
