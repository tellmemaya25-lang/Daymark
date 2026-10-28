# Daymark — Calendar in the Activity Tab (System Design + Implementation Plan)

**Status:** P0 (model plumbing) and P1 (layout engine + tests) shipped — see §10. P2+ not started.
**Branch base:** `main` @ `bae8547`
**Inspiration:** TickTick day calendar (`image-1`), Structured day timeline (`image-2`), app-switcher ref (`image-3`)

> **Design change, 2026-10-29.** The first version of this plan put the timeline *inline on Home* as a `HomeViewMode`. That is cancelled: the calendar is now a **segment of the Activity tab** (`[Feed][Calendar]`), and **Home is not touched at all**. §2 D1 and D2 say why, and §9 lists what that buys and what it costs.

---

## 1. What is being built

Three things, in dependency order:

| # | Feature | One-line definition |
|---|---|---|
| A | **Calendar surface** | A vertical 24-hour day agenda living inside the Activity tab: each task is a block whose height matches its duration, against an hour grid with a time gutter and a vertical rail. |
| B | **Segment switcher** | A two-segment control `[Feed] [Calendar]` at the top of Activity that swaps the activity feed for the day calendar without leaving the tab. |
| C | **Remembered segment** | A persisted `ActivityViewPreferences` value so Activity reopens on the segment the user left it on, overridable in `Settings > Appearance`. |

### What each reference actually contributes

| Reference | Take this | Leave this |
|---|---|---|
| **TickTick** (`image-1`) | Week strip above the grid; hour labels in a left gutter (`12:00 AM`, `1:00 AM`); blocks rounded and full-width, with a small note icon on the right; the grid continues into empty hours instead of hiding them | Its top-right triple of view icons (list / week / month / agenda) — Daymark needs **one** two-segment control, not four |
| **Structured** (`image-2`) | The rail: circle icon + vertical connector line through every entry; time range + duration on one muted line (`08:11 - 08:41 AM (30m)`); a **dashed rail across long empty gaps**; status rings on the right; completed = filled/coloured | Its infinite zoom-out (day → week → month → year) — out of scope, that is a whole navigation model |
| **App icons** (`image-3`) | Confirmation that this is a *pattern* match, not a pixel match — Daymark keeps its own dark surface, `AccentBlue`, 18–20 dp card radii | Nothing |

### Non-goals (explicitly out of scope for v1)

- **Any change to Home.** No `HomeViewMode`, no toggle in `ListHeader`, no new item keys in Home's `LazyColumn`, no touch to the dock/scroll machinery from PRs #71/#72.
- A 6th bottom-nav destination. The bar is already 5 slots plus a centre FAB, and `MainScaffold.kt` maps routes by string matching; a new tab means touching the pager in `MainTabsScreen.kt` for no gain, since Activity already exists and is under-used.
- Week/month timeline canvases, drag-to-reschedule, drag-to-resize blocks.
- Event duration *editing by dragging* (duration is set in Add/Edit with chips).
- Cross-midnight blocks (a task belongs to the day its `dueAt` falls on).
- Calendar sync (Google/device calendars), shared calendars, recurring *timed* events beyond `RepeatRule`.
- Horizontal day-swipe on the calendar body — see D10.

---

## 2. Decision log

Each decision below had a cheaper alternative that was rejected for a stated reason. These are the ones that will otherwise get re-litigated mid-implementation.

### D1 — The calendar is a **segment of the Activity tab**, not a Home mode and not a new tab

*Rejected:*

- **Inline on Home** (the original plan). Home's `LazyColumn` carries the whole dock/reset apparatus from PRs #71–#72, which reads `listState.layoutInfo` for `WEEK_ITEM_KEY` / `HEADER_ITEM_KEY`. Swapping Home's item model is the single riskiest change available in this app right now, and it buys nothing the Activity tab cannot give.
- **A new bottom-nav tab.** More nav plumbing; Activity already needs a reason to exist beyond a reverse-chronological feed.
- **Inside `MonthlyCalendarSheet`.** That sheet is a month picker; a day agenda in a bottom sheet is capped at ~60 % screen height, which is exactly wrong for a timeline.

*Because:* Activity is the tab a user opens when they are looking at *time* rather than at *one task*. `[Feed]` answers "what did I move forward?", `[Calendar]` answers "what does today look like?" — one screen, one mental model, two lenses. It is also the only option that leaves the app's most fragile surface (Home) completely untouched.

### D2 — Each segment owns its own `LazyColumn`

`ActivityScreen` renders the header (avatar row, title, segment control) and then **one** body: either the feed list or the calendar list. Each body is a separate `LazyColumn` with its own `rememberLazyListState` held in `ActivityScreen`, so scrolling the feed never moves the calendar and vice versa.

*Rejected:* one shared `LazyColumn` that emits feed items or calendar items depending on the segment. That is what the Home design required (Home owns one list and the dock machinery depends on it). Here it is pure cost: the two bodies have nothing in common — different item types, different scroll semantics (the calendar auto-scrolls to a minute offset), and sharing a list state means the first switch lands you at a meaningless scroll position in the other body.

**Consequence:** `HistoryScreen` must be split. It currently owns its own `Scaffold` **and** `BrandHeader` (`HistoryScreen.kt` L86–105) and emits the feed tail from the same list. P2 extracts the feed body so the scaffold, header and segment are owned once by `ActivityScreen`.

**Hard rule for the implementer:** the extracted feed body must keep the existing item keys and the existing hoisted `LazyListState` identity. The feed's scroll behaviour and its `rememberSaveable` state must be indistinguishable from today's.

### D3 — Timed blocks need a duration, so `Task.durationMinutes: Int?` is added (nullable, default `null`)

**Shipped in P0 (PR #76).** Kept here as the rationale, since it constrains everything downstream.

`Task` had only `dueAt: Long?` — no end time. Both references draw blocks whose height *is* the duration, and Structured prints it.

*Rejected:* a global fixed 30-minute block (every meeting looks like every errand; the `(30m)` label becomes a lie); deriving it from sub-task count (arbitrary).

*Because:* nullable + defaulted means **zero migration**. Old documents parse (`TaskDto` has defaults), old tasks render as the fallback duration, and precision arrives per-task as users set it.

Fallback rule (one place, in the layout engine): `durationMinutes ?: 30`, clamped to `5..720`, and clamped again so a block never crosses midnight.

### D4 — Full 24-hour grid, `LazyColumn`-virtualised, auto-scrolled to relevance

*Rejected:* a dynamic min/max window (`06:00 → last task + 30 min`) — it silently hides a 23:30 task, and the window jumping as tasks change makes the scroll position lurch.

*Because:* with hour rows as list items, off-screen hours cost nothing (Compose only composes visible ones). Auto-scroll on open to: the first timed task of the day if the day isn't today, otherwise `now − 90 min` (so "now" sits ~⅔ down the viewport, which is where both references put it).

### D5 — Untimed tasks go to a pinned **Anytime** band; they are never dropped

Home's `HomeTaskSorter` includes `dueAt == null` tasks in `HomeFilter.TODAY`. If the calendar only renders `dueAt` blocks, **the tasks a user has not scheduled yet are the ones that disappear** — which is precisely backwards, since those are the tasks that still need a decision.

**Therefore:** an `Anytime` band renders above the first hour row, containing every untimed pending task for the selected day plus the day's overdue-and-open ones. It is the top row of the calendar body, so it cannot scroll out from under a day with many blocks. Pinned by test #1 and #14.

### D6 — One persisted value is both the default **and** the last choice

`ActivityViewPreferences.segment: Flow<ActivitySegment>` in DataStore, written when the user flips the segment **and** when they pick from Settings.

*Rejected:* a separate "pin default" flag (three states, two sources of truth, no user-visible benefit); a launch-only default with an ephemeral segment (users then think the switcher is broken because it forgets).

*Because:* "it opens the way I left it" is what both reference apps do, and the Settings row makes the stored value discoverable and overridable without hunting for the switcher.

### D7 — The segment control is the only chrome the switcher needs

Inside Activity the segment sits directly under the title, as a two-segment pill matching the existing `[Today][Upcoming][All]` control's metric. It is **not** a replacement for anything: the feed keeps its own filters, and the calendar has no sort order at all (the clock is the sort), so no sort chip appears in calendar mode.

*Because:* a list/timeline toggle works best where there is nothing else to toggle. The feed's sort chip belongs to the feed; the calendar's time axis belongs to the calendar.

### D8 — Overlaps use lane packing, capped at 3 lanes

Sort blocks by start, then assign each to the first lane whose previous block ended at or before this start. `laneCount` per cluster sets block width: `width / laneCount` minus 6 dp gutter. A 4th+ concurrent block collapses into a `+N more` chip that opens the day list sheet.

*Rejected:* stacking with offset (unreadable past 2), or letting blocks overlap (illegible).

**Shipped in P1:** `TimelineLayout` assigns `lane` and `laneCount`, and puts overflow blocks in `TimelineDay.overflow` with `lane = -1`, `laneCount = 0`. Rendering the `+N more` chip is the P4 UI half.

### D9 — Long empty runs collapse into a **gap row** (Structured's dashed rail)

Any run of ≥ 3 consecutive empty hours renders one row: dashed vertical rail, `09:15 → 22:00 · 12h 45m` in muted type, and a tap-to-expand. Default **on**, with a `Settings > Appearance > Compress empty hours` switch (default on) so it can be turned off by people who want the literal grid.

P1 pinned two details the UI must respect, because they are easy to "fix" into bugs later:

- **A leading gap folds too.** A day whose first task is at 08:00 opens with one `Gap(0, 480)` row, not eight empty hours. The calendar body should then auto-scroll past it (D4), not the engine suppress it.
- **Runs shorter than the threshold are not folded.** A lone empty 23:00 hour stays a normal hour row. Folding it would hide the fact that the grid continues to midnight.

### D10 — No horizontal day-swipe on the calendar body

*Rejected:* swiping the calendar left/right to move ±1 day. The Activity page is a page of the `HorizontalPager` in `MainTabsScreen.kt` L182 (`beyondViewportPageCount = 1`) — a horizontal drag inside the page fights the pager, and the arbitration needed to make both feel right (`awaitHorizontalTouchSlopOrCancellation` plus a policy for which one wins at the edges) is a permanent source of "the app swipes when I meant to scroll" bugs.

*Instead:* day selection is `WeekStrip` (taps, plus its existing week paging) and `MonthlyCalendarSheet` for jumping further. This is also what the Activity tab's vertical scroll needs — one unambiguous gesture axis.

*If it is ever wanted:* it belongs in P5 as a separate, opt-in `nestedScroll` connection that the pager advertises, not as a `pointerInput` on the timeline.

---

## 3. Data model change (shipped — P0)

### 3.1 `Task` (domain)

```kotlin
// domain/model/Task.kt
/**
 * How long the task occupies. Null means "not specified"; the timeline falls back to
 * 30 minutes rather than hiding the task.
 */
val durationMinutes: Int? = null,
```

**Non-negotiable:** do **not** touch the nullability of `title` / `description` (non-null `String`). Three separate builds broke on `String?` being fed where `String` was required — see PRs #73–#75.

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
| `domain/security/InputSecurityValidator.kt` | `MIN_DURATION_MINUTES = 5`, `MAX_DURATION_MINUTES = 720`, `clampDurationMinutes(Int?)` | one shared bound instead of three copies |
| `ui/addedit/AddEditViewModel.kt` → `saveTask()` | **`durationMinutes = original?.durationMinutes`** (shipped P0), then the chip row + state + save (P5) | `saveTask()` rebuilds `Task(...)` from the form, so without the preserve line **every edit silently clears the duration** |

**Firestore rules: no change required.** `validTaskPayload()` validates only `title` and `description` (`firestore.rules` L43–47), so an extra numeric field passes. Optional hardening: add `data.durationMinutes == null || (data.durationMinutes is number && data.durationMinutes >= 5 && data.durationMinutes <= 720)`.

### 3.3 Add/Edit UI (P5)

Under the due-time row:

```
Duration    [15m] [30m] [45m] [1h] [1h30] [2h] [—]
```

- Disabled and hidden when no time is set (there is nothing to attach a duration to) — with a hint: *"Add a time to place this on the timeline."*
- `—` clears back to `null` (fallback governs).
- Values are constant `Int`, no free text → no new validation surface.

---

## 4. State & wiring

### 4.1 Segment enum

```kotlin
// ui/activity/ActivityUiState.kt
/** Which body the Activity tab renders under its title. */
enum class ActivitySegment { FEED, CALENDAR }
```

### 4.2 `CalendarViewModel`

`CalendarViewModel` is deliberately separate from `HistoryViewModel`, which is a 4-dependency object (`taskRepository`, `firestore`, `auth`, `avatarStore`) built to page an activity log. The calendar needs the task list and a selected date; it does not need the activity log at all.

```kotlin
data class CalendarUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val day: TimelineDay = TimelineDay.Empty,   // TimelineDay(emptyList(), emptyList(), null)
    val isLoading: Boolean = false,
    val daysWithTasks: Set<Long> = emptySet()   // for MonthlyCalendarSheet dots
)
```

Recompute triggers: the `allTasks` flow, `selectedDate`, `ZoneId.systemDefault()`. A 1-minute ticker exists **only** for the now-line and lives inside the composable (§6.6), never in this state — a per-minute `StateFlow` emission would recompose the whole day.

`daysWithTasks` is already computed for Home's week strip; reuse that derivation rather than adding a second one.

### 4.3 Screen split (`HistoryScreen` → `ActivityScreen` + bodies)

Today:

```
HistoryScreen            Scaffold + snackbar + LazyColumn
  ├ item  BrandHeader
  ├ item  "Your activity." intro block        L92–105, L107–135
  └ items FeedRow…                          L221
```

After P2:

```
ActivityScreen(onTaskClick, onProfileClick)          ui/activity/ActivityScreen.kt  (new)
  Scaffold
    ├ BrandHeader                                    unchanged composable
    ├ title block                                    per-segment copy
    ├ SegmentControl([Feed][Calendar])
    ├ snackbar host
    └ when (segment)
        FEED     -> ActivityFeedBody(...)             extracted from HistoryScreen
        CALENDAR -> CalendarScreen(...)               ui/activity/CalendarScreen.kt (new)

ActivityFeedBody      verticalScroll-free LazyColumn, existing item keys, existing FeedRow/EmptyFeed
CalendarScreen        LazyColumn: WeekStrip, Anytime band, hour rows, gap rows
```

`HistoryScreen.kt` survives as a thin wrapper (or is renamed outright — the file is only reachable from `MainTabsScreen.kt` page 2 and from `BottomNavBar`'s `"activity"` route match, so there is nothing else to update).

**Per-segment copy.** The feed's intro ("A look back at what you've moved forward.") is wrong above a calendar. In calendar mode the intro becomes the date line ("Your day." + `Thu 29 Oct`); in feed mode it stays exactly as it is today.

**Deep links and `onNavigateToHistory`.** `MainTabsScreen` already routes Home's and Settings' "history" actions to `goToPage(2)`. Those should open the segment the user last used (D6), not force `FEED` — with one exception: any future entry point that means "the log" should pass `ActivitySegment.FEED` explicitly. Keep the pager API as `goToPage(2)` and let `ActivityScreen` restore the persisted segment.

---

## 5. Layout engine — `ui/activity/TimelineLayout.kt` (shipped — P1)

Modelled on `HomeTaskSorter`: no Compose, no Android, no clock of its own. The date, the zone and "today" are all parameters, so a test pins a day instead of depending on when it runs.

```kotlin
object TimelineLayout {
    const val DEFAULT_DURATION_MINUTES = 30
    const val MAX_LANES = 3
    const val GAP_THRESHOLD_HOURS = 3

    fun build(
        tasks: List<Task>,
        day: LocalDate,
        zone: ZoneId,
        today: LocalDate = LocalDate.now(zone),
        fallbackDurationMinutes: Int = DEFAULT_DURATION_MINUTES,
        maxLanes: Int = MAX_LANES,
        gapThresholdHours: Int = GAP_THRESHOLD_HOURS
    ): TimelineDay
}

data class TimelineBlock(
    val taskId: String,
    val title: String,
    val startMinute: Int,      // minutes from midnight, 0..1440
    val endMinute: Int,        // exclusive, ≤ 1440
    val lane: Int,             // 0-based column; -1 means "overflowed"
    val laneCount: Int,        // columns in this overlap cluster; 0 when overflowed
    val isCompleted: Boolean,
    val priority: Priority,
    val categoryId: String?,
    val subTasksDone: Int,
    val subTasksTotal: Int
) {
    val durationMinutes: Int get() = endMinute - startMinute
}

sealed interface TimelineRow {
    data class Anytime(val tasks: List<Task>) : TimelineRow
    data class Hour(val hour: Int, val blocks: List<TimelineBlock>) : TimelineRow
    data class Gap(val fromMinute: Int, val toMinute: Int) : TimelineRow
}

data class TimelineDay(
    val rows: List<TimelineRow>,
    val overflow: List<TimelineBlock>,
    val firstTimedMinute: Int?
) {
    val anytime: List<Task>
    val timedBlocks: List<TimelineBlock>
    val gaps: List<TimelineRow.Gap>
    val isEmpty: Boolean
}
```

### Algorithm

1. **Filter** — drop `isDeleted`. Nothing else is dropped: an untimed task is *moved*, never removed.
2. **Bucket** — for each task with `dueAt != null` whose local date equals `day` in `zone`, compute `startMinute = hour*60 + minute` via `Instant.atZone(zone).toLocalDateTime()`. Never `millis % 86_400_000` — that is wrong on DST days.
3. **Duration** — `endMinute = start + (durationMinutes ?: fallback)`, clamped `≥ 5` and `≤ 1440`.
4. **Lane-pack** — sort by `(startMinute, endMinute, title)`; assign each block the leftmost lane whose previous block ended at or before this start; a block finding no lane goes to `overflow`. `laneCount` is set per transitively-overlapping cluster (`maxLane + 1`), not globally, so a lone afternoon task still gets full width.
5. **Hour rows** — mark each hour a block covers; walk 0…23.
6. **Fold gaps** — runs of empty hours with length ≥ `gapThresholdHours` become one `Gap` row. Folding only happens when at least one timed block exists: a day with no timetable is better drawn as an empty grid (with the empty-state copy over it) than as a single `00:00 → 24:00` row, which reads like the timeline failed to load.
7. **Anytime** — untimed tasks only on the day that is *actually today*; overdue-and-still-open tasks likewise. Showing an undated backlog on every day would make it look scheduled.

### Tests — `app/src/test/java/com/sabihon/todo/TimelineLayoutTest.kt` (shipped)

14 cases, all pinned to Tuesday 2026-10-27 in UTC.

| # | Case | Expectation |
|---|---|---|
| 1 | two untimed tasks | both in `Anytime`; no hour row carries them |
| 2 | task at 23:30, 90 min | `endMinute == 1440`, never 1480; `durationMinutes == 30` |
| 3 | three overlapping tasks | lanes `0,1,2`; `laneCount == 3`; no overflow |
| 4 | the same three + a fourth | 4th in `overflow` with `lane == -1`; `timedBlocks.size == 3`; `laneCount` stays 3 |
| 5 | 09:00–22:00 empty | `Gap(0, 480)` and `Gap(540, 1320)`; 23:00 stays a normal hour row |
| 6 | `gapThresholdHours = 20` | no `Gap`; 24 hour rows, 22 of them empty |
| 7 | `durationMinutes = null` | 30-minute block |
| 8 | `durationMinutes = 0` / `-5` / `9999` | 5 / 5 / 720-then-cut-to-660 at midnight |
| 9 | DST spring-forward in `Europe/Berlin` | 09:00 stays at minute 540 and in the `09` row; 24 wall-clock rows |
| 10 | tasks on another day / in another zone | excluded |
| 11 | completed task | still placed, flagged `isCompleted` |
| 12 | habit occurrence (`seriesId`, 2 sub-tasks) | placed like any task; `45`, `1/2` progress |
| 13 | empty day | `isEmpty`, 24 hour rows, no `Gap`, `firstTimedMinute == null` |
| 14 | overdue task | joins `Anytime` on today, and on **no other day**; an undated task likewise |

**How they run offline.** `tools/junit_shim/` contains a `@Test` annotation, an `Assert` object with the eight assertions these suites use, and a reflection `Runner`. Maven Central is unreachable in the agent sandbox, so the real test files are compiled against the shim and executed by the runner. On a normal machine Gradle's JUnit runs the same files unchanged; nothing in `build.gradle` points at `tools/`. Four of the fourteen originally asserted the wrong thing (the pre-dawn gap, the short-run rule, and the `9999`-minute arithmetic) — the engine was right and the expectations were corrected, which is worth knowing when reading the git history.

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

### 6.2 Structure (calendar segment)

```
┌──────────────────────────────────────────────┐
│ (avatar row — unchanged BrandHeader)         │
│  Small steps. Real progress.                 │
│  Your day.                                   │
│  Thu 29 Oct · 3 tasks · 2h 15m               │  ← per-segment intro
│  ┌───────────┬───────────┐                   │
│  │   Feed    │ Calendar  │                   │  ← segment control
│  └───────────┴───────────┘                   │
├──────────────────────────────────────────────┤
│  M T W T F S S          ‹  Oct  ›            │  ← WeekStrip (tap = select)
├──────────────────────────────────────────────┤
│ ANYTIME                                      │
│ ○ Rise and Shine            ☆  Daily         │  ← Anytime band (D5)
│ ○ Wind Down                                  │
├──────────────────────────────────────────────┤
│ 08:00 │ ●────── 08:00 - 08:30 · 30m          │
│       │  Rise and Shine                   ○  │
│ 09:00 │ ●────── 09:00 - 09:30 · 30m          │
│       │  Answer Emails                    ●  │
│  ⋮    │ ⋮  (dashed rail, tappable)           │  ← Gap row (D9)
│ 21:00 │ ●────── 21:00 - 22:00 · 1h           │
│ 22:00 │  Wind Down                        ○  │
└──────────────────────────────────────────────┘
```

The tab bar and the FAB come from `MainScaffold`, not from this screen — the calendar body must not add a second FAB.

### 6.3 Hour rows and blocks

The calendar body is the top-level `LazyColumn` of this segment, so hour rows are ordinary items — the nesting problem the Home design had to solve does not exist here.

A block is drawn as **one clipped segment per hour row it covers**:

- For hour `h`, the row's slice of a block is `height = min(endMinute, (h+1)*60) - max(startMinute, h*60)` minutes × `PX_PER_MIN` (`64.dp / 60`), positioned at `(max(startMinute, h*60) - h*60) × PX_PER_MIN` from the row top.
- The block's *content* (rail dot, time label, title, checkbox) renders only in its first visible segment; later segments continue the block surface and its left rail, so a two-hour task reads as one card interrupted by an hour label.
- Row height stays 64 dp; the block surface is clipped by the row, never allowed to overflow into the next item.

This keeps virtualisation (off-screen hours cost nothing) **and** lets a `Gap` row replace a run of hour items, which a single absolute-positioned grid canvas would not.

### 6.4 Block content (first segment only)

```
●────── 09:00 - 09:30 · 30m          ← 12.sp, onSurfaceVariant
  Answer Emails                     ← 14.sp semibold, maxLines 2, ellipsis
  ⌁ Work at Home · 3/4 steps        ← 11.sp, category + sub-task progress (optional)
                                ○   ← CircleCheckbox, right-aligned, 48 dp target
```

- Whole block: `clickable` → task detail; `combinedClickable(onLongClick)` → the existing action sheet (`showActionSheet` + `selectedTask`), so long-press behaves identically to the feed and to Home.
- Checkbox: `onCheckedChange` **must** dispatch the existing toggle-complete use case — never write `isCompleted` locally. `ToggleCompleteUseCase` refuses while sub-tasks are open, and the rest of the app already relies on that answer.

### 6.5 Calendar header

`WeekStrip` (`core/ui/components/WeekStrip.kt`, already shared and already imported by `DockedWeeklyCalendarBar`) with `compact = false`, plus a month label that opens `MonthlyCalendarSheet` for long jumps.

`MonthlyCalendarSheet` currently lives in `ui/home/HomeCalendarDock.kt` L279 but is already self-contained (`selectedDate`, `daysWithTasks`, `onSelectDate`, `onDismiss`). Move it to `core/ui/components/MonthCalendarSheet.kt` — a pure move, no behaviour change — so both Home and Activity use one sheet. One wart to fix while moving: it reads `remember { LocalDate.now() }` instead of accepting `today`, which makes it untestable and puts the clock inside a composable. Add a `today: LocalDate` parameter with a default.

### 6.6 Now-line

Rendered only when `selectedDate == LocalDate.now(zone)`: `2.dp` `AccentBlue` line at `nowMinute × PX_PER_MIN`, with a `6.dp` dot at the left end. It is driven by a composable-local ticker:

```kotlin
var nowMinute by remember { mutableIntStateOf(nowMinuteOfDay()) }
LaunchedEffect(selectedDate) {
    while (true) { delay(60_000); nowMinute = nowMinuteOfDay() }
}
```

Not in `CalendarUiState` — a per-minute `StateFlow` emission would recompose the entire day.

### 6.7 Empty & loading states

| State | Render |
|---|---|
| No tasks at all that day | `Anytime` hidden, grid shows, centred `EmptyState` variant: *"Nothing scheduled. Tap a day above, or ＋ to add one."* — the grid stays visible so the calendar reads as a calendar, not an empty page |
| Only untimed tasks | `Anytime` populated, grid still shown, no empty-state copy |
| Loading | reuse `LoadingShimmer` in the same region |
| Error | the existing `errorMessage` snackbar path, unchanged |

### 6.8 Accessibility

- Block `contentDescription`: `"Answer Emails, 9:00 AM to 9:30 AM, 30 minutes, open"` / `"completed"`.
- `semantics { role = Role.Button }` on the block; the checkbox keeps its own role and label `"Mark Answer Emails complete"`.
- Every block ≥ 48 dp tall including padding (use `minimumInteractiveComponentSize` if the visual height is smaller — a 15-minute block is only 16 dp).
- Contrast: block title on `surfaces.card` in both themes; muted time label stays ≥ 4.5:1 (it already is, via `onSurfaceVariant`).
- Dynamic type: at `fontScale ≥ 1.3`, hour height grows to `72.dp` (measure with `LocalDensity.current.fontScale`) so two-line titles don't clip.
- The segment control announces itself as a tab list: `"Feed"`, `"Calendar"`, with the selected one marked, so TalkBack users can find it without swiping through the whole header.

---

## 7. Remembered segment (persistence + Settings)

### 7.1 Store

```kotlin
// core/datastore/ActivityViewPreferences.kt  — mirrors ThemePreferences exactly
@Singleton
class ActivityViewPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val segmentKey = stringPreferencesKey("activity_segment")
    val segment: Flow<ActivitySegment> = context.dataStore.data.map { prefs ->
        runCatching { ActivitySegment.valueOf(prefs[segmentKey] ?: ActivitySegment.FEED.name) }
            .getOrDefault(ActivitySegment.FEED)
    }
    suspend fun setSegment(segment: ActivitySegment) { context.dataStore.edit { it[segmentKey] = segment.name } }
}
```

Same DataStore file (`sabihon_prefs`) as `ThemePreferences`, so no second store, no extra file on disk. Bound by constructor injection (it is a concrete `@Singleton`, so no `@Binds` module needed — `ThemePreferences` sets the precedent).

### 7.2 Settings row

`SettingsScreen.kt > APPEARANCE`:

```
Activity opens       Feed ›        ← Feed / Calendar
```

A small `ModalBottomSheet` reusing the existing `ThemeOptionRow` pattern; the row's value is the current segment. Tapping updates `ActivityViewPreferences` and the row reflects it live.

`ProfileViewModel` already injects `ThemePreferences` and exposes `uiState.theme`; add `activitySegment` + `setActivitySegment()` beside it, rather than inventing a second ViewModel for one preference.

### 7.3 Launch behaviour

The segment is read **once**, before the first body is composed, so the tab never flashes Feed and then snaps to Calendar:

```kotlin
val segment by viewModel.segment.collectAsStateWithLifecycle()
```

`ActivityViewModel` seeds its state from `activityViewPreferences.segment.first()` inside `init`, guarded so a late emission cannot yank the screen out from under a user who flipped the switch in the first 200 ms (same guard shape as the theme seed).

---

## 8. File-by-file change list

| # | File | Change | Status | Est. |
|---|---|---|---|---|
| 1 | `domain/model/Task.kt` | `durationMinutes: Int?` | ✅ P0 | 5 m |
| 2 | `data/remote/dto/TaskDto.kt` | field | ✅ P0 | 5 m |
| 3 | `data/remote/dto/Mappers.kt` | both directions + clamp | ✅ P0 | 20 m |
| 4 | `data/repository/TaskRepositoryImpl.kt` | `minimalTaskMap` + `copy(...)` passthrough | ✅ P0 | 20 m |
| 5 | `domain/security/InputSecurityValidator.kt` | `clampDurationMinutes` + bounds | ✅ P0 | 10 m |
| 6 | `domain/usecase/AddTaskUseCase.kt`, `UpdateTaskUseCase.kt` | clamp | ✅ P0 | 15 m |
| 7 | `ui/addedit/AddEditViewModel.kt` | preserve line in `saveTask()` (chips are P5) | ✅ P0 | 10 m |
| 8 | **`ui/activity/TimelineLayout.kt`** *(new)* | lanes, gaps, anytime, minute math | ✅ P1 | 3 h |
| 9 | **`test/…/TimelineLayoutTest.kt`** *(new)* | 14 cases (§5) | ✅ P1 | 2 h |
| 10 | `tools/junit_shim/*` *(new)* | offline JUnit subset + reflection runner | ✅ P1 | 30 m |
| 11 | **`core/ui/components/MonthCalendarSheet.kt`** *(new)* | move from `HomeCalendarDock.kt` L279, add `today` param | P2 | 40 m |
| 12 | `ui/home/HomeCalendarDock.kt` | delete the moved sheet, import the new one | P2 | 10 m |
| 13 | **`ui/activity/ActivityScreen.kt`** *(new)* | scaffold, header, segment, body switch | P2 | 3 h |
| 14 | `ui/history/HistoryScreen.kt` | split: keep the feed body, drop the scaffold/header | P2 | 2 h |
| 15 | **`ui/activity/CalendarScreen.kt`** *(new)* | week strip, anytime band, hour rows, blocks, gap rows | P2 | 5 h |
| 16 | **`ui/activity/CalendarViewModel.kt` + `CalendarUiState.kt`** *(new)* | selected date, `TimelineDay`, `daysWithTasks` | P2 | 2 h |
| 17 | `ui/navigation/MainTabsScreen.kt` | page 2 → `ActivityScreen` | P2 | 15 m |
| 18 | **`core/datastore/ActivityViewPreferences.kt`** *(new)* | store | P3 | 30 m |
| 19 | `ui/profile/ProfileViewModel.kt` | expose + set | P3 | 30 m |
| 20 | `ui/settings/SettingsScreen.kt` | `Activity opens` row + sheet | P3 | 1 h |
| 21 | `ui/activity/*` | `+N more`, gap tap-to-expand, now-line, a11y, auto-scroll | P4 | 3 h |
| 22 | `docs/` (this file) | keep updated | — | — |

**Remaining ≈ 21–23 h** (P0 + P1's ~6 h are done). The `.kt` volume is small; the risk is concentrated in item 14 (splitting a 340-line screen without changing how it scrolls).

---

## 9. Risks & mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| **Splitting `HistoryScreen` changes feed behaviour** (scroll position, `rememberSaveable` state, snackbar host) | High | D2 hard rule: same item keys, same hoisted `LazyListState`, scaffold stays owned by `ActivityScreen`. Add a before/after manual check to the PR description |
| **Untimed tasks vanish from the calendar** | High | D5 Anytime band, pinned by tests #1 and #14 |
| **`String?` → non-null regression** (already shipped 3×) | High | `durationMinutes` is `Int?` by design; run `tools/check_kotlin_syntax.py`, `tools/check_imports.py`, `tools/typecheck.py` (50 files) and `tools/check_write_parity.py` before pushing |
| Pager swipe collides with a day-swipe gesture | High **if D10 is ignored** | No horizontal drag on the calendar body; day changes via `WeekStrip` taps only |
| `minimalTaskMap` silently diverges from `toDto()` | Medium | Table row §3.2 + `tools/check_write_parity.py` (8 fields) + mapper round-trip test |
| Segment switcher loses the other body's scroll position | Medium | One `rememberLazyListState` per segment, held in `ActivityScreen`; never shared |
| Per-minute recomposition jank | Medium | Now-line ticker is composable-local (§6.6) |
| DST / timezone drift | Medium | `Instant.atZone(zone)` + injected `ZoneId` + tests #9–#10 |
| A 15-minute block is unreadable at 16 dp | Medium | `minimumInteractiveComponentSize` + compact block variant (time on one line, title ellipsised) |
| Overlap readability with 4+ concurrent | Low | Lane cap 3 + `+N more` (engine already returns `overflow`) |
| Moving `MonthlyCalendarSheet` breaks Home's month picker | Low | Pure move, no signature change except the added `today` default; Home compiles against the same call |

### Pre-push gate (all four must pass; none need the Android SDK)

```bash
python3 tools/check_kotlin_syntax.py     # nested comments / brackets
python3 tools/check_imports.py           # update-lambda symbols
python3 tools/check_write_parity.py      # toDto() vs minimalTaskMap()
python3 tools/typecheck.py               # real kotlinc on 50 dependency-free files
```

Tests: compile the test file against `tools/junit_shim/` and run `sandbox.Runner` (see §5).

---

## 10. Delivery phases

| Phase | Scope | Acceptance |
|---|---|---|
| **P0 — Model** ✅ | items 1–7 | `durationMinutes` round-trips through `toDto()`/`toDomain()` and `minimalTaskMap`; old docs (no field) still parse; clamps hold. Shipped in PR #76: 28 assertions executed, parity check added |
| **P1 — Engine** ✅ | items 8–10 | all 14 `TimelineLayoutTest` cases green, 0 Android imports in `TimelineLayout.kt`; typecheck 50 files |
| **P2 — Activity shell + calendar** | items 11–17 | `[Feed][Calendar]` switches both ways with no scroll jump; the calendar renders a real day (anytime band, hour rows, blocks, gap rows); the feed is visually identical to today; the month sheet opens from both tabs; **Home is byte-for-byte unchanged** |
| **P3 — Remembered segment** | items 18–20 | the segment survives an app kill; the Settings row changes what opens; `typecheck.py` clean |
| **P4 — Polish** | item 21 | gap tap expands; 4 concurrent tasks readable via `+N more`; now-line tracks the clock; TalkBack reads a block correctly; auto-scroll lands on the first block / now |
| **P5 — Optional** | duration chips in Add/Edit (§3.3); "compress empty hours" switch; opt-in day-swipe via `nestedScroll` | new tasks can set a real duration |

P1 is merged on its own branch commit and safe to ship alone: it is invisible, pure and fully tested. P2 is the first user-visible phase and the only one with real regression risk.

---

## 11. Open questions (answering these changes the plan, not the code layout)

1. **Should the calendar be reachable from Home?** Today Home has a docked week strip and a month sheet. A "open in Calendar" affordance (long-press the strip, or a row in the day sheet) would be one line in `MainTabsScreen` (`goToPage(2)` + `ActivitySegment.CALENDAR`) — but it needs a decision on whether it *forces* the calendar segment or respects the remembered one. Deferred to P3.
2. **Duration input method** — chips only (§3.3), or also a "starts at X, ends at Y" range picker in Add/Edit? Chips cover 90 % with a fraction of the surface.
3. **Should `Anytime` include the *whole* pending backlog** (all undated tasks), or only today's (§D5 as implemented)? The whole backlog risks a wall of text above the grid, and the engine currently answers "today only" — which is tested, so changing it is a deliberate edit to two assertions, not a drift.
4. **Gap compression default on or off?** Structured compresses; TickTick doesn't. Plan says on, with a setting.
5. **Week timeline** — is "calendar" wanted for a single day only (this plan), or eventually a 7-column week canvas? The latter is a different engine (2D layout, not hour rows) and should be a separate plan.
