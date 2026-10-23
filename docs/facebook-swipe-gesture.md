# The Facebook swipe gesture — research and how to build it

Research note, 2026-10-23. Sources are linked inline; every claim about Meta's own model
comes from their design-system spec, not from reverse-engineering a screenshot.

---

## 0. "The Facebook swipe" is three different gestures

Before any code, this needs naming, because Daymark only wants one of them.

| # | Gesture | Where it appears | Model |
|---|---|---|---|
| **A** | **Row swipe-to-reveal actions** | Friends list, notifications, Groups members, Inbox rows | Row slides, a panel of verbs stays pinned at the edge; **row rests open** |
| **B** | **Swipe-to-dismiss / swipe-up drawer** | Story viewer, composer sheet, the old Android "swipe up on the nav bar to open the drawer" | Surface follows the finger, released past a threshold it leaves |
| **C** | **Swipe between top-level tabs** | Not Facebook on Android — this is **Instagram's** model (ViewPager behind the bottom bar) | Pages track the finger, bottom bar follows |

Facebook's own Android app has historically *not* paged between bottom-nav tabs; it put the
drawer behind a swipe-**up** on the bar instead
([mobigyaan, 2017](https://www.mobigyaan.com/facebook-swipe-up-navigation-drawer-test-android)).
Instagram is the one that added "swipe across all the tabs"
([minter.io, 2025](https://minter.io/blog/instagram-launches-new-app-navigation-system-update/)).

**Daymark already has B-adjacent tab swiping and C's row swiping.** The gesture worth calling
"Facebook" is **A** — and it is meaningfully different from what `SwipeableTaskRow` does today.

---

## 1. The model, from Meta's own spec

Meta's open-source design system (`facebook/astryx`) has a live spec record for exactly this
component: **[spec(AST-057): swipe actions on Item](https://github.com/facebook/astryx/pull/6908)**.
It explicitly ports "a shipping product's row where the swipe verbs are the menu's own
handlers". The decisions worth copying:

**DEC-1 — actions are data, per side.** `{ label, icon, onActivate, variant, hasRemoval }` with
`variant: 'neutral' | 'accent' | 'destructive'` (default **accent**). The label names the
*state the verb reaches*, not the verb ("Archive", not "Archiving"). Crucially the same objects
feed the row's overflow menu — one vocabulary, two entry points.

**DEC-2 — two models, `reveal` is the default.**
- `reveal`: the row **rests open**; a *long* drag fires the last entry.
- `commit`: slides out, fires on release, rests nothing.

**DEC-3 — one row open at a time, with no group state.** "a pointer anywhere outside a resting
row closes it — no group state, no hook." This is the detail most implementations get wrong.

**DEC-4 — no hover reveal.** Gesture only on touch.

**DEC-5 — anatomy.** Panels are absolutely-positioned children of the row root. The drag writes
a value on the root; **the root translates by it and each panel translates by its negation** —
so the panel appears pinned to the edge while the row exposes it. The group clips with
`overflow-inline: clip` (not `hidden`, which would create a scroll container and fight
vertical scrolling).

**Accessibility (AR1–AR3)** — a resting panel is *real buttons*; a closed panel is *out of the
tree*; a `commit` panel is presentational only. And the invariant above all of it: **"hover or a
gesture is never the only path to an essential action."** Every swipe verb must also exist in
the row's menu.

That last rule is the one that makes the whole thing safe — and it is what the LogRocket UX
survey independently recommends: swipe actions cause accidental actions, hidden functionality
and discoverability problems unless a visible alternative exists
([logrocket.com](https://blog.logrocket.com/ux-design/accessible-swipe-contextual-action-triggers/)).

---

## 2. Why `reveal` (rest-open) beats what Daymark ships today

Today `SwipeableTaskRow` behaves like a **spring-loaded peek**: release past 50% and the card
parks at ±160 dp showing two icons; a deeper swipe deletes. That is a *commit* model with a
resting state — the worst of both:

- You must **hold and release precisely** to see the actions, then **tap a small icon** at the
  edge. Two gestures for one intent.
- The icons are 56 dp targets *behind a moving card*, so they are only fully available when the
  row is parked — and a parked row blocks the next row's gesture.
- Nothing communicates "this row is open" beyond its position.

Facebook's model reads better because the **row becomes a two-pane surface**: content pane
slides, action pane is fixed. Release lands half-open or fully open by *duration of travel*, and
once open it stays as a stable target.

---

## 3. Mechanics in Compose

### 3.1 Three (or four) anchors

```kotlin
enum class Reveal { Closed, Start, End }

val anchors = DraggableAnchors {
    Reveal.Start  at -panelWidthStartPx
    Reveal.Closed at 0f
    Reveal.End    at panelWidthEndPx
}
```

`panelWidth` is the *sum of the action buttons on that side*, not a magic 160 dp — the panel
defines the anchor, exactly as DEC-5 describes.

Use `AnchoredDraggableState` with `positionalThreshold = { it * 0.5f }` and
`velocityThreshold = 125.dp` (the documented defaults; above the velocity threshold the next
anchor wins regardless of position —
[reference](https://developer.android.com/reference/kotlin/androidx/compose/foundation/gestures/AnchoredDraggableState)).
Anchors are computed in `Modifier.layout { }` from the measured width, as in the
[official sample](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-composables).

### 3.2 Panels that stay pinned

```kotlin
Box(Modifier.clipToBounds()) {                    // group clip
    // action pane: counter-translates, so it reads as pinned to the edge
    Row(Modifier
        .align(if (isStart) Alignment.CenterStart else Alignment.CenterEnd)
        .offset { IntOffset(-offset.roundToInt(), 0) }) { actions.forEach { ActionButton(it) } }

    // content pane: moves with the drag
    Row(Modifier.offset { IntOffset(offset.roundToInt(), 0) }) { TaskCard() }
}
```

Note this is the opposite of what `SwipeableTaskRow` does now: it keeps one background box and
*aligns* it to the exposed edge. Alignment-swapping makes the icons jump when you cross zero;
counter-translation is smooth through the whole drag.

### 3.3 One row at a time, without group state

Meta's trick: an open row closes on any pointer *outside* it. In Compose that is a
`pointerInput` on the list that closes the current row on `awaitFirstDown()` — simpler and more
robust than a shared coordinator, and it also closes the row when you scroll or tap another row.

Daymark's `RevealCoordinator` gets the same outcome but only because every row is wired to it.
The pointer-outside rule needs no per-row wiring.

### 3.4 The rest-open → fire path

- Panel buttons are real buttons. Tapping fires `onActivate` and the row either springs back
  (`hasRemoval = false`) or holds out at the edge (`hasRemoval = true`).
- A **long drag** past the full panel width fires the **last** entry without a tap. This is the
  "power gesture" — it is what makes the row feel fast once learned, and it is opt-in by
  travel distance, not by force-press.
- After a `hasRemoval` action the row is the caller's problem: the row springs back, the list
  animates it out. The spec deliberately leaves exit animation out of scope.

### 3.5 Vertical scroll arbitration

`anchoredDraggable` only consumes horizontal deltas; vertical stays with `LazyColumn`. Do **not**
add a `detectHorizontalDragGestures` wrapper around the list (Daymark's tab swipe does this) —
it competes with the rows and with the system back gesture.

### 3.6 Edge exclusion still applies

Same rule as the tab swipe work: drags starting within ~24 dp of a screen edge belong to the
system back gesture. Rows inside a 20 dp content inset already satisfy this.

---

## 4. Gap analysis — Daymark today vs the Facebook model

| Aspect | `SwipeableTaskRow` today | Facebook model |
|---|---|---|
| Release behaviour | parks at ±160 dp, or full swipe deletes | rests open at *panel width*, or long-drag fires last action |
| Anchor source | hard-coded 160 dp | sum of the action buttons |
| Panel rendering | one background box, alignment swaps | two panes, panel counter-translates |
| Close trigger | coordinator + list scroll | pointer anywhere outside |
| Actions | icons only (edit, delete) | colour-coded verbs with labels, default accent |
| Menu parity | long-press sheet exists ✅ | required by invariant ✅ |
| Full swipe | delete (end action) | fires the *last* entry |
| Which side | both, same two icons | per-side verb sets |
| Rest state a11y | icons present only while open | closed panel out of the tree, open panel real buttons |

Daymark is already closer than most: it has the long-press alternative, one-open-row
coordination, velocity commits and a rubber band. What it lacks is the **panel anatomy** and the
**rest-open semantics**.

---

## 5. Plan

**P1 — panel anatomy (pure visual, no behaviour change).** Replace the background box with two
pinned panes that counter-translate; size the anchors from the buttons instead of 160 dp. Delete
the `bgAlignment` switch.

**P2 — rest-open semantics.** Keep the row parked after release; close on tap-anywhere-outside
(a `pointerInput` on the list root) and on scroll; keep the existing coordinator as a fallback.

**P3 — verbs as data.** Introduce `RowAction(label, icon, variant, hasRemoval, onActivate)` so the
long-press sheet and the swipe panel are generated from one list — that is Facebook's whole
point, and it removes the "swipe does something the menu doesn't" risk.

**P4 — long-drag fires the last entry.** Only after P1–P3; it is the least discoverable and the
easiest to trigger by accident.

Suggested order matches risk: P1 is invisible to behaviour, P2 changes feel, P3 changes the API
of the row, P4 is a flourish.

---

## 6. Open questions for Daymark

1. **Which gesture do you actually mean** — the row reveal (A), the dismissible sheet (B), or
   tab paging (C)? Most of this document is A.
2. **Should a parked row survive a scroll?** Facebook's answer: no. Daymark currently closes on
   scroll too, so this is already right.
3. **Do swipe verbs replace the long-press sheet**? Facebook's invariant says the gesture can
   never be the only path, so the sheet stays — but both should be generated from the same verb
   list (P3).
4. **Delete on long drag, or complete?** Facebook fires the *last* entry. In Daymark's
   `[Edit][Delete]` order that is delete — which matches the full-swipe decision already made
   in `docs/swipe-motion-plan.md`.


---

## 7. Follow-up — the two gestures actually asked for

Clarified after the first pass: **swipe-to-navigate** (between tabs) and
**swipe-to-refresh** (pull down to reload). Both are now implemented; the row-reveal
research above stays as background for a later pass.

### Swipe-to-navigate — what shipped

A true pager (Instagram's ViewPager-behind-the-bar) would mean collapsing the four tab
destinations into a single destination hosting a `HorizontalPager`. That fights three things
here: per-tab back stacks, the row swipe inside the pager (both are horizontal drags), and
the system back gesture at the left edge. Not worth it for four tabs that already animate.

What ships instead is a *commit-and-slide* model with pager-like feedback:

- 1:1 finger tracking to 25% of the width, then a rubber band (40 dp of give)
- vertical/horizontal arbitration left to touch slop, so lists still scroll
- 24 dp edge exclusion keeps the system back gesture working
- **the destination tab lights up in the bottom bar while you drag** — the same
  pre-highlight a pager gives you by revealing the next page, without rendering it
- commit on 25% travel or an 800 dp/s flick; the nav transition continues the motion
  instead of snapping back first
- no wrap-around at either end

### Swipe-to-refresh — what shipped

Both lists that show server data (Home, Projects) wrap their content in Material 3's
`PullToRefreshBox`. The gesture is vertical, so it does not compete with the tab swipe or
the row swipe at all.

`refresh()` on both view models cancels the live collectors and re-subscribes: Firestore's
listeners are long-lived, so re-subscribing is what actually re-reads the collection and
picks up anything the local cache missed. The spinner is held ~650 ms so the gesture still
feels acknowledged when the snapshot returns instantly, and the loading skeleton is
suppressed during a refresh so live content is not covered by a skeleton flash.


---

## 8. Revision — real pager (white flash fix)

The first tab implementation slid the *current* screen and then navigated. That is why the
next page appeared blank and then loaded: a navigation transition can only animate content
that already exists, and the incoming destination had not composed (or started its Firestore
listener) when the animation began.

Swiping must **reveal**, not preview-then-load. Fixed by making the four tabs pages of one
`HorizontalPager` inside a single destination (`Route.Home` → `MainTabsScreen`):

- `beyondViewportPageCount = 1` composes the neighbours ahead of the gesture, so the page
  being revealed is already drawn — no white gap.
- each page is wrapped in `SaveableStateProvider`, so scroll position survives switching
  without keeping four lists composed.
- the view models are scoped to the one nav entry, so the data stays loaded; a page is never
  "reloaded" when you come back to it.
- `Route.Projects`, `Route.History`, `Route.Settings` are gone; screens outside the pager
  (Search, Profile, a project page) ask for a page through `MainTabsRequest`, which is
  Compose state so a request still lands if the pager is already on screen.
- back on a non-first tab returns to Home before leaving the app.
- the bottom bar follows `pagerState.currentPage`, so the highlight moves at the halfway
  point of the drag, exactly like a native pager.

Consequences worth knowing: the tab destinations no longer have separate back-stack entries,
which is the same trade Instagram makes (its back button walks tab history instead). If tab
history out of order is ever needed, the pager's `currentPage` can be pushed into the nav
graph on each settle.
