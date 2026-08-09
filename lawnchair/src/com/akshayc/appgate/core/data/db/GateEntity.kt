package com.akshayc.appgate.core.data.db

import androidx.room.Entity

/**
 * Persisted form of a [com.akshayc.appgate.core.model.Gate].
 *
 * Deliberately flat and stringly-typed at the column level: enums are stored
 * by name and the [com.akshayc.appgate.core.model.Budget] variants are spread
 * across nullable columns, so a future enum addition is a data migration
 * rather than a type-converter puzzle. Primary key is
 * `(packageName, userId)` — Target identity, never bare package name.
 */
@Entity(tableName = "gates", primaryKeys = ["packageName", "userId"])
data class GateEntity(
    val packageName: String,
    val userId: Long,
    val tier: String,
    val frictionChallenge: String?,
    val authChallenge: String?,
    val graceSeconds: Long,
    val budgetKind: String?,
    val budgetMaxSessions: Int?,
    val budgetMaxTotalSeconds: Long?,
    val budgetPeriodSeconds: Long?,
    val enabled: Boolean,
    /** Hour the daily allowance refills at; only set for [BudgetKind.DAILY_TIME]. */
    val budgetResetHour: Int? = null,
)

/** Discriminator values for [GateEntity.budgetKind]. */
internal object BudgetKind {
    const val SESSION_COUNT = "SESSION_COUNT"
    const val TIME = "TIME"
    const val DAILY_TIME = "DAILY_TIME"
}
