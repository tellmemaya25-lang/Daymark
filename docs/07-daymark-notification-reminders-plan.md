# Daymark — Notification & Reminder Plan

Status: **P1–P3 implemented** (§9 lists what shipped). This document is the reference for the
reminder engine: why it changed, how delivery works, and what is deliberately not built yet.

Companions: `02-daymark-ai-system-design.md` (unrelated), `03-daymark-admin-dashboard-system-design.md`
(admin switches). No new backend: reminders stay client-side.

---

## 1. What existed before this change

| Piece | File | State |
|---|---|---|
| Per-task switch | `AddEditTaskScreen.kt` L546–570 | A single toggle, no lead time, no reminder UI beyond it |
| `reminderAt` | `Task.reminderAt`, `TaskDto.reminderAt` (Firestore) | Stored as an absolute timestamp |
| Scheduling | `notifications/ReminderScheduler.kt` | **WorkManager** one-time work, unique per task |
| Delivery | `notifications/ReminderWorker.kt` → `NotificationHelper` | `IMPORTANCE_HIGH` channel, heads-up |
| Action | `notifications/ReminderActionReceiver.kt` | "Mark done" writes Firestore **directly** |
| Permission | `MainActivity.onCreate` | Requested on every launch, before the user knows why |
| Settings | — | **Nothing.** No master switch, no lead time, no style, no snooze |

So the feature existed, but it was a demo of one: a "reminder" that WorkManager may deliver
15–30 minutes late, with no way to tune it and a Mark-done button that skipped the app's own
completion rules.

## 2. The six problems, in order of how much they hurt

1. **WorkManager is not an alarm.** It is deferred by Doze, by battery saver, and by the
   scheduler's batching window. `setInitialDelay` is a *minimum*. A 09:00 reminder arriving at
   09:25 is worse than no reminder — the user has already missed the thing.
2. **Reminders die after a reboot.** AlarmManager is cleared; WorkManager survives, but if we
   move to alarms (we must, see 1) we need our own persistence. Today there is nothing to
   rebuild from without a network round trip.
3. **No settings.** Not even "turn reminders off". Users silence the app at the OS level
   instead, which also kills the useful ones.
4. **Mark done bypasses the app's rules.** The receiver writes `completed=true` straight to
   Firestore. `ToggleCompleteUseCase` is what advances a habit's streak and spawns the next
   occurrence — so completing a recurring task from the notification silently broke the series.
5. **Nothing when you are already in the app.** A heads-up banner over the screen you are
   looking at, for a task on that screen, is noise.
6. **Permission asked at launch, cold.** The worst moment: no context, and a "no" is close to
   permanent (Android stops re-prompting after two denials).

## 3. Delivery ladder

```
reminderAt (absolute, computed at save)
        │
        ▼
ReminderScheduler.schedule(...)
        │  persists to ScheduledReminderStore (survives reboot)
        ▼
 canScheduleExactAlarms()?  ── no ──► setAndAllowWhileIdle()   (inexact, still wakes through Doze)
        │ yes                                    │
        ▼                                        │
 setExactAndAllowWhileIdle()                     │
        │  both eventually broadcast             │
        └──────────────┬─────────────────────────┘
                       ▼
             ReminderAlarmReceiver
                       │
        app in foreground? ── yes ──► in-app popup (ReminderEvents)
                       │ no
                       ▼
        alert style ── ALARM ──► full-screen intent + alarm channel
                    ├─ NOTIFY ─► heads-up notification
                    └─ SILENT ─► quiet notification (no sound, no vibrate)
```

Why the middle step: `SCHEDULE_EXACT_ALARM` is a user-grantable permission from Android 12 and
**denied by default on Android 14+** for apps that are not alarm clocks. Rather than ship an
app that quietly stops reminding people, an un-granted exact alarm degrades to
`setAndAllowWhileIdle`, which is inexact but still pierces Doze (worst case a few minutes late,
not a few hours). Settings offers a one-tap "Allow exact alarms" row that opens the system
screen.

**No `USE_EXACT_ALARM`.** That permission is auto-granted and Play restricts it to alarm-clock
and calendar apps; a to-do app claiming it is a policy violation waiting to happen.

## 4. Alert styles map to channels, not toggles

Android owns sound and vibration *per channel*, and a channel's importance cannot be changed
after creation. An app-side "sound on/off" switch therefore cannot be honoured on an existing
channel — shipping one would be a lie the OS silently overrides.

So the setting is a **style**, and each style is a real channel:

| Style | Channel id | Importance | Delivery |
|---|---|---|---|
| **Alarm** | `task_reminders_alarm` | HIGH, `CATEGORY_ALARM`, sound + vibrate | Full-screen intent over the lock screen, plus heads-up |
| **Notification** | `task_reminders` | HIGH, sound + vibrate | Heads-up banner |
| **Silent** | `task_reminders_silent` | LOW, `setSilent(true)` | Tray only |

The Notification style keeps the channel id the app has always used (`task_reminders`). Anyone
who already tuned that channel's ringtone or muted it keeps that choice; a new id would silently
resurrect a notification they had turned off.

The Settings row links to the system channel screen for volume, ringtone and "Override Do Not
Disturb" — the controls Android actually gives a user. We do not pretend to have our own.

## 5. Data model

Nothing new in Firestore. `Task.reminderAt` stays an absolute timestamp, computed at save:

```
reminderAt = dueAt - defaultLeadMinutes        (per-task override wins)
```

That keeps every existing document valid and keeps the wire contract unchanged. The lead is
applied in `AddEditViewModel` and in quick add (which already parses "30 minutes before" into
`reminderLeadMinutes`).

New local-only state:

| Store | Keys / shape | Why |
|---|---|---|
| `ReminderPreferences` (DataStore `daymark_reminder_prefs`) | `reminders_enabled`, `reminder_lead_minutes`, `reminder_alert_style`, `reminder_snooze_minutes`, `reminder_permission_asked` | The settings surface |
| `ScheduledReminderStore` (DataStore, JSON array) | `[{taskId,title,description,triggerAt,style}]`, capped at 200 | Rebuild every alarm after a reboot without the network |

The store is pruned on every write (past entries dropped) so it cannot grow forever.

## 6. Settings surface

New `REMINDERS` section in `SettingsScreen`, between `APPEARANCE` and `AI` — settings about the
app, not about the account, and above the AI block that most people never open:

| Row | Control | Default |
|---|---|---|
| Reminders | master switch | on |
| Default reminder | sheet: At due time · 10 min · 30 min · 1 hour · 1 day before | At due time |
| Alert style | sheet: Alarm · Notification · Silent | Notification |
| Snooze length | sheet: 5 · 10 · 15 · 30 min | 10 min |
| Exact alarms | shown only when Android 12+ and not granted; opens the system screen | — |
| Notification permission | shown only when denied; opens app settings | — |

Defaults are chosen to match the behaviour that shipped, so nobody's existing reminders change
until they change them.

## 7. In-app popup

The receiver always fires. If an activity is in the foreground (`AppForegroundTracker`, driven
by `registerActivityLifecycleCallbacks`, no new dependency), the system notification is
**suppressed** and `ReminderEvents` emits instead; `InAppReminderHost` — mounted once above the
NavHost — shows a dialog with the task title and three actions: **Mark done**, **Snooze**, and
**Later** (dismiss without touching the task). Snooze uses the length from Settings, so the
button never promises a number the app will not honour.

Foreground wins over the alert style: a screen the user is looking at gets a popup, not a
banner over its own content. Alarm style only differs once the app is *not* in the foreground —
there a locked or sleeping device gets the full-screen alarm, which is what "alarm" means.

Both decisions are made in one place, `ReminderDelivery`, shared by the alarm receiver and the
WorkManager fallback, so the two paths cannot drift apart.

Two reminders due in the same minute queue up rather than replacing each other: the host keeps a
list and shows the next one when the current is dismissed.

## 8. Edge cases

| Case | Behaviour |
|---|---|
| Reminder time already past | Not scheduled; the store drops it. A reminder for 09:00 set at 09:05 is silence, not a burst. |
| Lead pushes the trigger before "now" | Clamped to `now + 30 s`, so "remind me 1 hour before" on a task due in 20 minutes still warns you now instead of never. |
| Task completed / deleted | `cancelReminder(taskId)` — already called from `updateTask`; delete and complete now call it too. |
| Reboot | `BOOT_COMPLETED` → `ReminderBootReceiver` replays `ScheduledReminderStore` through the same scheduler. |
| Alarm fires while the app is open | In-app popup only (§7). |
| Permission denied | Worker/receiver no-ops; the Settings row shows the denial with a fix action. No crash, no silent retry storm. |
| Snooze | Re-schedules through the same path (+N minutes), does not touch the task. The title and description ride along on the action intent, because the reminder has already left the queue by then. |
| Reminders switched off | Queued entries are kept but **not armed**, so the device is not woken for a notification the app would immediately drop. Switching reminders back on calls `rescheduleAll()` and re-arms everything still ahead. |
| Due date edited while a reminder is on | The reminder moves with the due date (and keeps the chosen lead), so it cannot be left pointing at the old day. |
| Timezone / clock change | Alarms are `RTC_WAKEUP` on absolute time, so a manual clock change leaves a stale alarm until the next save; `ACTION_TIME_CHANGED` is listed as P4. |
| Two tasks, same minute | Distinct `PendingIntent` request codes (`taskId.hashCode()`), so both fire. |

## 9. Phases

| Phase | Contents | State |
|---|---|---|
| **P1** | Pure policy (`ReminderPolicy`, `ReminderSettings`) + tests; `ReminderPreferences`; `ScheduledReminderStore`; `ReminderScheduler` rewritten to AlarmManager with the exact→inexact→WorkManager ladder; `ReminderAlarmReceiver`; `ReminderBootReceiver`; channels; Mark-done routed through `ToggleCompleteUseCase`; snooze action | **shipped** |
| **P2** | Settings `REMINDERS` section (master, lead, style, snooze, exact-alarm and permission rows); lead applied on save and in quick add | **shipped** |
| **P3** | In-app popup (`ReminderEvents`, `AppForegroundTracker`, `InAppReminderHost`); full-screen alarm activity for Alarm style | **shipped** |
| **P4** | Per-task lead chips in the reminder sheet; quiet hours (suppress SILENT-mode delivery inside a window, queue a digest); `ACTION_TIME_CHANGED` / `TIMEZONE_CHANGED` rescheduling; "Remind me later" from the in-app popup | deferred |
| **P5** | Daily digest ("3 tasks due today"), notification grouping per list, notification history in Activity | deferred |

## 10. Testing strategy

The delivery path needs a device, but the *decisions* do not. Everything that can be decided
without Android lives in `ReminderPolicy` and runs on the JVM:

- trigger time from due date + lead (including the clamp and the past-due case)
- snooze arithmetic
- channel id per style (so a style cannot silently map to the wrong channel)
- the one-line Settings summary
- "should this reminder fire at all" under the master switch

`ReminderPolicyTest` covers these. The Android-side classes are kept thin on purpose: they read
prefs, call `AlarmManager`, build a notification — no branching worth a test that a JVM cannot
run.

## 11. Files

| File | Role |
|---|---|
| `domain/reminder/ReminderPolicy.kt` | Pure: settings, lead-time resolution, snooze, channel ids |
| `core/datastore/ReminderPreferences.kt` | DataStore for the settings surface |
| `notifications/ScheduledReminderStore.kt` | Reboot-safe queue of pending alarms |
| `notifications/ReminderScheduler.kt` | AlarmManager ladder + store + WorkManager fallback |
| `notifications/ReminderAlarmReceiver.kt` | Alarm → notification or in-app popup |
| `notifications/ReminderBootReceiver.kt` | Replay after reboot |
| `notifications/ReminderActionReceiver.kt` | Done (via use case) and Snooze |
| `notifications/NotificationHelper.kt` | Three channels, three styles, full-screen intent |
| `notifications/ReminderEvents.kt`, `AppForegroundTracker.kt` | In-app delivery |
| `ui/reminder/InAppReminderHost.kt`, `ReminderAlarmActivity.kt` | Popup and alarm screen |
| `ui/settings/SettingsScreen.kt`, `ui/profile/ProfileViewModel.kt` | The `REMINDERS` section |
| `ui/addedit/AddEditViewModel.kt` | Lead applied at save; contextual permission request |
