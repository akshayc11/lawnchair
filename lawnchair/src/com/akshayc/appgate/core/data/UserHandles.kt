package com.akshayc.appgate.core.data

import android.os.UserHandle
import android.os.UserManager
import com.akshayc.appgate.core.model.UserId

/**
 * Translation between `android.os.UserHandle` and the Android-free [UserId]
 * used in `:core:model`.
 *
 * The serial number is the only public identifier for a profile that survives
 * a reboot, so it — not the `@hide` user id — is what gets persisted.
 */
fun UserManager.toUserId(user: UserHandle): UserId = UserId(getSerialNumberForUser(user))

/**
 * Returns null when no profile currently carries this serial: the profile was
 * removed, or (Private Space) is not visible to us right now. Callers treat
 * that as "cannot resolve", never as "main profile".
 */
fun UserManager.toUserHandleOrNull(userId: UserId): UserHandle? = getUserForSerialNumber(userId.value)
