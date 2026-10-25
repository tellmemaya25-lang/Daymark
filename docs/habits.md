# Habits (repeating tasks)

Shipped across three commits on one branch. Agreed scope: **Daily + Weekly (weekday
chips)**, and **one missed occurrence is forgiven, two resets the streak**.

## Model

| field | type | meaning |
|---|---|---|
| `repeat` | `RepeatRule` (`NONE`/`DAILY`/`WEEKLY`) | stored by name in Firestore |
| `repeatDays` | `List<Int>` | 1 = Monday … 7 = Sunday, weekly only |
| `streak` | `Int` | consecutive completed occurrences |
| `lastCompletedDay` | `Long?` | `LocalDate.toEpochDay()` of the last completion |
| `seriesId` | `String?` | template id; null on the original task |

Everything defaults to a one-off task, so documents written before this existed read
back unchanged and nothing needs migrating.

## The rule that matters

Streaks count **missed occurrences**, not calendar days. A Monday/Thursday habit
completed on both days has a three-day calendar gap and has missed *nothing*.

```
missed 0   -> streak + 1
missed 1   -> streak + 1    (one forgiven)
missed >=2 -> streak = 1
```

`RepeatScheduler` is pure `java.time` — no Android types, no clock reads, every
function takes the date it should reason about.

## Completion

`ToggleCompleteUseCase` completes the task, writes the streak, and spawns the next
occurrence:

- the spawned id is `<seriesId>_<epochDay>`, so completing the same occurrence twice
  rewrites one document instead of stacking duplicates
- `dueAt` keeps the original local time of day; a reminder keeps its original gap
  before the due time
- sub-tasks on the spawn start unchecked
- `spawnOccurrence()` does not log an activity entry — a habit ticking over is not
  something the user did, and the feed should not fill with "Created" rows

## Known limitations

- **Reopening a habit does not remove the occurrence it spawned.** Un-ticking by
  accident leaves both today's task and the next one. Deleting the spawned row is
  the manual fix.
- **Editing preserves the streak** (the form carries `streak`, `lastCompletedDay`,
  `seriesId` and completion state through a save) but does not re-compute it. Change
  a daily habit to weekly and the existing streak simply continues.
- **No monthly rule**, no "skip today", no habit history chart.
- The old stats path (`HistoryViewModel.calculateStreak`, `completionRate = 0.75f`)
  is untouched and still wrong; it is behind the new activity feed, so it is
  currently invisible rather than safe.
