# Google OAuth 2.0 Setup for daymark .

You asked to enable OAuth 2.0 login – implemented!

## What was added (code)

- `AuthRepository.signInWithGoogle(idToken)`
- `GoogleAuthButton` composable with `play-services-auth`
- Login & SignUp screens now have "Continue with Google" button
- Error handling for common pitfalls (SHA-1 missing, client ID missing)

## Firebase Console steps (required)

### 1. Enable Google Provider
1. https://console.firebase.google.com → your project
2. Authentication → Sign-in method → **Add new provider** → **Google** → Enable → Save
3. Set support email

### 2. Add SHA-1 (critical for Android)
Google Sign-In on Android requires SHA-1 fingerprint, otherwise you get error 10 DEVELOPER_ERROR.

**Get SHA-1 debug key:**
```powershell
cd "C:\Users\INTEL\OneDrive\Documents\School Projects (4rt Year)\Todo-App-Task-Management"
.\gradlew signingReport
```
Look for `SHA1:` under `debug` variant, copy it.

**Add to Firebase:**
1. Project Settings → General → Your apps → Android app `com.sabihon.todo`
2. Add fingerprint → Paste SHA-1 → Save
3. Also add SHA-256 if you want (optional but recommended for Play Integrity)

### 3. Re-download google-services.json
1. Project Settings → Your apps → Download `google-services.json`
2. Replace file in `app/google-services.json`
3. This file now contains `oauth_client` with `client_type: 3` (web client) which generates `default_web_client_id`

### 4. Clean build
```powershell
.\gradlew clean assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Test
- Login screen → "Continue with Google" → choose Google account → should go to Home
- Check Firebase Console → Authentication → Users → new user with Google provider icon
- Check Firestore → `users/{uid}` doc created with displayName, email, photoUrl

## Troubleshooting

| Error | Fix |
|-------|-----|
| `Missing default_web_client_id` | Re-download google-services.json after enabling Google provider |
| `DEVELOPER_ERROR 10` | SHA-1 missing. Run signingReport, add SHA-1 to Firebase |
| `CONFIGURATION_NOT_FOUND` | Enable Email/Password AND Google, re-download json |
| Google button does nothing | Check logcat `adb logcat | Select-String Google` |
| `ApiException 12500` | Update Google Play Services on device/emulator |

## Brand: daymark .
Changed:
- `strings.xml` app_name → `daymark .`
- Splash screen → daymark mark + `daymark` wordmark with the blue dot (`DaymarkLockup`)
- Login subtitle → `Login to daymark .`
- SignUp subtitle → `Join daymark . today`
- Settings about → `daymark . v1.0`
- Launcher icon → lavender `#C4D2FF` tile with navy `#243B71` tick (legacy PNGs + adaptive icon)

Internal class names like `SabihonTheme`, `SabihonApp` kept to avoid risky refactor – they are not user-visible.

## Add Task Crash Fix
Fixed multiple crash points:
- `ReminderScheduler` now lazy and try-catch – no more WorkManager init crash
- `SabihonApp.onCreate()` creates notification channel early
- `AddEditTaskScreen` TimePicker state remembered correctly outside dialog, safe format
- `AddEditViewModel` combineDateAndTime now handles null date/time, defaults to today + 9 AM or now+1h for reminder
- `TaskRepositoryImpl` handles missing Firestore composite indexes – falls back to in-memory sort instead of crashing

Pull latest and test adding task:
1. Title: Webinar
2. Select date Sep 15 2026 → OK
3. Select time 12:53 PM → OK (now works even if date not yet selected)
4. Save → should not crash, task appears in Home
