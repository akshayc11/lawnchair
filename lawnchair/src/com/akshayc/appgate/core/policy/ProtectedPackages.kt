package com.akshayc.appgate.core.policy

/**
 * Packages the engine never gates: dialer, emergency services, system UI,
 * launcher, Settings. Enforced here in the policy engine — hiding them in the
 * app picker is not enough (safety invariant 1 in CLAUDE.md).
 */
object ProtectedPackages {
    private val static =
        setOf(
            // Dialers
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.samsung.android.app.telephonyui",
            "com.android.phone",
            "com.android.server.telecom",
            // Emergency
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
            // System UI
            "com.android.systemui",
            // Settings
            "com.android.settings",
            "com.samsung.android.settings",
            // Launchers
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "net.oneplus.launcher",
        )

    /**
     * [runtimeProtected] carries packages only resolvable on device — the
     * current default launcher and dialer via RoleManager — injected as plain
     * strings so this module stays Android-free.
     */
    fun isProtected(
        packageName: String,
        runtimeProtected: Set<String> = emptySet(),
    ): Boolean = packageName in static || packageName in runtimeProtected
}
