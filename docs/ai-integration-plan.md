# AI integration — research and plan

Status: **plan only, no code written.** Four decisions need a call (§7). Read §2 first if
you only read one section — it contains a deadline that is one week away.

---

## 1. What this app already has to build on

| Asset | Why it matters for AI |
|---|---|
| `Task`: title, description, categoryId, priority, dueAt, reminderAt, subTasks, repeat, streak | Every field an AI feature would fill already exists and is already rendered. No schema work. |
| `AddEditTaskScreen` + `AddEditViewModel` | The natural place for "suggest steps" and "clean up this title" — the sub-task list is already there with checkboxes and a progress bar. |
| `Category`/`Projects` | Gives the model a closed set of projects to classify into, instead of inventing labels. |
| `Activity` feed (`CREATED/UPDATED/COMPLETED/REOPENED/DELETED/RESTORED/PURGED`) + `HistoryStats` (weekly count, streak, daily counts) | Enough real data for a weekly review that is *about the user*, not generic advice. |
| Firebase Auth + Firestore, Hilt, `Result<T>`, `MutableStateFlow` UI state | The AI layer slots in as one more repository behind an interface. |
| `kotlinx-serialization-json` already on the classpath | Structured output can be parsed and validated without a new dependency. |
| Grouped `Settings` screen (post-#39) | One row is all an opt-in AI toggle needs. |

Constraints from the build file:

- `minSdk 24` — meets Firebase AI Logic's requirement (API 24+).
- **Firebase BoM is `33.12.0`.** The AI library is `firebase-ai`, which only exists from **BoM 34.0.0** (`firebase-vertexai` was removed in 34). So integrating AI means a BOM bump, which is a breaking-change hop for *all* Firebase libraries — auth, Firestore, storage, messaging, analytics. That is a real risk item, not a one-line change (§6).
- **No App Check anywhere.** `grep -i appcheck` returns nothing.

---

## 2. The deadline nobody plans for

**From 2 November 2026 — six days from today — Firebase AI Logic requires App Check
enforcement.** It is applied automatically, cannot be switched off, and any request without a
valid token is rejected, including builds that have no production attestation provider wired
up.
([firebase.google.com/docs/ai-logic/faq-and-troubleshooting](https://firebase.google.com/docs/ai-logic/faq-and-troubleshooting),
[docs/ai-logic/app-check](https://firebase.google.com/docs/ai-logic/app-check))

Practical meaning: App Check is not an optional hardening step later — it is **part of phase
zero**. Play Integrity for release builds, the debug provider for local work, and the debug
token kept out of git (the repo is public). If we skip it, the app ships an AI feature that
works on the day it is built and stops working on the day it is reviewed.

---

## 3. Options, scored for *this* app

Fit = how much of the existing UI/data it reuses. Risk = what it can get wrong in front of a user.

| # | Feature | What it does | Fit | Effort | Risk | Verdict |
|---|---|---|---|---|---|---|
| 1 | **Natural-language quick add** | "call mom friday 5pm urgent" → title, dueAt, priority, category, repeat | High — fills existing `AddEditTask` fields | S | Low (user confirms before save) | **Build first** |
| 2 | **Break into sub-tasks** | "prep the workshop" → 5 subtasks, inserted into the existing list | Very high — sub-task UI exists | S | Low (editable chips, user keeps/removes) | **Build second** |
| 3 | **Weekly review digest** | "12 done, 2 moved, 4-day streak — 3 things to nudge" on the Activity page | High — `HistoryStats` + feed already computed | M | Medium (needs honest numbers, not vibes) | **Build third** |
| 4 | **Triage stale tasks** | Suggests category/priority for untagged or overdue tasks, one row at a time | Medium | M | Medium (silent re-writing is a trust killer) | Later, after 1–3 |
| 5 | **On-device proofread / rewrite** (ML Kit GenAI, Gemini Nano) | Polish a title or description, no network, no cost | Medium | S | Low | Nice, but see caveat below |
| 6 | **Chat with your tasks / voice capture** | Ask questions, or dictate a Rambles-style dump | Low — needs a new surface and a trust model | L | High | **Not now** |

Notes behind the scores:

- #1 and #2 are the two features users of comparable apps actually praise. Todoist's
  natural-language parser is repeatedly described as the reason people stay, and its
  sub-task breakdown ("Task Assist") is the free-tier feature reviewers recommend enabling
  first. Its newer voice capture (Ramble) is Gemini-backed but is a bigger surface than this
  app needs.
  ([todoist review 2026](https://www.saner.ai/blogs/todoist-review), [setup guide](https://www.asianefficiency.com/task-management/todoist-ai-setup-guide/))
- #5 is genuinely attractive — on-device means no data leaves the phone, works offline, and
  costs nothing to run. **But the device list is narrow**: ML Kit GenAI (Summarization /
  Proofreading / Rewriting / Prompt) is limited to specific hardware — Pixel 9/10 series,
  Galaxy S25/S26, some OnePlus/OPPO/vivo/Motorola/Honor models.
  ([ML Kit GenAI device support](https://developers.google.com/ml-kit/genai)) If your test
  phone is not on that list, the feature is invisible on your own device, which makes it a
  poor *first* AI feature for a solo project. Keep it as a later, privacy-first path with a
  cloud fallback.
- #6 is where trust and scope go to die: an assistant that can misread your task list and
  confidently tell you the wrong thing. It also needs its own screen, its own empty/error
  states, and a conversation about data retention. Defer.

---

## 4. Recommendation

**Cloud-first via Firebase AI Logic, opt-in, one feature at a time — with a deterministic
fallback so nothing breaks offline.**

```
Phase 0  plumbing      BoM bump · firebase-ai · App Check (Play Integrity + debug) ·
                       AiTaskService interface + FirebaseAiTaskService · settings toggle ·
                       timeouts, single-flight, "AI unavailable" state
Phase 1  quick add     parse a sentence into the task editor's existing fields
Phase 2  sub-tasks     "Suggest steps" inside the editor's sub-task section
Phase 3  weekly review one card on the Activity page
Phase 4  on-device     ML Kit rewrite/proofread where supported, cloud fallback otherwise
```

Why not start with #5 despite it being free: it cannot be verified on hardware you may not
own, and its quality ceiling ("3 bullet points", short-message proofreading) is below what
quick-add needs.

### Cost and quota reality

On the no-cost Spark plan the Gemini Developer API has a free tier; the Firebase AI Logic SDK
adds a default per-user limit of 100 RPM on top of the provider's own limits. Typical provider
free-tier numbers are single-digit to low-teens RPM and 1,000–1,500 requests/day for Flash and
Flash-Lite models
([quotas](https://firebase.google.com/docs/ai-logic/quotas), [pricing](https://firebase.google.com/docs/ai-logic/pricing)).
For one user — or a hundred — that is ample. It stops being ample the moment a feature fires on
every keystroke, which is why §5 forbids that.

**The honest trade-off of the free tier:** data sent on the free tier may be used to improve
Google's products; the paid tier is excluded from that. For an app whose payload is your task
titles, that is the user's decision, not a default. Hence: opt-in, minimal payload, and a
sentence in the toggle's supporting text that says exactly this.

---

## 5. Design rules the implementation must follow

1. **AI never writes without a confirmation.** Every output lands in a field the user can see
   and edit before saving. No silent re-categorising, no background rewrites.
2. **Minimum payload.** Quick add sends the sentence. Sub-task suggestions send the title and
   description only. The weekly review sends *counts and streaks*, not task titles, unless the
   user opts into "include task names".
3. **Structured output, then validate.** Ask for JSON matching a schema
   (`responseMimeType = "application/json"`), deserialise with `kotlinx-serialization`, then
   validate every value against the app's own enums — an unknown priority, a category id that
   doesn't exist, a date in 1970. Reject the whole response rather than half-applying it.
4. **One request at a time.** A `Mutex` per feature plus a `debounce`, and the button disables
   while in flight. This app has already been burned by double-submits (#44 — five identical
   `Reopened` rows), and a paid-per-call endpoint deserves the same suspicion.
5. **Time-boxed.** 15 s timeout, and the UI says "AI is taking too long" rather than spinning
   forever. The existing `withTimeoutOrNull` + `WriteOutcome` pattern in
   `TaskRepositoryImpl` is the precedent to copy.
6. **Always a way out.** Quick add has a deterministic fallback parser (dates, "p1", "@work")
   so the field still works offline and with AI switched off. The fallback is also the unit-test
   surface.
7. **No new global state.** `AiTaskService` is a Hilt-bound interface, called from the existing
   ViewModels. Same shape as every other repository in the app.
8. **Errors are quiet.** A failed AI call is a one-line inline note ("Couldn't read that — you
   can still fill the fields by hand"), never a dialog, never a snackbar that blocks.

---

## 6. Files: new vs. changed

**Phase 0 (plumbing)**

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | `firebaseBom` 33.12.0 → 34.x **or** 35.x (§7A); add `firebase-ai`, `firebase-appcheck-playintegrity`, `firebase-appcheck-debug` |
| `app/build.gradle.kts` | the three dependencies; nothing else |
| `app/src/main/java/com/sabihon/todo/SabihonApp.kt` | install App Check (debug provider in `BuildConfig.DEBUG`, Play Integrity otherwise) — must run before any Firebase use |
| `domain/ai/AiTaskService.kt` *(new)* | interface: `parseQuickAdd`, `suggestSubTasks`, `weeklyReview` |
| `domain/ai/AiModels.kt` *(new)* | `AiTaskDraft`, `AiWeeklyReview`, and the `@Serializable` response DTOs |
| `data/ai/FirebaseAiTaskService.kt` *(new)* | the only file that imports `com.google.firebase.ai`; timeouts, validation, mapping to domain |
| `data/ai/QuickAddParser.kt` *(new)* | deterministic fallback: dates, times, `p1..p4`, `@project`, `every monday` |
| `di/AiModule.kt` *(new)* | binds the interface; provides the model name as a constant |
| `core/datastore/AiPreferences.kt` *(new)* | one boolean, default **false** |
| `ui/settings/SettingsScreen.kt` | one `GroupedRow` + switch under a new `AI` section, with the disclosure line |

**Phase 1 (quick add)**

| File | Change |
|---|---|
| `ui/addedit/AddEditViewModel.kt` | `onParseTitle()`, `aiDraft` state, apply-to-fields action |
| `ui/addedit/AddEditTaskScreen.kt` | one trailing icon in the existing title field + a suggestion strip above the fields; **no layout rework** |
| `app/src/test/.../QuickAddParserTest.kt` *(new)* | fallback parser: dates, priorities, categories, nonsense input |
| `app/src/test/.../AiResponseMappingTest.kt` *(new)* | valid JSON, malformed JSON, unknown enum, out-of-range date, extra keys |

**Phase 2 (sub-tasks)** — `AddEditViewModel.suggestSubTasks()`, a "Suggest steps" text button
in the existing sub-task section, and a test that suggestions merge without duplicating what
the user already typed.

**Phase 3 (weekly review)** — `ui/history/HistoryViewModel.kt` (build the input from
`HistoryStats`), one card `ui/history/WeeklyReviewCard.kt`, cached per ISO week in DataStore so
opening Activity twice doesn't spend two requests.

**Untouched in every phase:** `TaskRepository*`, `AuthRepository*`, `HomeScreen`,
`SabihonNavHost`, theme files, Firestore rules, the data model.

---

## 7. Decisions I need

**A. Firebase BoM target.** 34.x is the smallest hop that has `firebase-ai`; 35.x is current and
slightly cleaner (the `GenerativeBackend.googleAI()` syntax in both). Either way it is a
breaking-change update to auth/firestore/storage/messaging/analytics, so it needs a smoke test
sign-in → add task → complete → activity → delete → restore before anything else lands on top.
**Recommendation: bump in its own PR with no features attached**, so a regression is provably
not the AI code.

**B. Model.** The docs' current examples use `gemini-3.x-flash` / `flash-lite`; the exact id and
its free-tier availability change often. **Recommendation: `flash-lite` for all three phases**
(cheap, fast, plenty for classification and short JSON), held in one constant and overridable
from Remote Config later — not sprinkled through the code.

**C. Privacy posture.** Confirm you are comfortable with the free tier's data terms for *task
text*, or whether sub-task suggestions and the weekly review should send titles at all. This
decides whether Phase 3 is "counts only" (safe, less personal) or "counts + names" (better
advice, more data out).

**D. Where quick add lives.** Two options:
1. a "magic" action inside the existing `AddEditTask` title field — zero new screens, keeps
   the current flow exactly as it is; or
2. a one-line quick-add field on Home that opens the editor pre-filled — faster capture
   (which is the whole point of the feature), but it changes the Home layout you have been
   protective of.
**Recommendation: 1 now, 2 only if you want capture speed to lead.** Option 2 can be added
later without touching the AI layer.

---

## 8. Testing and verification, honestly

- Pure-JVM tests (JUnit5/MockK/Truth already in the project) can cover: the fallback parser,
  JSON → `Task` mapping including every malformed case, the single-flight guard, and the
  weekly-review input builder. These are the tests that matter, and they need no network.
- `AiTaskService` is an interface, so ViewModel tests inject a fake and assert that a draft is
  applied only after confirmation, and that a failure shows the inline note.
- **Not testable here:** the actual model call. That needs a device with the Firebase project
  configured, App Check tokens, and the free-tier quota. **This sandbox has no JDK or Android
  SDK**, so — as with every change so far — I can write the code and the JVM tests but cannot
  run Gradle or hit the endpoint. Verification of the live call would be yours:
  run once in debug, confirm the debug App Check token appears in Firebase Console >
  App Check, then rebuild in release to confirm Play Integrity registers.

---

## 9. What I would *not* do

- No API key in the app, no direct `generativelanguage.googleapis.com` calls from the client.
  Firebase AI Logic authorises through a Google-managed service account precisely so the key
  never ships.
- No AI in the "complete a task" path — that is the app's core loop and must stay instant and
  offline.
- No background AI (a Worker "tidying" tasks). It spends quota the user did not ask for and
  touches data they did not send.
- No chat surface in v1. If it ever happens, it needs its own trust design, not a `TextField`
  over the task list.
