# Daymark Admin Dashboard — System Design & Implementation Plan

- **Document ID**: `03-daymark-admin-dashboard-system-design`
- **Status**: Implemented on active branch
- **Primary Admin Account**: `tellmemaya25@gmail.com` (Firebase Auth + Firestore RBAC)
- **Stack**: Kotlin · Jetpack Compose (Material 3) · Hilt · Navigation Compose · Firebase Authentication · Cloud Firestore · Firebase Analytics

---

## 1. Executive Summary & Objectives

Daymark requires an administrative control plane (**Admin Dashboard**) accessible exclusively to authorized administrators (bootstrapped for **`tellmemaya25@gmail.com`**) while keeping ordinary users strictly isolated inside their personal task workspace.

### Primary Goals
1. **Zero-Plaintext Credential Security**: Authenticate the admin account through **Firebase Authentication** (with one-time salted SHA-256 bootstrap provisioning if the account has not yet been created in Firebase Auth) — never storing plaintext passwords in source code, `BuildConfig`, tests, or documentation.
2. **Server-Enforced Authorization (RBAC)**: Enforce admin privileges in **`firestore.rules`** (`isAdmin()`) so cross-user analytics, user governance, system configuration, and audit logs are protected at the database layer, not merely hidden in Compose UI.
3. **Unified Operational Visibility**: Provide real-time platform KPIs (total users, active/completed tasks, completion rate, 7-day task velocity, priority distribution, project count, habit streaks, and Cloud Gemini vs. Offline Rung-0 AI telemetry).
4. **Live Governance & Runtime Controls**:
   - **User Directory & Moderation**: Search/filter accounts, inspect per-user task/project counts, grant/revoke secondary `ADMIN` roles, and suspend/reactivate accounts (with primary-owner anti-lockout guards).
   - **Global AI & App Configuration**: Toggle a live Cloud Gemini kill-switch, adjust per-user daily AI call budgets, toggle Prioritize / Suggestions features, and publish a live system announcement or maintenance banner without shipping a new APK.
   - **Immutable Audit Trail & Account Security**: Record every administrative mutation into an append-only `/admin_audit_logs` collection and allow in-app admin password rotation.

---

## 2. Authentication, RBAC & Credential Security Model

### 2.1 Why Plaintext Credentials Never Live in Source
Embedding plaintext passwords in client binaries or Git history exposes credentials via APK decompilation (`jadx`) and repository clones. Instead, Daymark uses a **3-layer defense-in-depth model**:

1. **Identity Verification (`FirebaseAuth`)**:
   - Primary authentication always executes `FirebaseAuth.signInWithEmailAndPassword(email, password)` (or Google OAuth for `tellmemaya25@gmail.com`).
   - **One-Time Salted SHA-256 Bootstrap**: If `tellmemaya25@gmail.com` has not yet been provisioned in Firebase Authentication (`ERROR_USER_NOT_FOUND` / `INVALID_LOGIN_CREDENTIALS`), `AuthRepositoryImpl` checks `AdminAccessPolicy.matchesBootstrapCredential(email, password)` using a constant-time comparison(`MessageDigest.isEqual`) against a **salted SHA-256 digest**. Only when the digest matches does it provision the Firebase Auth user once, write `/users/{uid}` (`role = "ADMIN"`) + `/admins/{uid}` (`enabled = true`), and log `ADMIN_BOOTSTRAP_PROVISIONED`. Subsequent logins are verified natively by Firebase Auth, and rotating the password in **Admin > Audit & Security** updates Firebase Auth directly.

2. **Three-Tier RBAC Resolution**:
   Both the client guard (`AdminAccessPolicy`) and backend security rules (`firestore.rules :: isAdmin()`) recognize three authoritative admin signals:
   - **Tier 1 — Firebase Custom Claim**: `request.auth.token.admin == true`
   - **Tier 2 — Primary Bootstrap Owner**: Authenticated email `tellmemaya25@gmail.com`
   - **Tier 3 — Delegated Admin Allowlist**: Document `/admins/{uid}` with `enabled == true`

3. **Owner Anti-Lockout & Suspension Enforcement**:
   - The primary owner (`tellmemaya25@gmail.com`) **cannot** be demoted from `ADMIN` or suspended (`AdminAccessPolicy.canModifyUserRole` / `canSuspendUser`), preventing accidental self-lockout.
   - Any account marked `disabled == true` in `/users/{uid}` is immediately denied sign-in (and signed out on session check) with a clear administrator suspension message.

---

## 3. High-Level System Architecture

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose Presentation                          │
│                                                                              │
│  SplashScreen / LoginScreen ──(isAdmin)──► AdminDashboardScreen              │
│       │                                      ├─ Tab 1: Overview (KPIs & AI)  │
│       │ (standard user /                     ├─ Tab 2: Users & Governance    │
│       ▼  "Personal Workspace")               ├─ Tab 3: AI & Runtime Config   │
│  MainTabsScreen (Home / Projects /           └─ Tab 4: Audit Log & Security  │
│                  Activity / Settings*)                                       │
│   *Settings shows "ADMIN > Admin Dashboard" entry iff isAdmin == true        │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │UiState / Actions
                                ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                       Domain & Policy Layer (`domain/admin`)                 │
│                                                                              │
│  AdminDashboardViewModel ──► AdminRepository (interface)                     │
│                          ──► AdminAccessPolicy (RBAC + anti-lockout + hash)  │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                         Data Layer (`data/repository`)                       │
│                                                                              │
│  AdminRepositoryImpl  ◄──────►  Cloud Firestore                              │
│  AuthRepositoryImpl             ├─ /users/{uid} (+ subcollections)           │
│  FirebaseAiTelemetry            ├─ /admins/{uid}                             │
│                                 ├─ /system_config/global                     │
│                                 ├─ /ai_telemetry_daily/{yyyy-MM-dd}          │
│                                 └─ /admin_audit_logs/{logId} (append-only)   │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Firestore Data Model & Security Rules

### 4.1 Collections & Document Schemas

| Collection Path | Purpose | Key Fields | Access Policy |
| :--- | :--- | :--- | :--- |
| `/users/{uid}` | User profile, role, and account status | `displayName`, `email`, `photoUrl`, `themePref`, `role` (`"USER"` \| `"ADMIN"`), `disabled` (`Boolean`), `createdAt`, `lastLoginAt` | Owner: read + non-privileged profile update; Admin: read all + governance update |
| `/users/{uid}/tasks/{taskId}` | User tasks (queried via `collectionGroup("tasks")` for platform KPIs) | Existing `TaskDto` fields (`priority`, `dueAt`, `isCompleted`, `completedAt`, `repeat`, `streak`, `isDeleted`) | Owner: read/write; Admin: read-only (`collectionGroup` + subcollection) |
| `/users/{uid}/categories/{catId}` | User projects/categories | Existing `CategoryDto` fields | Owner: read/write; Admin: read-only |
| `/admins/{uid}` | Dynamic delegated admin registry | `uid`, `email`, `enabled` (`Boolean`), `grantedBy`, `updatedAt` | Authenticated user: read own doc; Admin: read/write (except disabling primary owner) |
| `/system_config/global` | Live runtime feature flags & system announcement | `cloudAiGlobalEnabled`, `prioritizeEnabled`, `suggestionsEnabled`, `dailyAiQuotaPerUser`, `maintenanceMode`, `announcementBanner`, `updatedBy`, `updatedAt` | Signed-in users: read; Admin: write |
| `/ai_telemetry_daily/{dayKey}` | Zero-PII aggregated daily AI metrics (`yyyy-MM-dd`) | `dateKey`, `totalCalls`, `cloudSuccessCount`, `localFallbackCount`, `errorCount`, `totalLatencyMs`, `acceptedOutcomes`, `dismissedOutcomes` | Signed-in users: atomic counter write/merge; Admin: read |
| `/admin_audit_logs/{logId}` | Immutable administrative audit trail | `actorUid`, `actorEmail`, `action`, `targetId`, `summary`, `createdAt` | Admin: read & `create` only (`update` / `delete` permanently `false`) |

### 4.2 Privilege-Escalation Prevention in `firestore.rules`
When a regular user updates `/users/{uid}`, `firestore.rules` verifies that `role` and `disabled` remain unchanged (or default to `"USER"` / `false` on initial document creation) unless `isAdmin()` is true. This prevents a modified client from self-promoting to `ADMIN` or unsuspending itself.

### 4.3 Resilient Metrics Aggregation Strategy
`AdminRepositoryImpl.loadPlatformMetrics()` executes `firestore.collectionGroup("tasks").get()` and `firestore.collectionGroup("categories").get()` in parallel for single-round-trip aggregation. If a Firebase project has not yet enabled collection-group rules or encounters an index state error, the repository **automatically falls back** to parallel per-user `/users/{uid}/tasks` and `/users/{uid}/categories` reads so metrics always populate cleanly.

---

## 5. UX & Screen Architecture (`AdminDashboardScreen`)

The Admin Dashboard adheres strictly to Daymark's calm visual system (`SabihonTheme`, `LocalDaymarkSurfaces`, `GroupedCard`, `AccentBlue`, responsive light/dark surfaces):

1. **Top Bar & Workspace Switcher**:
   - Displays `daymark .` **Admin Console** badge + signed-in admin identity (`tellmemaya25@gmail.com`).
   - Quick-action button (**Workspace**) opens `Route.Home` so the admin can use their personal task list anytime and return via **Settings > ADMIN > Admin Dashboard**.
   - Pull-to-refresh reloads platform KPIs on demand.

2. **Tab 1 — Overview**:
   - **4 KPI Stat Cards**: Total Users (+7d signups), Total Tasks (Open / Done), Completion Rate (`%` + active streaks), and Cloud AI Calls Today (Success `%` + avg latency `ms`).
   - **7-Day Task Velocity Chart**: Custom Compose bar chart comparing created vs. completed tasks across the last 7 days.
   - **Priority Distribution Card**: Proportional breakdown across `Urgent`, `High`, `Medium`, and `Low` open tasks.
   - **Cloud AI Telemetry Breakdown**: Cloud Gemini hits vs. Rung-0 Offline Fallback vs. Errors, plus accepted vs. dismissed AI outcomes.

3. **Tab 2 — Users**:
   - Live search field (`name`, `email`, `uid`) + filter chips (`All`, `Admins`, `Active`, `Suspended`).
   - Tapping any user opens **`UserInspectorSheet`**:
     - Shows live open/completed task counts and category counts for that user.
     - **Grant / Revoke Admin Role** button (locked for primary owner `tellmemaya25@gmail.com`).
     - **Suspend / Reactivate Account** button (locked for self and primary owner, with confirmation dialog).

4. **Tab 3 — AI & Config**:
   - Live editor for `/system_config/global`:
     - Master **Cloud Gemini AI** switch (wired as a global runtime gate in `FirebaseAiTaskService`).
     - **AI Prioritize** & **Contextual Task Suggestions** feature switches.
     - **Daily Cloud AI Quota per User** selector (`25`, `50`, `100`, `250`).
     - **Maintenance Mode** switch + **Global Announcement Banner** text field (rendered live on `HomeScreen` when active).

5. **Tab 4 — Audit & Security**:
   - Real-time chronological feed of `/admin_audit_logs` entries with action badges and actor/target metadata.
   - **Admin Account Security Card**:
     - Displays current session provider details (`Email/Password`, `Google`).
     - **Change Admin Password** modal sheet (`FirebaseAuth.currentUser.updatePassword`) with password visibility toggles and validation.
     - **Sign Out** button with confirmation.

---

## 6. Phased Implementation Plan

| Phase | Scope | Key Files |
| :--- | :--- | :--- |
| **Phase 1: Security Rules & Domain Policy** | Author `firestore.rules` RBAC + anti-escalation rules; build `AdminModels.kt`, `AdminAccessPolicy.kt` (salted SHA-256 bootstrap + owner lockout guards), and `AdminRepository.kt`. | `firestore.rules`, `domain/admin/AdminModels.kt`, `domain/admin/AdminAccessPolicy.kt`, `domain/admin/AdminRepository.kt` |
| **Phase 2: Auth Bootstrap, Telemetry & Data Repository** | Extend `UserDto` & `AuthRepositoryImpl` with admin bootstrap, role sync, and suspension check; wire zero-PII daily Firestore counters in `FirebaseAiTelemetry` and global AI kill-switch check; implement `AdminRepositoryImpl` + Hilt bindings. | `UserDto.kt`, `AuthRepository.kt`, `AuthRepositoryImpl.kt`, `AiTelemetry.kt`, `FirebaseAiTaskService.kt`, `AdminRepositoryImpl.kt`, `RepositoryModule.kt` |
| **Phase 3: Admin Console UI & ViewModel** | Implement `AdminDashboardUiState.kt`, `AdminDashboardViewModel.kt`, and `AdminDashboardScreen.kt` (Overview, Users + Inspector Sheet, AI & Config, Audit & Password Rotation). | `ui/admin/AdminDashboardUiState.kt`, `ui/admin/AdminDashboardViewModel.kt`, `ui/admin/AdminDashboardScreen.kt` |
| **Phase 4: Navigation Routing, Workspace Bridge & Tests** | Route admin logins and cold starts directly to `Route.AdminDashboard`; add `Settings > ADMIN` entry and `HomeScreen` global announcement banner; add unit tests in `AdminAccessPolicyTest.kt`. | `Routes.kt`, `SplashScreen.kt`, `AuthViewModel.kt`, `LoginScreen.kt`, `SabihonNavHost.kt`, `ProfileViewModel.kt`, `SettingsScreen.kt`, `HomeScreen.kt`, `AdminAccessPolicyTest.kt` |

---

## 7. Deployment & Operational Notes

1. **Deploy Updated Firestore Rules**:
   Run `firebase deploy --only firestore:rules` so Cloud Firestore activates the `isAdmin()`, `/system_config/global`, `/ai_telemetry_daily`, and `/admin_audit_logs` rules on the live Firebase project. (Until deployed, the Admin Dashboard automatically detects `PERMISSION_DENIED` on cross-user queries and displays an actionable setup hint without crashing.)
2. **Rotating the Bootstrap Password**:
   Sign in to Daymark with `tellmemaya25@gmail.com`, open **Admin Console > Audit & Security > Change Admin Password**, and save a new password. Firebase Auth immediately enforces the updated credential on all future logins.
