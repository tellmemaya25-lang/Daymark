# Settings redesign, sheet radius and the light/dark switch — research + notes

Status: implemented.

---

## 1. What the research said

Sources: Android's own settings pattern guide, Material 3 shape + bottom-sheet specs, and
three UX write-ups on settings screens (Android Developers "Settings", Material 3 "Shape –
corner radius scale", Material 3 "Bottom sheets", Toptal "Settings UX", LogRocket
"Designing settings screen UI", UI-prep "Settings screen design for iOS and Android").

Points that changed the design:

| Guidance | What it meant here |
|---|---|
| Group related settings under a heading; use visual containment and whitespace between groups, not a divider between every row | One card per group, small-caps heading above it, 12 dp gap between groups |
| Keep the number of top-level categories to four or five | Appearance, Account, Activity, About |
| "For 15 or more settings, group related settings under a subscreen" | We have three real settings; no subscreens beyond the theme sheet |
| Show the current value on the row, not a tile per option | Theme row reads `System`, opening a sheet with the three choices |
| "Avoid information about the app, such as a version number or licensing" | Tech-stack list and the feature dump are gone; the version survives as one quiet row because there is no store listing to put it on yet |
| Bottom sheets use a 28 dp top radius by default | Reduced to 20 dp — see §2 |
| Optical roundness: inner radius = outer radius − padding | 20 dp sheet over 18 dp cards, 14 dp fields inside the sheet |

Also from the research: a row that goes nowhere should not have a chevron. The old screen
had a "More info" card wired to an `onNavigateToAbout` callback that `MainTabsScreen` never
passed — a dead tap target. It is removed rather than left as decoration.

---

## 2. Modal bottom sheet radius

`DaymarkSheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)` in
`core/ui/theme/Shape.kt`.

Material's default for a modal sheet is 28 dp. On a phone that reads as nearly a pill, and
it fights the 18 dp grouped cards directly behind it. 20 dp is the next step down Material's
published scale ("large increased") and puts the sheet in the same family as the cards
underneath. Top corners only — the sheet is anchored to the bottom edge.

Both Profile sheets (display name, change password) and the Settings theme sheet use it, so
there is one number to change.

---

## 3. Password change is a sheet, not a dialog

The change-password flow used an `AlertDialog` while the name edit used a bottom sheet — two
different containers for two forms of the same weight. It now uses the same sheet shell:

- `DaymarkSheet` — one place for shape, container colour, tonal elevation and the state
- `SheetHeading` — title + one supporting line
- `SheetPrimaryAction` — 52 dp filled action, spinner while working
- `SheetCancelAction` — 48 dp text button, so dismissing never depends on a gesture
- `Modifier.imePadding()` on the content, so three fields are not buried under the keyboard

The dialog's "disable the button until valid" behaviour was dropped on purpose: the
ViewModel already produces specific messages ("Current password required", length, mismatch)
and a disabled button hides the reason nothing happened.

---

## 4. Profile gets a push transition

`Route.Profile` is a page over the tabs, so it slides in from the right (280 ms,
`FastOutSlowIn`) and back out the same way (240 ms, `FastOutLinearIn`) rather than appearing
out of a cross-fade.

Home holds still underneath it — the same `ExitTransition.None` treatment Search already
had, extended to a `holdsStillUnder()` helper. A fading background behind a travelling
opaque page shows the bare window colour mid-push; a static one is simply covered.

`NavHost` also stopped using navigation-compose's 700 ms default cross-fade. Every
destination that does not define its own transition now uses the same 220 ms fade as the
tabs.

---

## 5. The light/dark switch

Three separate causes of the "it jumps" effect, all fixed:

1. **Only 19 of 36 colour roles were interpolated.** Anything reading `secondary`,
   `tertiaryContainer`, `errorContainer`, `inverseSurface` or `surfaceContainerLow` (which
   `Card` and `ModalBottomSheet` resolve internally) sat at its light value for the whole
   fade and then snapped at the end. `lerpScheme` now covers every role; only the
   `*Fixed` roles are left alone, because they are identical in both schemes by definition.
2. **Hard-coded brand surfaces flipped at the halfway mark.** `if (isDark) DaymarkDarkCard
   else Color(0xFFF5F6FA)` reads `LocalIsDarkTheme`, which crosses at 50 % — so a near-black
   card landed on a half-faded grey canvas. Those pairs now live in `DaymarkSurfaces`,
   exposed through `LocalDaymarkSurfaces` and blended by the same animation, so they fade
   with everything else. Task rows, the week strip, onboarding, Profile and Settings all
   read from it and no longer branch on `isDark` for surfaces.
3. **Status/nav bar icons flipped at the start.** They were keyed on the *target* flag, so
   for the first ~140 ms they were black on a darkening canvas. They are now keyed on
   `LocalIsDarkTheme` and cross over with the canvas at the midpoint.

The neutral surface scale (`surfaceBright`, `surfaceDim`, `surfaceContainerLowest/Low`) is
now defined explicitly in both schemes; the Material baseline values are purple-tinted and
did not match the navy/lavender palette.

---

## 6. Shared grouped list

`core/ui/components/GroupedList.kt` — `SectionLabel`, `GroupedCard`, `GroupedRow`,
`GroupedDivider`.

Extracted from `ProfileScreen` so Profile and Settings cannot drift apart. Emphasis follows
Material's list pattern: the label is the primary line, the current value is secondary on
the trailing edge, and the chevron only exists when `onClick` is non-null.
