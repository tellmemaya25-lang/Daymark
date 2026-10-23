# Sabihon — To‑Do & Task Management App

**Sabihon** is a production-quality Android To-Do & Task Management app built with **Kotlin + Jetpack Compose (Material 3)** and **Firebase** backend. Clean, airy, iOS-like pastel design, offline-first Firestore, reminders, and full task lifecycle.

![Sabihon Banner](https://via.placeholder.com/1200x400/D6E4FF/101114?text=Sabihon+%E2%80%94+Pastel+To-Do+App)

---

## Screenshots (Placeholders – replace with real device screenshots)

| Home Dashboard | All Task List | Add Task |
|---|---|---|
| ![Home](https://via.placeholder.com/300x600/D6E4FF/000000?text=Home+Dashboard) | ![Categories](https://via.placeholder.com/300x600/FDF3A0/000000?text=All+Task+List) | ![Add](https://via.placeholder.com/300x600/CFF5E7/000000?text=Add+Task) |

| Search & Filter | History | Profile |
|---|---|---|
| ![Search](https://via.placeholder.com/300x600/FBD7EA/000000?text=Search) | ![History](https://via.placeholder.com/300x600/E5DBFF/000000?text=History) | ![Profile](https://via.placeholder.com/300x600/FFFFFF/000000?text=Profile) |

---

## Features
- Add tasks with title, description, priority (Low/Medium/High/Urgent), due date/time, category, sub-tasks
- Completed / Pending status with animated checkbox + strikethrough + haptics
- Categories / Lists with pastel cards (Grocery, Educational, Home Related, Work Related, etc.) – live counts
- Search & filter (status, priority, category, due date) with 300ms debounce + recent searches
- Task history grouped by Today/Yesterday/Earlier + activity log + restore + permanent delete
- Statistics dashboard (completed this week, completion rate, streak, 7-day bar chart drawn with Canvas)
- Reminders via WorkManager + notifications with deep link + Mark Done action, POST_NOTIFICATIONS permission handling
- Auth: Email/Password + Google Sign-In, auto seed 6 default categories, friendly error messages
- Offline persistence, swipe-to-complete/delete with Undo snackbar, shimmer skeletons, empty states
- Theme switcher (System/Light/Dark) persisted with DataStore, offline banner, pull-to-refresh
- Accessibility: content descriptions, ≥48.dp touch targets, dynamic font scaling

## Tech Stack
- Kotlin 2.1.20, Compose BOM 2025.08.00, Material 3, Min SDK 24, Target/Compile SDK 35
- MVVM + Clean-ish (ui / domain / data), Hilt DI, Coroutines + Flow (StateFlow)
- Navigation-Compose type-safe routes (@Serializable), DataStore for theme prefs
- Firebase Auth, Firestore (offline persistence), Storage, FCM optional
- WorkManager + NotificationCompat, java.time with desugaring, kotlinx-datetime
- JUnit + MockK + Turbine + Compose UI tests, Gradle Kotlin DSL + version catalog

## Project Structure
```
com.sabihon.todo
 ├─ SabihonApp.kt (@HiltAndroidApp + WorkManager Configuration.Provider)
 ├─ di/ (FirebaseModule, RepositoryModule, DispatcherModule, WorkerModule)
 ├─ core/ (ui/theme, ui/components, ui/preview, util Result, datastore)
 ├─ data/ (remote/dto + mappers, repository impls with callbackFlow)
 ├─ domain/ (model, repository interfaces, usecases)
 ├─ notifications/ (ReminderScheduler, ReminderWorker, NotificationHelper, ReminderActionReceiver)
 └─ ui/ (navigation, auth, home, categories, taskdetail, addedit, search, history, profile)
```

## Firebase Console Setup (Step-by-step)

### 1. Create Firebase Project
1. Go to https://console.firebase.google.com → Add project → name: `sabihon-todo`
2. Disable Google Analytics for now (optional), Create.

### 2. Add Android App
1. Project Overview → Add app → Android.
2. Package name: `com.sabihon.todo` (must match `app/build.gradle.kts` applicationId)
3. App nickname: `Sabihon`
4. SHA-1 (required for Google Sign-In):
   - Debug: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`
   - Copy SHA-1, add to Firebase.
   - For release, generate from your upload keystore.
5. Download `google-services.json` → place in `app/` folder (this file is gitignored; keep a copy secure).

### 3. Enable Authentication
1. Firebase Console → Build → Authentication → Get started.
2. Sign-in method → Enable Email/Password.
3. Enable Google → add support email → Save.

### 4. Create Firestore (Production Mode)
1. Build → Firestore Database → Create database.
2. Choose region, Start in **production mode**.
3. Offline persistence enabled in `FirebaseModule`.

### 5. Firestore Security Rules
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    match /users/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    match /users/{uid}/tasks/{taskId} {
      allow read, write: if request.auth != null && request.auth.uid == uid
        && (!('priority' in request.resource.data) || request.resource.data.priority in ['LOW','MEDIUM','HIGH','URGENT']);
    }
  }
}
```

### 6. Firestore Indexes (Composite)
- `isDeleted ASC, isCompleted ASC, dueAt ASC` (collection group)
- `isDeleted ASC, categoryId ASC, dueAt ASC` (collection group)
- Let Firebase auto-suggest via error link if missing.

### 7. Storage (Optional)
- Build → Storage → production mode.

### 8. Add google-services.json
- Place in `app/` folder.

## Build & Run
```bash
git clone https://github.com/tellmemaya25-lang/Todo-App-Task-Management.git
cd Todo-App-Task-Management
cp ~/Downloads/google-services.json app/
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
./gradlew connectedAndroidTest
./gradlew assembleRelease
```

### Sandbox Note
CI sandbox blocks non-GitHub TLS (services.gradle.org, maven central) so `./gradlew` may fail to download dependencies in restricted environments. On a normal dev machine with internet, build succeeds.

## Design System
- Corner radius: cards 24.dp, chips 100.dp, buttons 16.dp
- Spacing: 4/8/12/16/20/24.dp, screen horizontal 20.dp
- Palette: softBlue #D6E4FF, softYellow #FDF3A0, softMint #CFF5E7, softPink #FBD7EA, softLilac #E5DBFF, deepGreen #2F6B4F, accentBlue #2F6BFF, bg #FFFFFF, surfaceVariant #F5F6FA, textPrimary #101114, textSecondary #6B7280
- Typography: Inter/Poppins approximations, Headline 28.sp Bold, Title 20.sp SemiBold, Body 15.sp, Caption 12.sp Medium
- Dark theme: tonally-adjusted pastels
- Elevation: 0.dp except FAB 6.dp

## Firestore Data Model
```
users/{uid}
  displayName, email, photoUrl, createdAt, themePref, fcmToken

users/{uid}/categories/{categoryId}
  name, colorHex, iconKey, order, createdAt

users/{uid}/tasks/{taskId}
  title, description, categoryId, priority (LOW|MEDIUM|HIGH|URGENT),
  dueAt, reminderAt, isCompleted, completedAt, subTasks [{id,title,isDone}],
  createdAt, updatedAt, isDeleted, searchKeywords [lowercased tokens]

users/{uid}/activity/{activityId}
  taskId, taskTitle, action (CREATED|UPDATED|COMPLETED|REOPENED|DELETED), timestamp
```

## Engineering Rules Followed
- No hardcoded strings in Composables → strings.xml
- No business logic in Composables → ViewModel/UseCase only
- One immutable UiState per screen
- Never expose Firebase types above data layer
- Handle: no network, auth expired, permission denied, empty results
- KDoc on public classes

## Loops Progress
- [x] Loop 0 — Scaffold
- [x] Loop 1 — Design System
- [x] Loop 2 — Domain + Data Layer
- [x] Loop 3 — Authentication
- [x] Loop 4 — Home Dashboard
- [x] Loop 5 — Add/Edit Task
- [x] Loop 6 — Categories
- [x] Loop 7 — Search, Filter & Sort
- [x] Loop 8 — History & Statistics
- [x] Loop 9 — Reminders & Notifications
- [x] Loop 10 — Profile, Settings & Polish

## License
MIT
