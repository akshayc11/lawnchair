package com.akshayc.appgate.core.data.db

import com.akshayc.appgate.core.model.AuthChallenge
import com.akshayc.appgate.core.model.Budget
import com.akshayc.appgate.core.model.FrictionChallenge
import com.akshayc.appgate.core.model.Gate
import com.akshayc.appgate.core.model.GateConfig
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.Tier
import com.akshayc.appgate.core.model.UserId
import java.time.Duration

/**
 * Pure mapping between [GateEntity] and the domain [Gate]. No Android types,
 * so it is unit tested on the JVM.
 */
internal fun Gate.toEntity(): GateEntity {
    val budget = config.budget
    return GateEntity(
        packageName = target.packageName,
        userId = target.user.value,
        tier = config.tier.name,
        frictionChallenge = config.frictionChallenge?.name,
        authChallenge = config.authChallenge?.name,
        graceSeconds = config.grace.seconds,
        budgetKind =
            when (budget) {
                is Budget.SessionCount -> BudgetKind.SESSION_COUNT
                is Budget.TimeBudget -> BudgetKind.TIME
                is Budget.DailyTime -> BudgetKind.DAILY_TIME
                null -> null
            },
        budgetMaxSessions = (budget as? Budget.SessionCount)?.maxSessions,
        budgetMaxTotalSeconds =
            when (budget) {
                is Budget.TimeBudget -> budget.maxTotal.seconds
                is Budget.DailyTime -> budget.maxTotal.seconds
                else -> null
            },
        budgetPeriodSeconds = budget?.period?.seconds,
        enabled = enabled,
        budgetResetHour = (budget as? Budget.DailyTime)?.resetHour,
        escalation = config.escalation,
    )
}

/**
 * Returns null when the row cannot produce a valid [Gate] — unknown enum name
 * from a downgrade, or a column combination [GateConfig] rejects. A single bad
 * row is dropped rather than crashing the whole gate list (fail open, per
 * safety invariant 2); [Tier.LOCKED] rows are no exception, because a row we
 * cannot parse has no trustworthy tier to enforce.
 */
internal fun GateEntity.toGateOrNull(): Gate? {
    val tier = enumOrNull<Tier>(tier) ?: return null
    val friction = frictionChallenge?.let { enumOrNull<FrictionChallenge>(it) ?: return null }
    val auth = authChallenge?.let { enumOrNull<AuthChallenge>(it) ?: return null }
    val budget = toBudgetOrNull()
    if (budgetKind != null && budget == null) return null

    val config =
        runCatching {
            GateConfig(
                tier = tier,
                frictionChallenge = friction,
                authChallenge = auth,
                grace = Duration.ofSeconds(graceSeconds),
                budget = budget,
                escalation = escalation == true,
            )
        }.getOrNull() ?: return null

    return Gate(
        target = Target(packageName = packageName, user = UserId(userId)),
        config = config,
        enabled = enabled,
    )
}

private fun GateEntity.toBudgetOrNull(): Budget? {
    val period = budgetPeriodSeconds?.let(Duration::ofSeconds) ?: return null
    return runCatching {
        when (budgetKind) {
            BudgetKind.SESSION_COUNT ->
                budgetMaxSessions?.let { Budget.SessionCount(maxSessions = it, period = period) }

            BudgetKind.TIME ->
                budgetMaxTotalSeconds?.let {
                    Budget.TimeBudget(maxTotal = Duration.ofSeconds(it), period = period)
                }

            BudgetKind.DAILY_TIME -> {
                val resetHour = budgetResetHour
                val maxTotal = budgetMaxTotalSeconds
                if (resetHour == null || maxTotal == null) {
                    null
                } else {
                    Budget.DailyTime(maxTotal = Duration.ofSeconds(maxTotal), resetHour = resetHour)
                }
            }

            else -> null
        }
    }.getOrNull()
}

private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
