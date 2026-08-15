package app.lawnchair.appgate

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import androidx.room.Room
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.MainThreadInitializedObject
import com.akshayc.appgate.core.data.GateRepository
import com.akshayc.appgate.core.data.RoomGateRepository
import com.akshayc.appgate.core.data.RoomSessionRepository
import com.akshayc.appgate.core.data.SessionRepository
import com.akshayc.appgate.core.data.db.AppGateDatabase
import com.akshayc.appgate.core.data.db.MIGRATION_1_2
import com.akshayc.appgate.core.data.db.MIGRATION_2_3
import com.akshayc.appgate.core.data.db.MIGRATION_3_4
import com.akshayc.appgate.core.data.db.MIGRATION_4_5
import com.akshayc.appgate.core.data.db.MIGRATION_5_6
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

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
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
        .build()

    val repository: GateRepository = RoomGateRepository(database.gateDao())
    val sessionRepository: SessionRepository = RoomSessionRepository(database.sessionDao())

    private val policyEngine = GatePolicyEngine(clock)

    // Starts on, matching the preference's own default, so a gate is never
    // missed in the moment before the first value arrives.
    private val _enabled = MutableStateFlow(true)

    private val _gates = MutableStateFlow<List<Gate>>(emptyList())
    private val _badges = MutableStateFlow<Map<GateKey, GateBadgeState>>(emptyMap())
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())

    /** Enabled gates only, keyed by (packageName, profile). Read on the draw path. */
    val badges: StateFlow<Map<GateKey, GateBadgeState>> = _badges.asStateFlow()

    /** Configured Gates, enabled or not. What the stats screens list. */
    val gates: StateFlow<List<Gate>> = _gates.asStateFlow()

    /** Recent Sessions, the week the database keeps. What the stats screens count. */
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    /** The zone day boundaries are resolved in, so screens agree with the engine. */
    val zone: ZoneId get() = clock.zone

    fun now(): Instant = clock.instant()

    private val gatesLoaded = CompletableDeferred<Unit>()
    private val sessionsLoaded = CompletableDeferred<Unit>()

    /** Fires when the running Session that ends soonest runs out. */
    private var watchdog: Job? = null

    /**
     * Expiries already acted on, so watchdog and alarm cannot both take over.
     * Concurrent because the two reach it from different threads.
     */
    private val enforced: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var pendingTakeover: Target? = null

    /**
     * Screen off, screen on and unlock, while there is a Session to keep time
     * for. One receiver for all three: the clock stopping and starting and a
     * deferred takeover all hang off the same moments, and two receivers racing
     * over the same unlock would be a bug waiting to happen.
     */
    private var screenReceiver: BroadcastReceiver? = null

    /** Notices the user switching to another app, while a Session is running. */
    private var foregroundPoll: Job? = null

    /**
     * Sessions granted in this process, by start time. The row is written
     * asynchronously, so during the handoff to the app being launched this is
     * the only place that knows the Session exists yet.
     */
    private val grantedSessions = ConcurrentHashMap<Target, Instant>()

    init {
        scope.launch {
            repository.observeGates().collect { gates ->
                _gates.value = gates
                recomputeBadges()
                gatesLoaded.complete(Unit)
            }
        }
        scope.launch {
            sessionRepository.observeRecentSessions().collect { sessions ->
                _sessions.value = sessions
                recomputeBadges()
                sessionsLoaded.complete(Unit)
                rescheduleWatchdog()
            }
        }
        scope.launch {
            sessionRepository.pruneBefore(clock.instant().minus(SESSION_HISTORY))
        }
        scope.launch {
            PreferenceManager2.getInstance(context).enableAppGate.get().collect { enabled ->
                _enabled.value = enabled
                // Indicators go with it: the badges Flow is what repaints icons.
                recomputeBadges()
            }
        }
    }

    /**
     * Whether gating is switched on at all. Off is a real off — no Gate is
     * raised, no indicator is drawn — but nothing is forgotten: the Gates stay
     * configured and start working again when it is switched back on.
     */
    val isEnabled: Boolean get() = _enabled.value

    /**
     * What the icon should say. A gate whose daily allowance is spent reads as
     * LOCKED here, because that is what it behaves like until the allowance
     * refills — the indicator should not claim otherwise.
     *
     * Recomputed whenever gates or sessions change and on every launcher
     * resume, which is also what keeps [GateBadgeState.allowanceMinutesLeft]
     * current: minutes tick down while the user is inside the app, but the
     * icon is not on screen then, and it is refreshed by the time it is.
     */
    private fun recomputeBadges() {
        if (!_enabled.value) {
            _badges.value = emptyMap()
            return
        }
        val now = clock.instant()
        _badges.value = _gates.value
            .filter { it.enabled }
            .mapNotNull { gate ->
                // A profile that no longer resolves (removed, or a Private
                // Space we cannot see right now) is skipped rather than being
                // treated as the main profile.
                val user = userManager.toUserHandleOrNull(gate.target.user) ?: return@mapNotNull null
                val minutesLeft = allowanceMinutesLeft(gate, now)
                val state = GateBadgeState(
                    tier = if (minutesLeft == 0) Tier.LOCKED else gate.config.tier,
                    allowanceMinutesLeft = minutesLeft,
                )
                GateKey(gate.target.packageName, user) to state
            }.toMap()
    }

    /**
     * Whole minutes left of today's allowance, or null when this gate has no
     * daily allowance. Rounded up, so it only reads zero once the allowance is
     * genuinely gone — and zero is exactly when the policy engine starts
     * denying, so the number and the behaviour agree.
     */
    private fun allowanceMinutesLeft(gate: Gate, now: Instant): Int? {
        val budget = gate.config.budget as? Budget.DailyTime ?: return null
        if (isAllowanceSpent(gate, now)) return 0
        val used = timeUsedSince(
            sessions = _sessions.value.filter { it.target == gate.target },
            windowStart = dayStart(now, clock.zone, budget.resetHour),
            now = now,
        )
        val secondsLeft = budget.maxTotal.minus(used).seconds
        if (secondsLeft <= 0) return 0
        return ((secondsLeft + 59) / 60).toInt()
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

    /** What to draw on this app's icon right now, or null if it is not gated. */
    fun badgeFor(packageName: String, user: UserHandle): GateBadgeState? =
        _badges.value[GateKey(packageName, user)]

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
        if (!_enabled.value) return GateDecision.Allow
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
        grantedSessions[target] = startedAt
        // The in-process watchdog handles the common case; the alarm is what is
        // left if the launcher is killed while the user is inside the app.
        SessionExpiryAlarm.schedule(context, target, session.endsAt)
        // Straight away rather than waiting for the row to come back through the
        // snapshot: the screen can go off in that gap, and that time should not
        // be charged for either.
        registerScreenReceiver()
        startForegroundPoll()
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
        // A warning, or a takeover, for a Session that is over would be noise —
        // but not for the Session that is being handed off to right now, whose
        // alarms are the only thing left if this process is killed inside it.
        val youngest = now.minus(HANDOFF_GRACE)
        val handedOff = _sessions.value
            .filter { it.isActiveAt(now) && it.startedAt.isAfter(youngest) }
            .map { it.target }
            .toSet() +
            // The Session just granted may not have reached a snapshot yet, and
            // its alarms are the ones that must survive this resume.
            grantedSessions.filterValues { it.isAfter(youngest) }.keys
        _gates.value.map { it.target }.filterNot { it in handedOff }.forEach { target ->
            GateNotifications.cancelSessionWarning(context, target)
            SessionExpiryAlarm.cancel(context, target)
        }
        // Time has passed since the last emission; an allowance may have run
        // out or refilled, and the icons should say so.
        recomputeBadges()
    }

    /**
     * Watches for the running Session that ends soonest and enforces it when it
     * does. Rescheduled on every emission, so a Session ended early — the user
     * went home — simply drops out of the schedule.
     */
    private fun rescheduleWatchdog() {
        val now = clock.instant()
        syncSessionWatchers(now)
        SessionExpiryRules.justExpired(_sessions.value, now).forEach { enforce(it) }
        val next = SessionExpiryRules.nextExpiryAt(_sessions.value, now)
        watchdog?.cancel()
        watchdog = null
        if (next == null) return
        watchdog = scope.launch {
            delay(Duration.between(now, next).toMillis().coerceAtLeast(0))
            rescheduleWatchdog()
        }
    }

    /**
     * Called by [SessionExpiryReceiver] for an expiry the launcher process was
     * not alive to watch. The alarm is inexact and may arrive late or after the
     * user has already left, so what it does is decided from the same snapshot
     * as the watchdog's.
     *
     * The wait is much longer than [SNAPSHOT_WAIT_MILLIS] on purpose: this is
     * the cold-start path, where the database is being opened and migrated for
     * the first time, and giving up early would drop the expiry entirely.
     * [onDone] releases the broadcast.
     *
     * A late alarm is still worth acting on — [takeOver] is what decides
     * whether the user is still in the app — so no age limit is applied here.
     */
    fun enforceExpiry(target: Target, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                withTimeoutOrNull(COLD_START_WAIT_MILLIS) {
                    gatesLoaded.await()
                    sessionsLoaded.await()
                }
                val now = clock.instant()
                SessionExpiryRules.expired(_sessions.value, target, now)?.let { enforce(it) }
            } finally {
                onDone()
            }
        }
    }

    /**
     * One expired Session. The accounting is already right without a write —
     * a Session past its planned end is over as far as every reader is
     * concerned — so this is about telling the user, which has to happen at
     * most once per expiry however many alarms and watchdogs notice it.
     */
    private fun enforce(session: Session) {
        val now = clock.instant()
        // Its planned end has passed, but its clock was stopped: the user was
        // away, so this is time to give back rather than an expiry to enforce.
        if (!SessionPauseRules.openPause(session, now).isZero) {
            scope.launch { reconcilePaused(session, now) }
            return
        }
        val key = SessionExpiryRules.enforcementKey(session)
        if (!enforced.add(key)) return
        if (enforced.size > MAX_ENFORCED_KEYS) {
            enforced.clear()
            enforced.add(key)
        }
        GateNotifications.cancelSessionWarning(context, session.target)
        SessionExpiryAlarm.cancel(context, session.target)
        // The allowance may have just run out, which greys the icon.
        recomputeBadges()
        scope.launch { takeOver(session) }
    }

    /**
     * Puts the session-end screen in front of the user. Nothing here can stop
     * the gated app's processes — that needs permissions a sideloaded launcher
     * will never hold — so enforcement is the takeover plus the Gate the user
     * has to pass to come back.
     */
    private suspend fun takeOver(session: Session) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (powerManager?.isInteractive != true) {
            // Nothing to take over while the screen is off, but the app is
            // still what the user comes back to, so this is only deferred.
            deferTakeoverUntilUnlock(session.target)
            return
        }
        // Known to be somewhere else already: the session is over, and there is
        // nothing to interrupt. Unknown means taking over.
        if (hasLeft(session.target)) return
        startTakeover(session)
    }

    /**
     * Whether the user is demonstrably somewhere else, which is the only reason
     * not to take the screen over. Unknown counts as still there.
     *
     * `queryEvents` only reports the calling profile, so for a Target in another
     * profile — a Private Space copy, the case this fork exists for — the answer
     * would be some stale main-profile package. That is worse than no answer, so
     * it is not asked.
     */
    private fun hasLeft(target: Target): Boolean {
        if (userManager.toUserHandleOrNull(target.user) != Process.myUserHandle()) return false
        val foreground = ForegroundApp.current(context) ?: return false
        return foreground != target.packageName
    }

    private suspend fun startTakeover(session: Session) {
        val intentText = runCatching { sessionRepository.latestIntentText(session.target) }.getOrNull()
        val intent = SessionExpiredActivity.createIntent(
            context = context,
            target = session.target,
            appLabel = appLabelFor(session.target),
            intentText = intentText,
            wrapUpAvailable = !session.wrapUpUsed,
        )
        runCatching { context.startActivity(intent) }
    }

    /**
     * The screen went off before the session ran out. Taking over then would be
     * shouting at a dark screen, so it waits for the unlock that brings the
     * user back — and does nothing if they come back to something else.
     */
    private fun deferTakeoverUntilUnlock(target: Target) {
        pendingTakeover = target
        registerScreenReceiver()
    }

    /**
     * Keeps the screen receiver and the foreground poll running exactly as long
     * as there is something for them to do — a Session whose time is being kept,
     * or a takeover waiting for the user to come back.
     */
    private fun syncSessionWatchers(now: Instant) {
        val watchable = _sessions.value.any {
            it.endedAt == null && (it.pausedAt != null || it.endsAt.isAfter(now))
        }
        if (watchable || pendingTakeover != null) {
            registerScreenReceiver()
        } else {
            unregisterScreenReceiver()
        }
        if (watchable) startForegroundPoll() else stopForegroundPoll()
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onScreenOn(intent.action)
                }
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        screenReceiver = null
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }

    /** The screen went off: nobody is using the app, so nothing should be charged for. */
    private fun onScreenOff() {
        val now = clock.instant()
        val running = SessionPauseRules.running(_sessions.value, now)
        if (running.isEmpty()) return
        scope.launch { running.forEach { pause(it.target, now) } }
    }

    private fun onScreenOn(action: String?) {
        val now = clock.instant()
        scope.launch { resumeSessionsInApp(now) }
        if (action == Intent.ACTION_USER_PRESENT) takePendingTakeover()
    }

    private fun takePendingTakeover() {
        val target = pendingTakeover ?: return
        pendingTakeover = null
        scope.launch {
            try {
                if (hasLeft(target)) return@launch
                val session = _sessions.value.firstOrNull { it.target == target } ?: return@launch
                startTakeover(session)
            } finally {
                // Nothing may be left to watch for now that this is done.
                syncSessionWatchers(clock.instant())
            }
        }
    }

    /**
     * Restarts the clock on paused Sessions the user is back inside.
     *
     * Nothing resumes while the keyguard is up: the screen coming on to a lock
     * screen is not the user being back in the app. Where usage access has not
     * been granted the answer to "back in which app?" is unknown, and unknown
     * resumes — the alternative is a Session that pauses on the first screen-off
     * and never runs again.
     */
    private suspend fun resumeSessionsInApp(now: Instant) {
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return
        SessionPauseRules.paused(_sessions.value).forEach { session ->
            if (hasLeft(session.target)) return@forEach
            resume(session, now)
        }
    }

    /**
     * Polls what is in front while a Session is running, to notice the user
     * switching to another app — the one way of leaving that produces no
     * broadcast of its own. Only as accurate as the interval, so a switch costs
     * at most that much session time.
     *
     * Needs usage access, which the user grants in Settings; without it a switch
     * goes unnoticed and only screen-off pauses the clock. Cross-profile Targets
     * are out of reach either way, because `queryEvents` only ever reports the
     * calling profile.
     */
    private fun startForegroundPoll() {
        if (foregroundPoll?.isActive == true) return
        foregroundPoll = scope.launch {
            while (true) {
                delay(FOREGROUND_POLL.toMillis())
                runCatching { pollForeground() }
            }
        }
    }

    private fun stopForegroundPoll() {
        foregroundPoll?.cancel()
        foregroundPoll = null
    }

    private suspend fun pollForeground() {
        val now = clock.instant()
        // Screen off is the receiver's business, and asking what is in front
        // while the screen is off would answer with whatever was there before.
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != true) return
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return
        val foreground = ForegroundApp.current(context) ?: return
        _sessions.value
            .filter { it.endedAt == null && (it.pausedAt != null || it.endsAt.isAfter(now)) }
            .forEach { session ->
                if (userManager.toUserHandleOrNull(session.target.user) != Process.myUserHandle()) return@forEach
                val inApp = foreground == session.target.packageName
                when {
                    !inApp && session.pausedAt == null -> pause(session.target, now)
                    inApp && session.pausedAt != null -> resume(session, now)
                }
            }
    }

    /**
     * Stops a Session's clock. The end-of-session warning goes with it — it is
     * set for a moment that has stopped meaning anything — and is set again from
     * the new planned end when the clock restarts.
     *
     * The backstop alarm is deliberately left where it is: cancelling it would
     * lose the expiry if this process dies while paused. It fires at a planned
     * end that has moved, which [enforce] recognises and settles.
     */
    private suspend fun pause(target: Target, now: Instant) {
        if (!sessionRepository.pauseSession(target, now)) return
        GateNotifications.cancelSessionWarning(context, target)
    }

    /** Restarts a Session's clock, giving back the time the user was away for. */
    private suspend fun resume(session: Session, now: Instant) {
        val newEndsAt = SessionPauseRules.settledEnd(session, now)
        if (!sessionRepository.settlePause(session.target, now, stillPaused = false)) return
        SessionExpiryAlarm.schedule(context, session.target, newEndsAt)
        GateNotifications.scheduleSessionWarning(
            context = context,
            target = session.target,
            appLabel = appLabelFor(session.target),
            endsAt = newEndsAt,
        )
    }

    /**
     * A paused Session whose planned end has passed — the backstop alarm firing
     * on stopped time. The time away is settled onto the row so the planned end
     * moves with it, and the pause carries on, because nothing here says the user
     * has come back.
     *
     * A pause this long means the app was left, not stepped away from, so the
     * Session ends: coming back has to pass the Gate again.
     */
    private suspend fun reconcilePaused(session: Session, now: Instant) {
        if (SessionPauseRules.isAbandoned(session, now)) {
            sessionRepository.endNewestSession(session.target, now)
            GateNotifications.cancelSessionWarning(context, session.target)
            SessionExpiryAlarm.cancel(context, session.target)
            return
        }
        sessionRepository.settlePause(session.target, now, stillPaused = true)
        SessionExpiryAlarm.schedule(context, session.target, now.plus(SessionPauseRules.PAUSE_RECHECK))
    }

    /**
     * The one-off extension offered when a Session runs out: a minute to finish
     * what was in the middle of being written, rather than a hard stop.
     *
     * It moves the Session's own end, so the minute counts against the daily
     * allowance and the watchdog comes back for it. Persisted as spent, so
     * restarting the launcher does not hand out another one.
     */
    suspend fun wrapUpSession(target: Target): Boolean {
        val newEndsAt = clock.instant().plus(SessionExpiryRules.WRAP_UP)
        val extended = sessionRepository.wrapUpSession(target, newEndsAt)
        if (extended) SessionExpiryAlarm.schedule(context, target, newEndsAt)
        return extended
    }

    /**
     * The profile this Target lives in, or null when it cannot be resolved —
     * a removed profile, or a Private Space that is locked right now.
     */
    fun userHandleFor(target: Target): UserHandle? = userManager.toUserHandleOrNull(target.user)

    /** The app's name, falling back to its package name when it cannot be asked for. */
    fun labelFor(target: Target): String = appLabelFor(target)

    private fun appLabelFor(target: Target): String {
        val user = userManager.toUserHandleOrNull(target.user) ?: return target.packageName
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return target.packageName
        return runCatching {
            launcherApps.getActivityList(target.packageName, user).firstOrNull()?.label?.toString()
        }.getOrNull() ?: target.packageName
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

    /**
     * What the icon indicator draws.
     *
     * @property tier the Tier the gate *behaves* as, not necessarily the
     *   configured one — a spent allowance reads as LOCKED.
     * @property allowanceMinutesLeft whole minutes left of today's allowance,
     *   or null when this gate has no daily allowance.
     */
    data class GateBadgeState(
        val tier: Tier,
        val allowanceMinutesLeft: Int?,
    )

    companion object {
        private const val DB_NAME = "appgate.db"
        private const val SNAPSHOT_WAIT_MILLIS = 400L
        private val SESSION_HISTORY: Duration = Duration.ofDays(7)
        private val HANDOFF_GRACE: Duration = Duration.ofSeconds(10)
        private const val MAX_ENFORCED_KEYS = 100
        private const val COLD_START_WAIT_MILLIS = 6_000L

        /**
         * How often what is in front is checked while a Session runs. Short
         * enough that a switch away costs little session time, long enough not
         * to be a poll worth noticing on battery.
         */
        private val FOREGROUND_POLL: Duration = Duration.ofSeconds(20)
        private val MIN_SESSION: Duration = Duration.ofMinutes(1)

        @JvmField
        val INSTANCE = MainThreadInitializedObject(::AppGate)

        @JvmStatic
        fun getInstance(context: Context): AppGate = INSTANCE.get(context)
    }
}

/** Convenience for the launch path, which only cares whether a gate exists at all. */
fun AppGate.isGated(packageName: String, user: UserHandle): Boolean = badgeFor(packageName, user) != null
