# Daymark — Timeline View, View Toggle & Default View (System Design + Implementation Plan)

**Status:** plan / not implemented
**Branch base:** `main` @ `72e73d2`
**Inspiration:** TickTick day calendar (`image-1`), Structured day timeline (`image-2`), app-switcher ref (`image-3`)

---

## 1. What is being built

Three things, in dependency order:

| # | Feature | One-line definition |
|---|---|---|
| A | **Timeline view** | A vertical 24-hour day agenda on Home, where each task is a block whose height matches its duration, laid out against an hour grid with a time gutter and a vertical rail. |
| B | **View toggle** | A two-state control in the Home list header that swaps the task list for the timeline without leaving the screen. |
| C | **Default view** | A persisted `HomeViewMode` that decides which of the two Home opens in; editable from both the toggle and `Settings > Appearance`. |

### What each reference actually contributes

| Reference | Take this | Leave this |
|---|---|---|
| **TickTick** (`image-1`) | Week strip above the grid; hour labels in a left gutter (`12:00 AM`, `1:00 AM`); blocks rounded and full-width, with a small note icon on the right; the grid continues into empty hours instead of hiding them | Its top-right triple of view icons (list / week / month / agenda) — Daymark needs **one** toggle, not four |
| **Structured** (`image-2`) | The rail: circle icon + vertical connector line through every entry; time range + duration on one muted line (`08:11 - 08:41 AM (30m)`); a **dashed rail across long empty gaps**; status rings on the right; completed = filled/coloured | Its infinite zoom-out (day → week → month → year) — out of scope, that is a whole navigation model |
| **App icons** (`image-3`) | Confirmation that this is a *pattern* match, not a pixel match — Daymark keeps its own dark surface, `AccentBlue`, 18–20 dp card radii | Nothing |

### Non-goals (explicitly out of scope for v1)

- Week/month timeline canvases, drag-to-reschedule, drag-to-resize blocks.
- Event duration *editing by dragging* (duration is set in Add/Edit with chips).
- Cross-midnight blocks (a task belongs to the day its `dueAt` falls on).
- Calendar sync (Google/device calendars), shared calendars, recurring *timed* events beyond `RepeatRule`.
- A separate Calendar tab in the bottom nav.

---

## 2. Decision log

Each decision below had a cheaper alternative that was rejected for a stated reason. These are the ones that will otherwise get re-litigated mid-implementation.

### D1 — The timeline lives **inline on Home**, not in a new tab or the month sheet

*Rejected:* a 6th bottom-nav destination (the bar is already 5 slots with a centre FAB, and `MainScaffold.kt` maps routes by string matching — adding a tab means touching the pager in `MainTabsScreen.kt`); putting it inside `MonthlyCalendarSheet` (that sheet is a month picker, and stuffing a day agenda into a bottom sheet caps it at ~60 % screen height, which is exactly wrong for a timeline).

*Because:* Home already owns `selectedDate` and the day-selection gestures (`DockedWeeklyCalendarBar` swipe = ±1 day). A timeline under that same strip is the same mental model Structured uses, with TickTick's escape hatch.

### D2 — The timeline is an **alternative item model for the existing `LazyColumn`**, not a nested scrollable

This is the single most important architectural constraint.

`HomeScreen` renders **one** `LazyColumn` with keyed items (`HEADER_ITEM_KEY`, `MOMENTUM_ITEM_KEY`, `WEEK_ITEM_KEY`, …). The dock/reset machinery added in PRs #71/#72 reads those keys out of `listState.layoutInfo` and drives `scrollDockSignal` / `dockProgress`.

- A `LazyColumn` nested inside that list is illegal (unbounded height → crash).
- A `Column` with `verticalScroll` inside it is worse: two scroll owners, and the dock thresholds become meaningless.
- Putting the timeline in a fixed-height `Box` gives a scroll-inside-scroll experience no reference app has.

**Therefore:** in timeline mode, the *same* `LazyColumn` emits timeline items instead of task cards, after the unchanged header/momentum/week/AI/suggestions items. `WEEK_ITEM_KEY` stays exactly where it is, so `scrollDockSignal`, `inlineWeekTopPx` and the slot-collapse from #72 keep working untouched.

**Hard rule for the implementer:** the new item keys are `timeline-anytime`, `timeline-hour-00` … `timeline-hour-23`, `timeline-gap-N`, and `tl-<taskId>`. **Never** reuse or shadow `HEADER_ITEM_KEY` / `MOMENTUM_ITEM_KEY` / `WEEK_ITEM_KEY`.

### D3 — Timed blocks need a duration, so `Task.durationMinutes: Int?` is added (nullable, default `null`)

`Task` today has only `dueAt: Long?` — no end time. Both references draw blocks whose height *is* the duration, and Structured prints it.

*Rejected:* a global fixed 30-minute block (every meeting looks like every errand; the `(30m)` label becomes a lie); deriving it from sub-task count (arbitrary).

*Because:* nullable + defaulted means **zero migration**. Old documents parse (`TaskDto` has defaults), old tasks render as the fallback duration, and precision arrives per-task as users set it.

Fallback rule (one place, in the layout engine): `durationMinutes ?: 30`, clamped to `5..720`, and clamped again so a block never crosses midnight.

### D4 — Full 24-hour grid, `LazyColumn`-virtualised, auto-scrolled to relevance

*Rejected:* a dynamic min/max window (`06:00 → last task + 30 min`) — it silently hides a 23:30 task, and the window jumping as tasks change makes the scroll position lurch.

*Because:* with hour rows as list items, off-screen hours cost nothing (Compose only composes visible ones). Auto-scroll on open to: the first timed task of the day if the day isn't today, otherwise `now − 90 min` (so "now" sits ~⅔ down the viewport, which is where both references put it).

### D5 — Untimed tasks go to a pinned **Anytime** band; they are never dropped

`HomeTaskSorter` includes `dueAt == null` tasks in `HomeFilter.TODAY` when today is selected. If the timeline only renders `dueAt` blocks, **untimed tasks disappear when the user toggles views** — a data-visibility bug, and the most likely regression of this whole feature.

**Therefore:** an `Anytime` section renders above the first hour row, containing every untimed *pending* task for the selected day plus the day's overdue ones. It gets its own test.

### D6 — One persisted value is both the default **and** the last choice

`HomeViewPreferences.viewMode: Flow<HomeViewMode>` in DataStore, written when the user toggles **and** when they pick from Settings.

*Rejected:* a separate "pin default" flag (three states, two sources of truth, no user-visible benefit); a launch-only default with an ephemeral toggle (users then think the toggle is broken because it forgets).

*Because:* "it opens the way I left it" is what both reference apps do, and the Settings row makes the stored value discoverable and overridable without hunting for the toggle.

### D7 — The toggle **replaces the sort control** in the existing list header

`ListHeader` (`HomeScreen.kt` ~L1206) currently ends with a sort chip (`Due date ↑`). In timeline mode a sort order is meaningless — the clock is the sort.

So: the right-hand slot becomes a 2-segment control `[☰] [🕐]` in both modes; the sort chip renders **only** in list mode, to the left of the toggle. `ON YOUR LIST` becomes `TIMELINE` in timeline mode, and the `selectedFilter` segmented control (Today / Upcoming / All) is hidden in timeline mode (the timeline is always "the selected day").

### D8 — Overlaps use lane packing, capped at 3 lanes

Sort blocks by start, then assign each to the first lane whose previous block ended at or before this start. `laneCount` per cluster sets block width: `width / laneCount` minus 6 dp gutter. A 4th+ concurrent block collapses into a `+N more` chip that opens the day list sheet.

*Rejected:* stacking with offset (unreadable past 2), or letting blocks overlap (illegible).

### D9 — Long empty runs collapse into a **gap row** (Structured's dashed rail)

Any run of ≥ 3 consecutive empty hours renders one row: dashed vertical rail, `09:15 → 22:00 · 12h 45m` in muted type, and a tap-to-expand. Default **on**, with a `Settings > Appearance > Compress empty hours` switch (default on) so it can be turned off by people who want the literal grid.

### D10 — Horizontal swipe changes the day, with touch-slop arbitration

Swiping left/right on the timeline body shifts `selectedDate` ±1 day — the same semantic and same ±1-day step as `DockedWeeklyCalendarBar.onShiftDays`, so the two gestures agree.

Implementation must not steal vertical drags: use `awaitEachGesture { awaitHorizontalTouchSlopOrCancellation(...) }` in `pointerInput`, **not** a bare `detectHorizontalDragGestures` over the whole surface, and never consume a pointer that has already travelled vertically. Transition via `AnimatedContent` with a 220 ms slide, direction from the delta sign.

---

## 3. Data model change

### 3.1 `Task` (domain)

```kotlin
// domain/model/Task.kt — appended after `description`-adjacent fields, nullable on purpose
/**
 * How long the task occupies. Null means "not specified"; the timeline falls back to
 * 30 minutes rather than hiding the task.
 */
val durationMinutes: Int? = null,
```

**Non-negotiable:** do **not** touch the nullability of `title` / `description` (non-null `String`). Three separate builds broke on `String?` being fed where `String` was required — see `docs/04-…` follow-ups and the `AddTaskUseCase` / `UpdateTaskUseCase` / `Mappers` fixes in PRs #73–#75.

### 3.2 Every place the field must be threaded

This list exists because `minimalTaskMap` (the flattened fallback writer) is the classic miss.

| File | Change | Why it breaks if skipped |
|---|---|---|
| `domain/model/Task.kt` | add `durationMinutes: Int? = null` | everything downstream has nothing to read |
| `data/remote/dto/TaskDto.kt` | `val durationMinutes: Int? = null` | writes silently drop the field |
| `data/remote/dto/Mappers.kt` → `TaskDto.toDomain()` | `durationMinutes = durationMinutes` | reads always show the fallback |
| `data/remote/dto/Mappers.kt` → `Task.toDto()` | `durationMinutes = durationMinutes?.coerceIn(5, 720)` | unclamped values reach Firestore |
| `data/repository/TaskRepositoryImpl.kt` → `minimalTaskMap()` | `"durationMinutes" to task.duration…` | **fallback path loses it while the POJO path keeps it** — the divergence bug |
| `domain/usecase/AddTaskUseCase.kt` / `UpdateTaskUseCase.kt` | clamp into `5..720`, `null` stays `null` | negative / 10 000-minute blocks |
| `domain/security/InputSecurityValidator.kt` | `const val MIN_DURATION_MINUTES = 5`, `MAX_DURATION_MINUTES = 720`, `clampDurationMinutes(Int?)` | one shared bound instead of three copies |
| `ui/addedit/AddEditUiState.kt` + `AddEditTaskScreen.kt` + `AddEditViewModel.kt` | duration chip row + state + save | users can't set it (feature still works on the fallback) |
| `data/repository/AdminRepositoryImpl.kt` (`AdminTaskRecord` mapping, if it exposes timings) | pass through | admin edits would nil it out on save |

**Firestore rules: no change required.** `validTaskPayload()` validates only `title` and `description` (`firestore.rules` L43–47), so an extra numeric field passes. Optional hardening: add `data.durationMinutes == null || (data.durationMinutes is number && data.durationMinutes >= 5 && data.durationMinutes <= 720)`.

### 3.3 Add/Edit UI

Under the due-time row:

```
Duration    [15m] [30m] [45m] [1h] [1h30] [2h] [—]
```

- Disabled and hidden when no time is set (there is nothing to attach a duration to) — with a hint: *"Add a time to place this on the timeline."*
- `—` clears back to `null` (fallback governs).
- Values are constant `Int`, no free text → no new validation surface.

---

## 4. State & wiring

### 4.1 View mode enum

```kotlin
// ui/home/HomeUiState.kt
/** Which body Home renders below the weekly strip. */
enum class HomeViewMode(label: String) { LIST("List"), TIMELINE("Timeline") }
```

`HomeUiState` gains:

```kotlin
val viewMode: HomeViewMode = HomeViewMode.LIST,
/** Day blocks for the timeline; recomputed when the day, tasks or zone change. */
val timeline: TimelineDay = TimelineDay.Empty,
```

`HomeAction` gains:

```kotlin
data class SetViewMode(val mode: HomeViewMode) : HomeAction
data class OpenDaySheet(val date: LocalDate) : HomeAction   // "+N more" / month grid → full day
```

`SetViewMode` must: update state **and** persist (`homeViewPreferences.setViewMode(mode)`).

### 4.2 Timeline derivation (ViewModel)

```kotlin
private fun buildTimeline(
    tasks: List<Task>, day: LocalDate, zone: ZoneId, now: LocalTime
): TimelineDay = TimelineLayout.build(
    tasks = tasks,
    day = day,
    zone = zone,
    fallbackDurationMinutes = 30,
    maxLanes = 3,
    gapThresholdHours = 3
)
```

Recompute triggers: `allTasks`, `selectedDate`, `ZoneId.systemDefault()`, and a 1-minute ticker **only** for the now-line (see §6.6 — the ticker updates a `LocalTime` in a `remember`ed state inside the composable, not the whole `HomeUiState`, to avoid recomposing the list every minute).

### 4.3 Where it plugs into `HomeScreen`

Inside the existing `LazyColumn`, the region that today begins with `item { ListHeader(...) }` becomes:

```kotlin
when (uiState.viewMode) {
    HomeViewMode.LIST      -> { ListHeader(…); taskItems(…) }
    HomeViewMode.TIMELINE  -> { TimelineHeader(…); timelineItems(uiState.timeline) }
}
```

`HEADER_ITEM_KEY`, `MOMENTUM_ITEM_KEY` and `WEEK_ITEM_KEY` items are untouched, above this block.

---

## 5. Layout engine — `ui/home/TimelineLayout.kt` (pure, unit-tested)

Modelled on `HomeTaskSorter`: no Android imports, everything injected, so `./gradlew test` covers it.

```kotlin
data class TimelineBlock(
    val taskId: String,
    val title: String,
    val startMinute: Int,      // minutes from midnight, 0..1440
    val endMinute: Int,        // exclusive, ≤ 1440
    val lane: Int,             // 0-based column
    val laneCount: Int,        // columns in this overlap cluster
    val isCompleted: Boolean,
    val priority: Priority,
    val categoryId: String?
) {
    val durationMinutes: Int get() = endMinute - startMinute
}

sealed interface TimelineRow {
    data class Anytime(val tasks: List<Task>) : TimelineRow
    data class Hour(val hour: Int, val blocks: List<TimelineBlock>) : TimelineRow
    data class Gap(val fromMinute: Int, val toMinute: Int) : TimelineRow
}

data class TimelineDay(val rows: List<TimelineRow>, val anytime: List<Task>, val firstTimedMinute: Int?)
```

### Algorithm

1. **Bucket** — for each task with `dueAt != null` whose local date equals `day` in `zone`, compute `startMinute = hour*60 + minute` via `ZonedDateTime` (never `millis % 86_400_000` — that is wrong on DST days).
2. **Duration** — `endMinute = start + (durationMinutes ?: fallback)`; clamp `≥ 5`, clamp `≤ 1440`.
3. **Cluster & lane-pack** — sort by `(startMinute, endMinute)`; group blocks that transitively overlap; within a group assign lanes greedily; `laneCount = maxLane + 1` seeded at `1`.
4. **Fold** — place each block under its start hour; a block that runs past the hour boundary is drawn with `height = duration * PX_PER_MINUTE` and *does not* create duplicate rows (it overlaps the next hour row visually — implemented as a single absolutely-positioned pass, see §6.3).
5. **Collapse gaps** — walk hours 0…23; runs of empty hours with length ≥ `gapThresholdHours` become one `Gap` row (start = end of the previous block rounded up to the hour, end = start of the next block floored).
6. **Anytime** — pending tasks with `dueAt == null` for today-selected, plus overdue-and-still-open tasks (`dueAt < startOfDay`), sorted by the existing `HomeSort`.

### Tests (`app/src/test/java/com/sabihon/todo/TimelineLayoutTest.kt`)

| # | Case | Expectation |
|---|---|---|
| 1 | two untimed tasks | both in `Anytime`, zero hour rows carry them |
| 2 | task at 23:30, 90 min | `endMinute == 1440`, never 1480 |
| 3 | three tasks 09:00–10:00, 09:15–09:45, 09:30–10:15 | lanes `0,1,2`, `laneCount == 3` |
| 4 | same three + a fourth at 09:20 | 4th goes to overflow list, `laneCount == 3` |
| 5 | empty 09:00–22:00 | single `Gap(540, 1320)` |
| 6 | gaps disabled / 2 h | `Gap` not emitted |
| 7 | `durationMinutes = null` | 30-minute block |
| 8 | `durationMinutes = 0` / `-5` / `9999` | clamped to 5 / 5 / 720 |
| 9 | DST spring-forward day in `Europe/Berlin` | 23 hour rows, labels still monotonic |
| 10 | task in a different zone | excluded from the day |
| 11 | completed task | still placed, flagged `isCompleted` |
| 12 | habit occurrence (has `seriesId`) | placed like any task, `repeatLabel` rendered |

---

## 6. UI specification

### 6.1 Dimensions & tokens

| Token | Value | Source |
|---|---|---|
| Hour row height | `64.dp` | 1 h = 64 dp → 15 m ≈ 16 dp, enough for a one-line block |
| Time gutter width | `64.dp` (label right-aligned, `10.sp`, `onSurfaceVariant`) | matches both references' left rail |
| Rail inset | `12.dp` from gutter edge, `2.dp` wide, `onSurface.copy(alpha = .08f)` | |
| Block radius | `14.dp` | Daymark cards are 18–20 dp; blocks sit denser |
| Block background | `LocalDaymarkSurfaces.current.card` + `1.dp cardBorder` | reuse the token set so dark mode needs no new colours |
| Block min height | `44.dp` (tap target) | a11y |
| Block horizontal gutter | `6.dp` between lanes | |
| Accent | `AccentBlue` (`0xFF2F6BFF`) | now-line, selected ring, rail dot |
| Completed | `card.copy(alpha = .55f)`, title `lineThrough`, ring filled `AccentBlue` | |
| Priority dot | reuse `priorityDotColor()` (`URGENT #EB5757`, `HIGH #F2994A`) | `HomeScreen.kt` L1463 |

### 6.2 Structure (timeline mode)

```
┌──────────────────────────────────────────────┐
│ (header, Momentum, week strip, AI row —      │  ← unchanged, keeps the dock logic alive
│  suggestions — all unchanged)                │
├──────────────────────────────────────────────┤
│ TIMELINE   Thu 29 Oct            [☰][🕐]     │  ← TimelineHeader
├──────────────────────────────────────────────┤
│ ANYTIME                                      │
│ ○ Rise and Shine            ☆  Daily         │  ← Anytime band (D5)
│ ○ Wind Down                                  │
├──────────────────────────────────────────────┤
│ 08:00 │ ●────── 08:00 - 08:30 · 30m          │
│       │  Rise and Shine                   ○  │
│ 09:00 │ ●────── 09:00 - 09:30 · 30m          │
│       │  Answer Emails                    ●  │
│ 10:00 │                                      │
│  ⋮    │ ⋮  (dashed rail, tappable)           │  ← Gap row (D9)
│ 21:00 │ ●────── 21:00 - 22:00 · 1h           │
│ 22:00 │  Wind Down                        ○  │
├──────────────────────────────────────────────┤
│              [+ New task]                    │
└──────────────────────────────────────────────┘
```

### 6.3 Drawing consequences of D2

Because the timeline is list items, a block that spans an hour boundary cannot be a child of one hour row. Two viable implementations, pick one and stay with it:

- **Recommended — single grid item.** One item emulates the whole visible grid: `Box(height = hours * 64.dp)` with absolutely-positioned blocks via `Modifier.offset(y = …)`. Virtualisation is lost, but 24 h × 64 dp = 1536 dp is a single laid-out box — cheap, and it makes cross-hour blocks trivial. Gap rows then become in-flow items above/below it, so they cannot compress the grid… **which breaks D9.**
- **Recommended instead — hour items + clipped block segments.** Each hour item draws its slice of any block that covers that hour (`y = (startMinute - hour*60) * PX_PER_MIN`, clipped by the row). Glyph/checkbox/title render only in the block's *first* visible segment. Keeps virtualisation **and** lets gap rows replace hour items, so D9 works.

→ Choose the second. It is more code, but it is the only version that satisfies both D4 and D9, and it composes per-hour, so scrolling stays cheap.

### 6.4 Block content (first segment only)

```
●────── 09:00 - 09:30 · 30m          ← 12.sp, onSurfaceVariant
  Answer Emails                     ← 14.sp semibold, maxLines 2, ellipsis
  ⌁ Work at Home · 3/4 steps        ← 11.sp, category + sub-task progress (optional)
                                ○   ← CircleCheckbox, right-aligned, 48 dp target
```

- Whole block: `clickable` → task detail; `combinedClickable(onLongClick)` → existing action sheet (`showActionSheet` + `selectedTask`), so long-press behaves identically to the list.
- Checkbox: `onCheckedChange` **must** dispatch `HomeAction.ToggleComplete(taskId, checked)` — never write `isCompleted` locally. `ToggleCompleteUseCase` refuses while sub-tasks are open, and Home already relies on that answer.

### 6.5 TimelineHeader

`TIMELINE` label (`11.sp`, `1.4.sp` letterSpacing, `eyebrow` colour), date hint chip (`Thu 29 Oct` — same treatment as the list's `dateHint`), then right-aligned `[☰][🕐]` toggle. In timeline mode the sort chip and the Today/Upcoming/All chips are hidden (D7).

### 6.6 Now-line

Rendered only when `selectedDate == LocalDate.now(zone)`: `2.dp` `AccentBlue` line at `nowMinute * PX_PER_MIN`, with a `6.dp` dot at the left end. It is driven by a composable-local ticker:

```kotlin
var nowMinute by remember { mutableIntStateOf(nowMinuteOfDay()) }
LaunchedEffect(selectedDate) {
    while (true) { delay(60_000); nowMinute = nowMinuteOfDay() }
}
```

Not in `HomeUiState` — a per-minute `StateFlow` emission would recompose the entire list.

### 6.7 Empty & loading states

| State | Render |
|---|---|
| No tasks at all that day | `Anytime` hidden, grid shows, centred `EmptyState` variant: *"Nothing scheduled. Tap ＋ or pick a day above."* — the grid stays visible so the timeline reads as a timeline, not an empty page |
| Only untimed tasks | `Anytime` populated, grid still shown, no empty-state copy |
| Loading | reuse `LoadingShimmer` in the same region |
| Error | existing `errorMessage` snackbar path, unchanged |

### 6.8 Accessibility

- Block `contentDescription`: `"Answer Emails, 9:00 AM to 9:30 AM, 30 minutes, open"` / `"completed"`.
- `semantics { role = Role.Button }` on the block; checkbox keeps its own role and label `"Mark Answer Emails complete"`.
- Every block ≥ 48 dp tall including padding (use `minimumInteractiveComponentSize` if the visual height is smaller).
- Contrast: block title on `surfaces.card` in both themes; muted time label stays ≥ 4.5:1 (it already is, via `onSurfaceVariant`).
- Dynamic type: at `fontScale ≥ 1.3`, hour height grows to `72.dp` (measure with `LocalDensity.current.fontScale`) so two-line titles don't clip.

---

## 7. Default view (persistence + Settings)

### 7.1 Store

```kotlin
// core/datastore/HomeViewPreferences.kt  — mirrors ThemePreferences exactly
@Singleton
class HomeViewPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val viewModeKey = stringPreferencesKey("home_view_mode")
    val viewMode: Flow<HomeViewMode> = context.dataStore.data.map { prefs ->
        runCatching { HomeViewMode.valueOf(prefs[viewModeKey] ?: HomeViewMode.LIST.name) }
            .getOrDefault(HomeViewMode.LIST)
    }
    suspend fun setViewMode(mode: HomeViewMode) { context.dataStore.edit { it[viewModeKey] = mode.name } }
}
```

Same DataStore file (`sabihon_prefs`) as `ThemePreferences`, so no second store, no extra file on disk. Bound by constructor injection (it is a concrete `@Singleton`, so no `@Binds` module needed — `ThemePreferences` sets the precedent).

### 7.2 Settings row

`SettingsScreen.kt > APPEARANCE`:

```
Home view            Timeline ›      ← List / Timeline / (Nothing else)
```

A 3-option `ModalBottomSheet` reusing the existing `ThemeOptionRow` pattern; the row's value is the current mode. Tapping updates `HomeViewPreferences` and the row reflects it live.

`ProfileViewModel` already injects `ThemePreferences` and exposes `uiState.theme`; add `homeViewMode` + `setHomeViewMode()` beside it, rather than inventing a second ViewModel for one preference.

### 7.3 Launch behaviour

`HomeViewModel.init` collects `homeViewPreferences.viewMode` and seeds `viewMode` **once**, guarded so a late emission cannot yank the screen out from under a user who toggled in the first 200 ms:

```kotlin
private var viewModeSeeded = false
// in init:
viewModelScope.launch { homeViewPreferences.viewMode.first().let { /* only if !viewModeSeeded */ } }
```

---

## 8. File-by-file change list

| # | File | Change | Est. |
|---|---|---|---|
| 1 | `domain/model/Task.kt` | `durationMinutes: Int?` | 5 m |
| 2 | `data/remote/dto/TaskDto.kt` | field | 5 m |
| 3 | `data/remote/dto/Mappers.kt` | both directions + clamp | 20 m |
| 4 | `data/repository/TaskRepositoryImpl.kt` | `minimalTaskMap` + any `copy(...)` passthrough | 20 m |
| 5 | `domain/security/InputSecurityValidator.kt` | `clampDurationMinutes` + bounds | 10 m |
| 6 | `domain/usecase/AddTaskUseCase.kt`, `UpdateTaskUseCase.kt` | clamp | 15 m |
| 7 | `ui/addedit/*` (state, screen, viewmodel) | duration chips | 2 h |
| 8 | **`ui/home/TimelineLayout.kt`** *(new)* | lanes, gaps, anytime, minute math | 3 h |
| 9 | **`ui/home/TimelineView.kt`** *(new)* | hour items, blocks, gap rows, now-line, a11y | 6 h |
| 10 | `ui/home/HomeUiState.kt` | `HomeViewMode`, `timeline`, actions | 30 m |
| 11 | `ui/home/HomeViewModel.kt` | seed, `SetViewMode`, `buildTimeline`, recompute | 2 h |
| 12 | `ui/home/HomeScreen.kt` | `when(viewMode)` branch, `ListHeader` toggle, `TimelineHeader` | 3 h |
| 13 | **`core/datastore/HomeViewPreferences.kt`** *(new)* | store | 30 m |
| 14 | `ui/profile/ProfileViewModel.kt` | expose + set | 30 m |
| 15 | `ui/settings/SettingsScreen.kt` | `Home view` row + sheet | 1 h |
| 16 | **`test/…/TimelineLayoutTest.kt`** *(new)* | 12 cases (§5) | 2 h |
| 17 | `test/…/MappersTest.kt` | duration round-trip incl. null | 30 m |
| 18 | `docs/` (this file) | keep updated | — |

**Total ≈ 24–26 h including tests.** UI-heavy; the model plumbing is a day, the timeline composable is the other.

---

## 9. Risks & mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| **Timeline breaks the dock/reset machinery** (PRs #71–#72) | High | D2: same `LazyColumn`, unchanged keys; QA the exact regressions from #71/#72 in both modes |
| **Untimed tasks vanish on toggle** | High | D5 Anytime band + test #1 |
| **`String?` → non-null regression** (already shipped 3×) | High | `durationMinutes` is `Int?` by design; run `python3 tools/typecheck.py` (49 files, includes Task/DTO/Mappers) **and** `tools/check_kotlin_syntax.py` before pushing |
| `minimalTaskMap` silently diverges from `toDto()` | Medium | Explicit table row §3.2 + mapper round-trip test |
| Nested-scroll mistake (LazyColumn in LazyColumn) | Medium | D2 rule; reviewer checklist item |
| Per-minute recomposition jank | Medium | Now-line ticker is composable-local (§6.6) |
| DST / timezone drift | Medium | `ZonedDateTime` + `ZoneId` parameter + tests #9–#10 |
| Overlap readability with 4+ concurrent | Low | Lane cap 3 + `+N more` |
| `LazyColumn` scroll jump when switching modes | Low | keep one `listState`; on `SetViewMode`, `rememberSaveable` the previous first-visible index per mode and restore via `LaunchedEffect(viewMode)` (guard with `isScrollInProgress`) |

### Pre-push gate (all three must pass; none need the Android SDK)

```bash
python3 tools/check_kotlin_syntax.py     # nested comments / brackets
python3 tools/check_imports.py           # update-lambda symbols
python3 tools/typecheck.py               # real kotlinc on 49 dependency-free files
```

---

## 10. Delivery phases

| Phase | Scope | Acceptance |
|---|---|---|
| **P0 — Model** | items 1–6, 17 | `durationMinutes` round-trips through `toDto()`/`toDomain()` and `minimalTaskMap`; old docs (no field) still parse; clamps hold |
| **P1 — Engine** | item 8, 16 | all 12 `TimelineLayoutTest` cases green; zero Android imports in `TimelineLayout.kt` |
| **P2 — Timeline UI** | 9, 10 (viewMode only), 12 | Timeline renders a real day; dock/reset behaviour from #71–#72 indistinguishable from list mode; toggle swaps both ways without scroll jump |
| **P3 — Default view** | 11, 13, 14, 15 | Toggle survives app kill; Settings row changes the launch view; `typecheck.py` clean |
| **P4 — Polish** | D9 gaps, D8 overflow, §6.6 now-line, §6.8 a11y, D10 swipe | Gap tap expands; 4 concurrent tasks readable; TalkBack reads a block correctly; swipe changes day ±1 |
| **P5 — Optional** | Duration chips in Add/Edit (item 7); dock swipe-up opens the *day* sheet instead of only the month grid | New tasks can set a real duration |

Ship P0→P2 as one PR only if P2 is fully green; otherwise land P0+P1 first (they are invisible and safe), then P2.

---

## 11. Open questions (answering these changes the plan, not the code layout)

1. **Should the timeline be its own destination after all?** The plan puts it inline on Home because the dock/`selectedDate` machinery already lives there. If the intent is a *separate calendar tab* (TickTick's actual structure), that is a different IA decision and adds a nav task.
2. **Duration input method** — chips only (§3.3), or also a "starts at X, ends at Y" range picker in Add/Edit? Chips cover 90 % with a fraction of the surface.
3. **Should `Anytime` include the *whole* pending backlog** (all undated tasks), or only today's (§4/D5 as written)? Whole backlog risks a wall of text above the grid.
4. **Gap compression default on or off?** Structured compresses; TickTick doesn't. Plan says on, with a setting.
5. **Week timeline** — is "timeline" wanted for a single day only (this plan), or eventually a 7-column week canvas? The latter is a different engine (2D layout, not hour rows) and should be a separate plan.
