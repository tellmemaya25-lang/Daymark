# Smart fill — plan

One sentence should be able to fill **every** field of the editor: title, description,
due date, due time, sub-tasks, repeat (none / daily / weekly + weekdays), priority, and
the reminder — and the reading of the sentence should be less literal than it is today.

Today's draft carries title, dueAt, priority, category, repeat, repeatDays. Everything
below is the delta.

---

## 1. New draft fields

| Field | Type | Meaning |
|---|---|---|
| `description` | `String?` | Only when the sentence clearly separates a note from the task |
| `subTasks` | `List<String>` | Titles only; ids are minted in the ViewModel |
| `reminder` | `Boolean?` | `null` = the sentence said nothing. Not the same as `false` |
| `reminderLeadMinutes` | `Int?` | How long before `dueAt`; `0` means at the due time |

The tri-state on `reminder` matters: "don't touch this" and "turn it off" are different
instructions, and the editor must not switch off a reminder the user already set.

## 2. Rules the pipeline keeps

1. **Nothing is invented.** Categories still come only from the user's own lists;
   weekdays stay 1–7; a reminder with no due date is dropped, not silently scheduled.
2. **Offline first, always.** The on-device parser runs before the call, and every field
   the model leaves empty falls back to it.
3. **Fill only what is empty** — in the ViewModel, not in the draft. The draft says what
   the sentence meant; the editor decides whether the user's own words win.
4. **Nothing saves.** The strip still offers Undo, and Undo now restores description,
   sub-tasks and the reminder too.

## 3. When the reminder is on

This is the one place the feature could annoy someone, so the rule is narrow:

- **Off** unless something in the sentence says otherwise.
- **On** when the sentence asks for it ("remind me", "don't forget", "alert me"), or when
  the model judges the task time-bound enough that missing it would matter.
- **Lead time** is `0` (at the due time) unless the sentence says "30 minutes before".
- **No due date, no reminder.** Dropped in `sanitize`, so nothing can be scheduled
  against a time that does not exist.

## 4. Fuzzy and semantic reading

Two halves, because they can do different things.

**On-device (deterministic, tested)** — the vocabulary improves, not the reasoning:

- `noon`, `midnight`, `tonight`
- `this morning` / `this afternoon` / `this evening`
- hour as a word: `five pm`, `eight thirty`
- `half past 5`, `quarter past 5`, `quarter to 6`
- `in 20 minutes`, `in 3 hours`, `in 2 days`
- `remind me`, `remind me 30 minutes before`
- a weekday with a part of day: `friday evening` → 18:00

**Cloud (semantic)** — the prompt is rewritten to say, in order of authority: ignore
filler ("around", "sometime", "maybe", "later"), resolve relative phrases against the
given "now", split sub-tasks out of one sentence, treat a clause after a dash or comma as
the description, and answer `null` rather than guessing.

**What on-device still will not do**, deliberately: split sub-tasks out of prose, or
understand "the usual". Splitting is a semantic judgement, and a wrong split is worse
than no split.

## 5. Files

| File | Change |
|---|---|
| `domain/ai/AiTaskDraft.kt` | four new fields, `hasDetails` widened |
| `data/ai/FirebaseAiTaskService.kt` | new wire shape + rewritten prompt |
| `domain/usecase/ParseQuickAddUseCase.kt` | sanitize and merge the new fields |
| `data/ai/QuickAddParser.kt` | the vocabulary in §4 |
| `ui/addedit/AddEditViewModel.kt` | apply fields, widen the undo snapshot, richer summary |
| `app/src/test/.../QuickAddParserTest.kt` | new cases for §4 |
| `app/src/test/.../ParseQuickAddUseCaseTest.kt` | new: reminder dropped without a due date, sub-tasks capped and de-duplicated |

## 6. Honest limits

- Cannot be compiled or run here — no JDK, no Android SDK. Tests are written, not run.
- The cloud half needs the App Check debug token registered, and enforcement is
  2026‑11‑02. Until then a debug build will mostly show "Filled offline".
