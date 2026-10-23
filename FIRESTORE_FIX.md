# Firestore – Task Not Creating / Not Inserting Fix

## Symptom: the Save button spins forever and no task appears

Cause: `DocumentReference.set(...)` returns a Task that only completes when the
**backend acknowledges** the write. Offline, on a flaky connection, or when the write
is stuck behind a proxy, that ack never arrives — `await()` suspends forever, so
`isSaving` is never cleared and the button shows a spinner indefinitely. The old code
also awaited the activity-log write *before* returning success, which doubled the
chance of hanging.

Fixed in `TaskRepositoryImpl` / `AddEditViewModel`:

- every write now waits at most `WRITE_ACK_TIMEOUT_MS` (8 s); no ack means the write is
  treated as *queued* (Firestore offline persistence is enabled) and the save returns
  success, so the SDK can flush it later
- the activity log is bounded to 3 s and can never block a save
- `saveTask` runs inside `withTimeoutOrNull(20 s)` and clears `isSaving` in a `finally`
  block, so the spinner always stops — with a readable message when it times out
- toggling complete / delete / restore use the same bounded write

If the save still fails, the snackbar now names the real reason (permission denied,
unreachable backend, signed out) instead of spinning.

---


Your symptom: Add Task → Save → no crash but task doesn't appear, not in database.

## Root Causes (most common)

1. **Firestore Security Rules deny writes** (default rules block all)
2. **Firestore not enabled** in Firebase Console
3. **User not authenticated** (uid null) – sign out/in again
4. **Missing composite index** for `isDeleted + dueAt` query (causes empty list, not crash now due to fallback)

## Fix Steps

### 1. Enable Firestore
- https://console.firebase.google.com → your project → **Firestore Database** → Create database → Start in **production mode** → Choose region → Enable

### 2. Deploy Security Rules

**Option A – Firebase Console (quick):**
1. Firestore → Rules tab → Replace with content of `firestore.rules` from this repo:
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
      match /tasks/{taskId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
      match /categories/{categoryId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
      match /activity/{activityId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
    }
    match /users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```
2. Publish

**Option B – Firebase CLI:**
```powershell
npm install -g firebase-tools
firebase login
firebase init firestore  # select your project, use firestore.rules file
firebase deploy --only firestore:rules
```

### 3. Check Authentication
In app, after login, the debug info shows `uid=...`. If `uid=null`, logout and login again.

Logcat:
```powershell
adb logcat | Select-String -Pattern "AddEditVM|TaskRepo"
```
You should see:
```
AddEditVM: Saving task: title=Webinar uid=abc123 dueAt=...
TaskRepo: Writing to users/abc123/tasks/xyz
TaskRepo: Task written successfully xyz
```
If you see `PERMISSION_DENIED`, rules are the problem.

### 4. Test Write Manually
Firebase Console → Firestore → Start collection → `users` → document ID = your uid (from logcat) → add subcollection `tasks` → add document with:
```json
{
  "title": "Test",
  "isCompleted": false,
  "isDeleted": false,
  "priority": "MEDIUM"
}
```
If manual write fails with permission denied, rules are wrong.

### 5. Offline Persistence
Firestore offline is enabled in `FirebaseModule`. If device offline, writes are queued and synced later. Check internet.

### 6. Code fixes in this update
- `AddEditViewModel` now logs uid, title, dueAt and shows detailed error in Snackbar
- `TaskRepositoryImpl.addTask` logs full path `users/{uid}/tasks/{id}` and tries minimal map fallback if full DTO fails
- `FirebaseModule` guards against `IllegalStateException` when settings already set
- `observeTasks` logs permission/index errors and shows friendly message
- Added `firestore.rules` file to repo

### 7. After fixing rules
```powershell
git pull origin arena/01a0a291-todo-app-task-management
.\gradlew clean assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
# Add task again, check logcat
adb logcat -s TaskRepo:AddEditVM
```

### 8. Verify in Console
Firestore → Data → `users/{uid}/tasks` → your task should appear.

If still not working, send logcat output of `TaskRepo` and `AddEditVM` and screenshot of Firestore Rules tab.
