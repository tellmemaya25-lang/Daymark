# Speech to text in Add Task — research and plan

Dictation for the three text surfaces of the Add Task form: **title**, **description**, and **sub-tasks**. Nothing else changes, and nothing is saved by dictating.

---

## 1. What the form already is

| Surface | Today | Constraint |
|---|---|---|
| Title | `OutlinedTextField`, single line, `trailingIcon` = ✨ quick add (or a spinner while parsing) | The trailing slot is already taken |
| Description | `OutlinedTextField`, `minLines = 3`, `maxLines = 6`, no icons | Room for one trailing icon |
| New sub-task | Field + `Add` button in a `Row` | Field is `weight(1f)` — already tight |
| Sub-task edit row | Checkbox + field + ✓ + ✕, 48dp min height | Four controls already; a fifth must earn its place |

Other facts that matter:

- `minSdk 24`, `targetSdk 35`, `compileSdk 35`.
- `material-icons-extended` is a dependency, so `Icons.Filled.Mic`, `MicOff`, `Stop` are free.
- Runtime-permission patterns already exist: `MainActivity` uses `RequestPermission` for notifications, `ProfileScreen` uses `rememberLauncherForActivityResult` for photos.
- The design language for transient feedback is **`QuickAddStrip`** — a one-line inline strip under the top of the form. No dialogs for speaking, no toasts, no snackbars for this.
- `data/ai/*` and the quick-add use case already exist. Dictation must not accidentally route through them.

---

## 2. What Android gives you, and what it costs

**`SpeechRecognizer`** is the system recognition service. Free, no API key, no Firebase, no App Check, no billing, no quota. It is the only sensible engine here.

Four things that decide whether the feature works at all:

1. **The manifest query.** With `targetSdk 30+`, an app that does not declare the recognition intent sees `isRecognitionAvailable()` return **false** even when a recognizer is installed. This is the classic silent failure:

```xml
<queries>
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

2. **`RECORD_AUDIO` is a runtime permission** — for the in-app recognizer only. The system `ACTION_RECOGNIZE_SPEECH` activity handles its own permission, at the price described in option B.

3. **One recognizer, main thread, always destroyed.** `createSpeechRecognizer` on the main thread; `cancel()` before reuse; `destroy()` when the form closes. A leaked recognizer keeps the microphone hot and drains battery, and `ERROR_RECOGNIZER_BUSY` is what the user sees afterwards.

4. **Errors are normal, not exceptional.** `ERROR_NO_MATCH` and `ERROR_SPEECH_TIMEOUT` mean "nothing was said" — the single most common outcome, and never an error dialog.

`EXTRA_PARTIAL_RESULTS = true` gives live text while speaking, which is what makes dictation feel native rather than like a wait.

API 31+ can ask `isOnDeviceRecognitionAvailable()` and API 33+ can `createOnDeviceSpeechRecognizer()`, keeping audio on the phone. Better privacy, worse accuracy, and one more code path.

---

## 3. Options, scored for *this* app

| | A. In-app recognizer + inline strip | B. System voice sheet | C. Firebase AI audio |
|---|---|---|---|
| Code | ~350 lines + tests | ~40 lines | ~200 lines on top of existing AI stack |
| Permission | `RECORD_AUDIO` | none | `RECORD_AUDIO` |
| Feel | Live text in the field, stays in the form | Full-screen takeover by another app | In-form |
| Failures | We map them, one line each | Google's UI handles retry | App Check, quota, 12s timeout |
| Works at `minSdk 24` | Yes | Yes | Yes, but needs the AI stack |
| Fits the design | Yes — `QuickAddStrip` precedent | No — jarring | Overkill |

**C is rejected.** The platform already transcribes locally for free, and our own AI path has an unregistered App Check debug token and a hard enforcement date of 2026‑11‑02. Sending audio through it adds cost, latency and a privacy story for zero benefit.

**B is rejected** as the primary path: a full-screen Google sheet appearing out of a quiet Compose form is exactly the kind of jolt the rest of this app avoids, and it discards the live-partial feel.

**A is the recommendation**, which is why sections 4–6 are about A.

---

## 4. Design rules the implementation must follow

1. **Dictation appends, it never replaces.** The field's existing text is snapshotted on start; Cancel restores it byte for byte. Same contract as quick add's Undo.
2. **One session at a time.** Tapping a second mic stops the first. The recognizer is created on tap and destroyed on stop or on leaving the screen — never held across a screen exit.
3. **No recognizer, no mic.** If `isRecognitionAvailable()` is false, the mic is not drawn at all. A permanently dead button is worse than no button.
4. **Permission on first tap, never on entry.** Denied once: a one-line strip explanation. Denied permanently: the line names Settings. Typing is always there — the keyboard remains the primary path, dictation is the shortcut.
5. **Failures are one line, in the `QuickAddStrip` style.** Silence reads as "Didn't catch that — try again", not as an error. `ERROR_INSUFFICIENT_PERMISSIONS`, `ERROR_NETWORK`, `ERROR_RECOGNIZER_BUSY`, `ERROR_CLIENT` each get their own sentence, and none of them is a dialog.
6. **Listening is static, not animated.** Mic tints on a colour change, the strip names the target: `Listening for Title…`. No pulse, no waveform, so reduced motion needs no special case.
7. **Text lands at the end of the field**, whitespace-normalised, so two dictations don't collide into `wordword`.
8. **Dictating saves nothing.** Save still saves. Same promise quick add already makes.
9. **It composes with the sparkle.** Dictate a whole sentence into Title, then tap ✨ to pull out date, priority and project. Neither step triggers the other automatically.

---

## 5. Files: new vs. changed

**New**

| File | Role |
|---|---|
| `ui/addedit/speech/DictationBuffer.kt` | Pure text logic: append normalisation, partial→final replacement, snapshot restore. No Android imports, so it is JVM-testable. |
| `ui/addedit/speech/DictationController.kt` | `SpeechRecognizer` wrapper: start/stop/cancel, target, mapped messages, lifecycle destroy. State as `StateFlow`. |
| `ui/addedit/speech/DictationUiState.kt` | Target, listening flag, one-line message |
| `ui/addedit/speech/DictationTarget.kt` | `Title`, `Description`, `NewSubTask`, `ExistingSubTask(id)` |
| `app/src/test/.../DictationBufferTest.kt` | Pinned tests for the pure half |
| `docs/speech-to-text-plan.md` | This file |

**Changed**

| File | Change |
|---|---|
| `AndroidManifest.xml` | `RECORD_AUDIO` + the `<queries>` block from §2 |
| `AddEditTaskScreen.kt` | Mic icons, dictation strip, permission launcher, controller lifecycle |
| `AddEditViewModel.kt` | Setters only — `onTitleChange`, `onDescriptionChange`, `onNewSubTaskTitleChange`, `editSubTask` already exist and are reused as-is |

`AddEditUiState` stays untouched. Dictation state lives with the screen, because the recogniser does: a ViewModel that survives a rotation should not own the microphone, and a field being dictated is not part of the task being edited.

The recognizer itself stays out of the ViewModel: it is a framework service connection tied to a screen, and a ViewModel that survives a rotation should not own the microphone account.

---

## 6. Testing and verification, honestly

**Unit** — `DictationBuffer`, which is why it is a separate pure class:

- append to empty gives the phrase
- append to existing adds exactly one space
- newlines: a second sentence in Description starts on a new line
- partial replaced by final does not duplicate
- cancel restores the exact original, including empty
- trailing whitespace in the phrase never produces a double space

**Manual, on a real device** (an emulator without a recognizer will simply hide the mic):

- dictating into each of the three surfaces
- Cancel after partial results → original text back
- deny microphone permission, then deny permanently
- airplane mode
- silence for ten seconds
- tapping two mics quickly
- rotating mid-listen, and backing out mid-listen → mic released, no `ERROR_RECOGNIZER_BUSY` on the next form
- TalkBack: mic announces its target ("Dictate title"), and each strip line is reachable

**Not verifiable here.** No JDK, Android SDK or Gradle in this sandbox. Nothing is claimed as run.

---

## 7. Decisions, as answered

1. **Mic placement** — one mic per field. The title takes it as a **leading** icon so ✨ keeps the right edge; description and both sub-task fields take it as a trailing icon.
2. **Recognizer flavour** — system default. One path on every device from `minSdk 24`, best accuracy, no on-device branching.
3. **Sub-task edit rows** — included. The edit row's microphone goes in the field's trailing slot, so the row keeps its four controls.

---

## 8. What I would *not* do

- Route audio through Firebase AI Logic while the platform transcribes for free.
- Fire the microphone permission prompt when the screen opens.
- Keep a recognizer alive across screens, or share one instance app-wide.
- Animate a listening state.
- Make dictation the only way to fill a field.
- Auto-save, or auto-run quick add, on the final result.
