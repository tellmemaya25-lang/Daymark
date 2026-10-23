# Swipe & drag motion — research and implementation plan

Status: sections B, C and D are implemented. Section A (the `anchoredDraggable` refactor)
is deliberately deferred — see the note in §5.

### Decisions (confirmed)

1. **Full swipe → delete**, guarded by the existing "Task deleted / Undo" snackbar.
2. **Bidirectional reveal stays**; a full swipe in either direction runs the primary
   action, because Delete is the end-most action on both sides.
3. **No wrap-around** on the tab swipe: at Settings a leftward swipe does nothing, and at
   Home a rightward swipe does nothing.

---

## 1. What exists today

### Task row — `core/ui/components/SwipeableTaskRow.kt`

| Parameter | Value | Line |
|---|---|---|
| Reveal distance | ±160 dp | `:82-83` |
| Positional threshold | ±80 dp (50% of reveal) | `:80-81` |
| Fling threshold | 400 dp→px treated as px/s | `:85` |
| Settle spring | `dampingRatio 0.58`, `StiffnessMediumLow` | `:93` |
| Close spring | `DampingRatioMediumBouncy`, `StiffnessMedium` | `:94` |
| Drag | hand-rolled `Animatable` + `Modifier.draggable` | `:189-212` |
| Overscroll | hard `coerceIn(-160, 160)` — no give at the ends | `:191` |
| Icons | fade `0.1→1`, scale `0.78→1` with reveal | `:144-146` |
| Haptic | one tick when the threshold is crossed | `:106-108` |

### Tab swipe — `ui/navigation/MainScaffold.kt`

| Parameter | Value | Line |
|---|---|---|
| Commit distance | 64 dp | `:107` |
| Max drag | 220 dp | `:108` |
| Content translation | `drag * 0.18` | `:148` |
| Content alpha | fades to 0.85 while dragging | `:149` |
| Commit test | position only — velocity ignored | `:117-129` |
| Release | always springs back to 0, *then* the nav transition runs | `:133-135` |

---

## 2. What the platform guidance actually says

**Material lists.** A row swipe reveals actions; **a full swipe commits the primary action**,
and that primary action must be the final, end-aligned one. Hidden actions always need a
second access path (we have the long-press sheet — good). Material: *"The primary action must
be the final end-aligned option. A full swipe triggers this action."*
[m3.material.io/components/lists](https://m3.material.io/components/lists/guidelines)

**Threshold feedback.** The classic spec wants the affordance to *change* at the threshold —
"the radial fill indicates that a threshold has been passed and the action will be committed"
[m2.material.io/design/interaction/gestures](https://m2.material.io/design/interaction/gestures.html).
Our icons only scale; nothing tells you when you have "made it".

**The three-zone model.** The only published numbers for swipe-to-reveal are the Wear
guidelines: under 50% snaps back, 50–75% stays partially revealed with actions showing, over
75% commits the primary action and removes the row
[developer.android.com/design/ui/wear/guides/…/swipe-to-reveal](https://developer.android.com/design/ui/wear/guides/m2-5/components/swipe-to-reveal).

**Use `anchoredDraggable`, not `draggable`/`swipeable`.** `swipeable` is deprecated in favour
of Foundation's `anchoredDraggable` for exactly this class of component
[developer.android.com/…/drag-swipe-fling](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling).
Its semantics are what we are currently hand-rolling badly:

- `velocityThreshold` — above it, the next anchor wins **regardless of position**
  [AnchoredDraggableState reference](https://developer.android.com/reference/kotlin/androidx/compose/foundation/gestures/AnchoredDraggableState)
- `positionalThreshold` — below the velocity threshold, decided by distance; defaults to 50%
  of the travel ([AnchoredDraggableDefaults](https://composables.com/jetpack-compose/androidx.compose.foundation/foundation/objects/AnchoredDraggableDefaults/api))
- anchors + `snapAnimationSpec` + `decayAnimationSpec` + `FlingBehavior` now live on the
  **modifier**, not the state (config moved in 1.7)
- `settle()` no longer takes a velocity; use `FlingBehavior.performFling`
  ([release notes](https://android.googlesource.com/platform/frameworks/support/+/502d84f2b0f83d024c4a2e1c05487ce324e22d02%5E!/))

**One open row at a time.** Swipe-reveal implementations keep a single shared state per list so
opening a row closes the previous one; per-item state cannot coordinate
([DragToReveal write-up](https://olubunmi-alegbeleye.medium.com/android-gave-me-two-states-my-designer-wanted-three-so-i-built-a-library-50c4c2055e15)).

**Edges belong to the system.** Horizontal gestures that start at a screen edge fight the
system back gesture; the Android guidance is to keep reveals single-direction and avoid
edge-initiated drags ([Wear SwipeToReveal API](https://composables.com/jetpack-compose/androidx.wear.compose/compose-material3/components/SwipeToReveal/api)).

---

## 3. Gap analysis

### Rows

1. **No anchor model.** `Animatable` + manual `when` in `onDragStopped` (`:196-212`) re-implements
   what `AnchoredDraggableState` already does, and skips decay — a fast flick ends abruptly
   instead of decaying into the anchor.
2. **Hard clamp at ±160 dp** (`:191`). Past the reveal point the card stops dead; there is no
   rubber band, so the gesture feels like it hits a wall.
3. **No full-swipe commit.** Material expects a deep swipe to *do* the primary action.
4. **No mutual exclusion.** Two, three, five rows can all be left open at once.
5. **Rows stay open while scrolling.** `LazyColumn` scroll should close the reveal; today it
   stays open and rides up the screen.
6. **Threshold has no visual commit cue** — only an icon scale that saturates at 80 dp.
7. **`onToggleComplete` is dead** (`:77`) — declared, never used. Either swipe-to-complete or
   delete the parameter.
8. **No `confirmValueChange` veto**, so a completed task can be swiped open pointlessly.

### Tab swipe

1. **Position-only commit.** A quick 30 dp flick does nothing; a slow 70 dp drag commits.
   Needs `velocityThreshold` semantics.
2. **Double motion.** On commit we `animateTo(0)` *and* navigate, so the content snaps back
   while the incoming screen slides — two motions fighting.
3. **No edge exclusion** — a swipe starting at the left edge is also the system back gesture.
4. **No vertical/horizontal arbitration** beyond touch slop; diagonal drags can start a tab
   swipe while the list is scrolling.
5. **Alpha fade** (`:149`) is not in any spec and muddies the card colours; the translation
   alone carries the intent.
6. **Fragile coordination with row drags** — we rely on the child consuming first. That works,
   but it is implicit and will break if a screen adds a `LazyRow`.

---

## 4. Proposed motion spec

These are the numbers I would implement; all in one place so they can be tuned together.

```
Row reveal
  anchor closed           0 dp
  anchor revealed        ±160 dp        (two 56 dp targets + padding)
  positional threshold    50% of travel  (80 dp) — AnchoredDraggableDefaults
  velocity threshold      125 dp/s       (≈375 px/s @3x)
  snap spec               spring(dampingRatio 0.9, stiffness MediumLow)   // no bounce past the anchor
  decay spec              splineBasedDecay
  overscroll              rubber band: excess * 0.35, capped at 28 dp
  full-swipe commit       1.6× the reveal distance (≈256 dp) → primary action (delete + undo)
  threshold cue           icon budget: colour → AccentBlue at 100%, plus a 6 dp→10 dp
                          radial ring around the icon; haptic tick on the crossing

Tab swipe
  commit distance         25% of screen width  (≈96 dp on a 390 dp screen)
  commit velocity         ≥ 800 dp/s
  live translation        1:1 up to 96 dp, then rubber band at 0.4
  alpha                   none
  edge exclusion          24 dp from either edge never starts a tab swipe
  on commit               content stays where the finger left it; navigation
                          transition picks up from there (no snap-back)
```

---

## 5. Implementation plan

Four PRs, each independently shippable. A and B are behaviour-preserving; C and D change feel.

### A. Row swipe → `AnchoredDraggableState` — DEFERRED

> **Why it is not in this pass.** Foundation is mid-churn on this API: constructor
> configuration (`positionalThreshold` / `velocityThreshold` / animation specs) is deprecated
> in favour of per-modifier config, `settle()` lost its velocity parameter, and
> `AnchoredDraggableState(...)` exists in two shapes depending on the exact version. With no
> compiler in this environment that is a coin-flip on handing back another red build — which
> is exactly how the `clip()` regression happened. Nothing below depends on it: the shipped
> behaviour runs on `Modifier.draggable` with the same thresholds.

**Files:** `SwipeableTaskRow.kt` (rewrite of the gesture half), new
`core/ui/components/SwipeRevealState.kt`.

```kotlin
enum class RevealValue { Closed, Start, End }

val state = remember {
    AnchoredDraggableState(
        initialValue = RevealValue.Closed,
        positionalThreshold = { distance -> distance * 0.5f },
        velocityThreshold = { with(density) { 125.dp.toPx() } },
        snapAnimationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        decayAnimationSpec = rememberSplineBasedDecay(),
    )
}
Modifier
    .anchoredDraggable(state, Orientation.Horizontal)
    .offset { IntOffset(state.requireOffset().roundToInt(), 0) }
```

Anchors set in `Modifier.onSizeChanged` (or `layout {}`): `Start at -revealPx`, `Closed at 0f`,
`End at +revealPx`.

- delete `offsetX`, `settleSpec`, `closeSpec`, `close()`, `onDragStopped` blocks
- `revealFraction` becomes `state.progress`-derived, or `abs(requireOffset()) / revealPx`
- keep the visual layer untouched in this PR so the diff is reviewable

**Done when:** drag, flick, and click-away-to-close behave as today, minus the wall at ±160 dp.

*Skipped — the equivalent behaviour ships on `draggable`:* anchors ±160 dp, 50% positional
threshold, 800 dp/s velocity threshold, rubber band at 35%, spring settle.

### B. Coordination and thresholds

**Files:** `SwipeableTaskRow.kt`, `HomeScreen.kt`, `CategoriesScreen.kt`, `SearchScreen.kt`,
new `RevealCoordinator.kt`.

- `RevealCoordinator` — one `MutableStateFlow<RevealValue>`-style owner per list; opening row B
  animates row A to `Closed`.
- `LaunchedEffect(listState.isScrollInProgress) { if (scrolling) closeAll() }` in each list host.
- Threshold cue: ring + colour change at `progress >= 1f`, haptic once per crossing
  (guard with a `remember` boolean so it does not re-fire on recomposition).
- `confirmValueChange = { it == RevealValue.Closed || !task.isCompleted }`.

**Done when:** only one row can be open, scrolling closes it, and the crossing is felt and seen.

### C. Full swipe commits the primary action

**Files:** `SwipeableTaskRow.kt`, `HomeScreen.kt` (undo snackbar already exists for delete).

- Add a fourth anchor pair at `±1.6 * revealPx` (`FullStart`/`FullEnd`), or use
  `FlingBehavior`-driven `onFullSwipe` if we prefer the Wear model.
- Material says the primary action is the **end-aligned** one. Today the order is
  `[Edit] [Delete]`; delete is end-aligned, so a full swipe commits **delete**, guarded by the
  existing "Task deleted / Undo" snackbar.
- On commit: haptic `LongPress`, animate the row's height to 0, then dispatch the delete.
- Decide whether swipe-to-complete deserves its own gesture — see §7.

**Done when:** deep swipe deletes with undo, and the row animates out rather than vanishing.

### D. Tab swipe refinement

**Files:** `MainScaffold.kt`.

- Track drag **velocity** (use `VelocityTracker` inside `awaitEachGesture`, or
  `draggable(state = rememberDraggableState{}, onDragStopped = { v -> … })` and keep the
  velocity it hands us).
- Commit when `abs(travel) >= 25% width || abs(velocity) >= 800.dp/s`.
- **No snap-back on commit:** leave `translationX` at its current value, navigate, and reset it
  when `currentRoute` changes (the nav transition then continues the same direction).
- Rubber band past 96 dp instead of a hard stop at 220 dp.
- Drop the alpha fade.
- Edge exclusion: ignore drags whose `down.position.x` is within 24 dp of either edge
  (`awaitFirstDown().position`).
- Prefer `awaitHorizontalTouchSlopOrCancellation` so vertical scrolls win ties.

**Done when:** a flick switches tabs, the motion is continuous into the next screen, and swipes
from the screen edge still go to the system back gesture.

---

## 6. Risks and test matrix

| Risk | Mitigation |
|---|---|
| Row drag vs `LazyColumn` scroll | horizontal-only `anchoredDraggable`; vertical stays with the list |
| Row drag vs tab swipe | child consumes first; add an explicit test on Home with 3+ cards |
| System back gesture | 24 dp edge exclusion on the tab swipe; row reveal only starts inside the content inset |
| "Remove animations" accessibility setting | springs collapse automatically; verify the settle still lands on an anchor |
| RTL | anchors swap sides — assert `RevealValue.Start` renders on the right |
| TalkBack | hidden actions need semantics: current long-press sheet is the documented alternative; add `contentDescription` to the revealed icon buttons (present) and a custom action ("Show actions") |
| Low-end devices | `splineBasedDecay` + spring only, no per-frame allocation in the drag path |

Manual matrix before merge: Home (3 cards), Projects → project list, Search results, one-handed
edge swipe, long card titles, completed cards, TalkBack on, animation scale 0, dark + light.

---

## 7. Open questions

1. **Full swipe = delete?** It is what Material prescribes (primary action end-aligned) and the
   undo snackbar exists, but it is destructive. Alternative: full swipe = complete, and delete
   stays a deliberate tap. My recommendation: **delete with undo**, matching the icon order.
2. **Keep bidirectional reveal?** Material leans single-direction; both directions work today
   and users are used to it. Recommendation: keep, but only commit full-swipe on the end side.
3. **Swipe-to-complete as a third gesture?** Right now `onToggleComplete` is dead code. Options:
   remove it, or make a short swipe on a *pending* row complete it. I would remove it — three
   gestures on one card is one too many.
4. **Tab order for swiping.** Settings is last; a leftward swipe there does nothing. Should it
   wrap to Home?
