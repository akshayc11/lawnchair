package app.lawnchair.appgate

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import android.telecom.TelecomManager
import androidx.room.Room
import app.lawnchair.util.MainThreadInitializedObject
import com.akshayc.appgate.core.data.GateRepository
import com.akshayc.appgate.core.data.RoomGateRepository
import com.akshayc.appgate.core.data.RoomSessionRepository
import com.akshayc.appgate.core.data.SessionRepository
import com.akshayc.appgate.core.data.db.AppGateDatabase
import com.akshayc.appgate.core.data.db.MIGRATION_1_2
import com.akshayc.appgate.core.data.db.MIGRATION_2_3
import com.akshayc.appgate.core.data.toUserHandleOrNull
import com.akshayc.appgate.core.data.toUserId
import com.akshayc.appgate.core.model.Budget
import com.akshayc.appgate.core.model.Gate
import com.akshayc.appgate.core.model.GateDecision
import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.Tier
import com.akshayc.appgate.core.policy.EvaluationInput
import com.akshayc.appgate.core.policy.GatePolicyEngine
import com.akshayc.appgate.core.policy.ProtectedPackages
import com.akshayc.appgate.core.policy.dayStart
import com.akshayc.appgate.core.policy.isBudgetExhausted
import com.akshayc.appgate.core.policy.timeUsedSince
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Process-wide owner of AppGate's state inside the launcher.
 *
 * Lawnchair has no app-level Hilt, so the Room database and repositories are
 * constructed by hand here and reached through [MainThreadInitializedObject] —
 * the same singleton idiom `PreferenceManager2.getInstance(context)` uses.
 *
 * Both the icon draw path and the launch path read in-memory snapshots kept
 * hot by collecting the repositories' Flows, so neither `BubbleTextView` nor
 * `startActivitySafely` ever touches the database.
 */
class AppGate(private val context: Context) {

    // Deliberately not the main dispatcher: evaluate() may briefly block the
    // main thread waiting for the first snapshot, and these collectors are what
    // it is waiting for.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val clock: Clock = Clock.systemDefaultZone()

    private val database = Room
        .databaseBuilder(context.applicationContext, AppGateDatabase::class.java, DB_NAME)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

    val repository: GateRepository = RoomGateRepository(database.gateDao())
    val sessionRepository: SessionRepository = RoomSessionRepository(database.sessionDao())

    private val policyEngine = GatePolicyEngine(clock)

    private val _gates = MutableStateFlow<List<Gate>>(emptyList())
    private val _gatedTiers = MutableStateFlow<Map<GateKey, Tier>>(emptyMap())
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())

    /** Enabled gates only, keyed by (packageName, profile). Read on the draw path. */
    val gatedTiers: StateFlow<Map<GateKey, Tier>> = _gatedTiers.asStateFlow()

    private val gatesLoaded = CompletableDeferred<Unit>()
    private val sessionsLoaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            repository.observeGates().collect { gates ->
                _gates.value = gates
                recomputeTiers()
                gatesLoaded.complete(Unit)
            }
        }
        scope.launch {
            sessionRepository.observeRecentSessions().collect { sessions ->
                _sessions.value = sessions
                recomputeTiers()
                sessionsLoaded.complete(Unit)
            }
        }
        scope.launch {
            sessionRepository.pruneBefore(clock.instant().minus(SESSION_HISTORY))
        }
    }

    /**
     * The Tier shown on the icon. A gate whose daily allowance is spent reads
     * as LOCKED here, because that is what it behaves like until the allowance
     * refills — the indicator should not claim otherwise.
     */
    private fun recomputeTiers() {
        val now = clock.instant()
        _gatedTiers.value = _gates.value
            .filter { it.enabled }
            .mapNotNull { gate ->
                // A profile that no longer resolves (removed, or a Private
                // Space we cannot see right now) is skipped rather than being
                // treated as the main profile.
                val user = userManager.toUserHandleOrNull(gate.target.user) ?: return@mapNotNull null
                val tier = if (isAllowanceSpent(gate, now)) Tier.LOCKED else gate.config.tier
                GateKey(gate.target.packageName, user) to tier
            }.toMap()
    }

    private fun isAllowanceSpent(gate: Gate, now: Instant): Boolean {
        val budget = gate.config.budget ?: return false
        val forTarget = _sessions.value.filter { it.target == gate.target }
        return isBudgetExhausted(
            budget = budget,
            recentSessions = forTarget.filterNot { it.isActiveAt(now) },
            activeSession = forTarget.firstOrNull { it.isActiveAt(now) },
            now = now,
            zone = clock.zone,
        )
    }

    /** The Tier gating this app right now, or null if it is not gated. */
    fun tierFor(packageName: String, user: UserHandle): Tier? =
        _gatedTiers.value[GateKey(packageName, user)]

    fun gateFor(gates: List<Gate>, target: Target): Gate? = gates.firstOrNull { it.target == target }

    fun targetFor(packageName: String, user: UserHandle): Target =
        Target(packageName = packageName, user = userManager.toUserId(user))

    /**
     * Decides one Interception, synchronously, from the hot snapshots.
     *
     * On a cold launcher start the snapshots may not have arrived yet; rather
     * than let a fast first tap slip past a gate, this waits briefly for the
     * first emission. The wait is bounded — if the database is somehow slow,
     * evaluation proceeds on what is loaded and the engine's fail-open rule
     * applies (safety invariant 2).
     */
    fun evaluate(packageName: String, user: UserHandle): GateDecision {
        awaitSnapshots()
        val target = targetFor(packageName, user)
        val now = clock.instant()
        val forTarget = _sessions.value.filter { it.target == target }
        return policyEngine.evaluate(
            EvaluationInput(
                target = target,
                gate = gateFor(_gates.value, target),
                activeSession = forTarget.firstOrNull { it.isActiveAt(now) },
                recentSessions = forTarget.filterNot { it.isActiveAt(now) },
                runtimeProtectedPackages = runtimeProtectedPackages(),
            ),
        )
    }

    fun gateFor(packageName: String, user: UserHandle): Gate? =
        gateFor(_gates.value, targetFor(packageName, user))

    /**
     * Records the access grant that follows a passed Challenge and returns it.
     * The row is written in the background; the returned Session is what the
     * caller needs straight away (its end time schedules the warning).
     */
    fun startSession(
        target: Target,
        length: Duration,
        intentText: String? = null,
    ): Session {
        val startedAt = clock.instant()
        val granted = clampToAllowance(target, length, startedAt)
        val session = Session(target = target, startedAt = startedAt, endsAt = startedAt.plus(granted))
        scope.launch {
            sessionRepository.startSession(
                session = session,
                intentText = intentText?.takeIf { it.isNotBlank() },
            )
        }
        return session
    }

    /**
     * A daily allowance is a cap on time *in* the app, so the grant cannot be
     * longer than what is left of it — otherwise 29 minutes used against a
     * 30-minute allowance would still buy a full session.
     *
     * The floor keeps a boundary race from producing a zero-length session: the
     * policy engine would already have denied the open if nothing were left, so
     * anything reaching here is entitled to at least a minute.
     */
    private fun clampToAllowance(target: Target, length: Duration, now: Instant): Duration {
        val budget = gateFor(_gates.value, target)?.config?.budget as? Budget.DailyTime ?: return length
        val used = timeUsedSince(
            sessions = _sessions.value.filter { it.target == target },
            windowStart = dayStart(now, clock.zone, budget.resetHour),
            now = now,
        )
        val remaining = budget.maxTotal.minus(used)
        return minOf(length, remaining).coerceAtLeast(MIN_SESSION)
    }

    /**
     * The launcher being on screen means the user is not inside a gated app, so
     * any running Session is over: coming back has to pass the Gate again.
     *
     * Sessions younger than [HANDOFF_GRACE] are left alone — the launcher is
     * briefly resumed while handing off to the app it has just launched, and
     * that must not end the Session it was launched for.
     */
    fun onLauncherResumed() {
        val now = clock.instant()
        scope.launch {
            sessionRepository.endActiveSessions(at = now, startedBefore = now.minus(HANDOFF_GRACE))
        }
        // A warning for a Session that is over would be noise.
        _gates.value.forEach { GateNotifications.cancelSessionWarning(context, it.target) }
        // Time has passed since the last emission; an allowance may have run
        // out or refilled, and the icons should say so.
        recomputeTiers()
    }

    fun hasActiveSession(target: Target): Boolean {
        awaitSnapshots()
        val now = clock.instant()
        return _sessions.value.any { it.target == target && it.isActiveAt(now) }
    }

    private fun awaitSnapshots() {
        if (gatesLoaded.isCompleted && sessionsLoaded.isCompleted) return
        runBlocking {
            withTimeoutOrNull(SNAPSHOT_WAIT_MILLIS) {
                gatesLoaded.await()
                sessionsLoaded.await()
            }
        }
    }

    /**
     * Safety invariant 1: dialer, emergency services, system UI, Settings and
     * the launcher are never gateable. Enforced here rather than by hiding the
     * menu item, and it includes our own package — AppGate is the launcher now,
     * so gating it would be gating home.
     */
    fun isGateable(packageName: String): Boolean =
        !ProtectedPackages.isProtected(packageName, runtimeProtectedPackages())

    private fun runtimeProtectedPackages(): Set<String> = buildSet {
        add(context.packageName)

        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        home?.activityInfo?.packageName?.let(::add)

        context.getSystemService(TelecomManager::class.java)
            ?.let { runCatching { it.defaultDialerPackage }.getOrNull() }
            ?.let(::add)
    }

    data class GateKey(val packageName: String, val user: UserHandle)

    companion object {
        private const val DB_NAME = "appgate.db"
        private const val SNAPSHOT_WAIT_MILLIS = 400L
        private val SESSION_HISTORY: Duration = Duration.ofDays(7)
        private val HANDOFF_GRACE: Duration = Duration.ofSeconds(10)
        private val MIN_SESSION: Duration = Duration.ofMinutes(1)

        @JvmField
        val INSTANCE = MainThreadInitializedObject(::AppGate)

        @JvmStatic
        fun getInstance(context: Context): AppGate = INSTANCE.get(context)
    }
}

/** Convenience for the launch path, which only cares whether a gate exists at all. */
fun AppGate.isGated(packageName: String, user: UserHandle): Boolean = tierFor(packageName, user) != null
