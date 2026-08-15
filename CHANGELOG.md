# 0.2.0

Sessions now end where the user can feel it, run only while the app is actually in front, and report back as neutral usage figures.

Session end is now felt: when a gated app's timer runs out while the app is in front, AppGate takes the screen over and puts the user back on the launcher.

- In-process watchdog in `AppGate` fires when the running session that ends soonest runs out; rescheduled on every session emission, so a session ended early drops out on its own.
- Inexact `SessionExpiryAlarm` backstop for when the launcher process is not alive to watch its own session; both paths share one enforcement key so a takeover happens at most once per expiry.
- New `SessionExpiredActivity`: full-screen takeover over the app, quoting the user's own stated intent back where there is one. Every way out of it — Done, back — goes home, and coming back has to pass the Gate again.
- One-off "wrap up" extension of a minute, persisted on the session row (`sessions.wrapUpUsed`, `MIGRATION_4_5`), so it counts against the daily allowance and cannot be taken twice.
- Takeover is skipped when usage access says the user has already left the app, and deferred to `ACTION_USER_PRESENT` when the screen is off at expiry.
- `SessionRepository` gains `wrapUpSession` and `latestIntentText`; `Session` gains `wrapUpUsed`.
- Debug builds offer a one-minute session length, so an expiry can be watched without waiting five minutes. The challenge itself is unchanged.
- 13 JVM tests for the expiry rules (`SessionExpiryRulesTest`), 52 in total.

Session time now only runs while the app is actually being used: the screen going off, or another app coming to the front, stops the clock.

- `Session` carries `pausedMillis` and an open `pausedAt` (`MIGRATION_5_6`), so a pause survives the launcher being killed; the settle arithmetic is a single SQL statement, so no stale snapshot can get it wrong.
- Time away is given back — the planned end moves by the length of the pause — and the daily allowance is only charged for time in the app (`timeInWindow` in `BudgetEvaluator` subtracts pause, clamped at zero).
- One receiver for screen off, screen on and unlock, registered only while there is a session to keep time for, and it also carries the deferred takeover that used to have a receiver of its own. Nothing resumes while the keyguard is up.
- A 20-second foreground poll notices the user switching to another app, the one way of leaving that broadcasts nothing. Needs usage access; without it only screen-off pauses the clock, and cross-profile targets are out of reach of `queryEvents` either way.
- The backstop alarm is left armed across a pause, so a process death cannot lose the expiry: it fires on a planned end that has moved, and `enforce` settles the pause and re-arms instead of taking the screen over.
- A pause running past 30 minutes ends the session outright — the app was left, not stepped away from — which also stops the recheck alarms.
- `SessionRepository` gains `pauseSession`, `settlePause` and `endNewestSession`.
- 12 more JVM tests (`SessionPauseRulesTest`, paused-time cases in `DailyAllowanceTest`), 64 in total.

Usage stats for gated apps: a list of every gate with today's time, a screen of daily figures per app, and the same figures at the top of the gate settings sheet.

- New `Gated apps` screen (Settings › Experimental features › App gating, deep link `lawnchair://settings/appgate-stats`) listing each gate with time today, sessions today and its level.
- Tapping an app opens its own screen: a seven-day bar chart with the daily allowance marked, plus time in app, longest session, sessions, and the gate's level and allowance.
- The gate settings sheet from an icon's long-press now opens with the same figures and chart, for apps that already have a gate.
- Figures come from `UsageStatsRules`, pure Kotlin over the Sessions already in memory: days run from the 5 AM allowance reset, paused time is excluded, and days with nothing in them are still drawn.
- Copy and colours stay neutral — time, sessions, trend, one bar colour, no grading.
- A gate whose profile cannot be resolved right now (a locked Private Space) still lists, without its icon, rather than disappearing from the screen that manages it.
- 8 JVM tests for the aggregation (`UsageStatsRulesTest`), 72 in total.

# 0.1.0

Settings search, indexed by composition rather than by hand.

- Search icon in the settings dashboard top bar, new `SettingsSearch` route with deep link `lawnchair://settings/settings-search`.
- The shared preference controls register themselves through `RegisterSearchableSetting`, so a newly added preference becomes searchable with no search-side work.
- Screens that take no arguments are declared once in `navigation/PreferenceScreens.kt`, which drives both the navigation graph and the off-screen indexing pass.
- The indexing pass composes and measures every indexable screen, then places nothing; launcher previews, wallpaper previews and smartspace setup opt out of it.
- Results show the full path to a setting, `screen › group › label`.
- Fixed `DividerColumn` measuring an empty group to a negative height, which crashed with `Size(w x -5) is out of range`.
