package com.akshayc.appgate.core.model

/**
 * Opaque profile identifier. At the Android boundary this is the profile's
 * serial number (`UserManager.getSerialNumberForUser` /
 * `getUserForSerialNumber`) — the only public identifier for an
 * `android.os.UserHandle` that stays stable across reboots and is safe to
 * persist. Declared as `Long` because that API is; this module stays free of
 * Android types.
 */
@JvmInline
value class UserId(
    val value: Long,
)

/**
 * An app the user chose to gate. Identity is (packageName, user) — a package
 * reinstalled in another profile (e.g. Private Space) is a different Target.
 */
data class Target(
    val packageName: String,
    val user: UserId,
)
