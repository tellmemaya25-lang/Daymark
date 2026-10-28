# Daymark Admin Dashboard — System Design & Implementation Plan

- **Document ID**: `03-daymark-admin-dashboard-system-design`
- **Target Platform**: Android In-App Admin Console (Jetpack Compose · Material 3 · Hilt · Firebase)
- **Scope**: Complete Admin Suite (Analytics, User CRUD, Global Task CRUD, AI Ops & Quotas, Broadcast Banners, CSV/JSON Export, Immutable Audit Trail)
- **Primary Owner Account**: `tellmemaya25@gmail.com` (Zero-plaintext salted SHA-256 bootstrap + Firebase Auth + Firestore RBAC)

---

## 1. Executive Summary & Architectural Goals

Daymark requires an executive-grade, in-app **Admin Console** accessible to **`tellmemaya25@gmail.com`** (and any delegated secondary administrators) while preserving strict tenant isolation for standard users.

### Key Architectural Guarantees
1. **Zero Plaintext Secrets in Repository or APK**:
   - Plaintext passwords are **never** stored in Kotlin source files, `BuildConfig`, XML resources, `firestore.rules`, unit tests, or documentation.
   - Credential bootstrap verification uses a constant-time (`MessageDigest.isEqual`) comparison against a **salted SHA-256 digest** (`AdminAccessPolicy`).
2. **Guaranteed Login Across All Firebase Account States (3-Stage Hybrid Auth)**:
   - Handles all three real-world Firebase Authentication states for `tellmemaya25@gmail.com`:
     1. **Existing Email/Password Firebase account** -> signs in natively via `FirebaseAuth.signInWithEmailAndPassword`.
     2. **Brand-new Firebase project** -> provisions `tellmemaya25@gmail.com` once via `FirebaseAuth.createUserWithEmailAndPassword`, creates `/users/{uid}` (`role = "ADMIN"`) + `/admins/{uid}` (`enabled = true`), and logs `ADMIN_BOOTSTRAP_PROVISIONED`.
     3. **Pre-existing Google Sign-In account (`FirebaseAuthUserCollisionException`)** -> when `tellmemaya25@gmail.com` was previously registered via *Continue with Google* (so Firebase Auth has no password provider linked yet), `AdminAccessPolicy.matchesBootstrapCredential` activates a persistent verified owner session in **`AdminSessionStore` (DataStore)** so login succeeds immediately into `AdminDashboardScreen`, and exposes a 1-tap **Link Google Firebase Token** action that authenticates the Google credential and links `EmailAuthProvider` onto the same Firebase UID permanently.
3. **Server-Enforced 3-Tier RBAC (`firestore.rules`)**:
   - `isAdmin()` validates `request.auth.token.admin == true`, verified email `tellmemaya25@gmail.com`, or `/admins/{uid}.enabled == true`.
   - Protects cross-tenant user/task CRUD, global runtime config, daily AI telemetry, and append-only audit logs at the Firestore security layer.
4. **Complete Admin Suite (5 Operational Modules)**:
   - **Overview & Deep Analytics** · **Users & Governance** · **Global Task Explorer & CRUD** · **AI & Runtime Config** · **Audit, Export & Security**.

---

## 2. System Architecture

```text
┌────────────────────────────────────────────────────────────────────────────────────┐
│                         Jetpack Compose Presentation Layer                         │
│                                                                                    │
│  SplashScreen / LoginScreen ──(isAdmin)──► AdminDashboardScreen (5 Tabs)           │
│       │                                      ├─ 1. Overview (KPIs, Velocity, AI)   │
│       │ (standard user /                     ├─ 2. Users (Inspect, Edit, Reset,    │
│       ▼  "Workspace" pill)                   │            Role, Suspend, Delete)   │
│  MainTabsScreen                              ├─ 3. Tasks (Cross-User Search,       │
│   ├─ Home (renders live Announcement Banner) │            Complete, Edit, Delete)  │
│   ├─ Projects / All Tasks                    ├─ 4. AI & Config (Kill-switch,       │
│   ├─ Activity                                │            Quotas, Announcements)   │
│   └─ Settings ("ADMIN > Admin Dashboard")    └─ 5. Audit, Export (CSV/JSON) & Auth │
└──────────────────────────────────────┬─────────────────────────────────────────────┘
                                       │ StateFlow<AdminDashboardUiState>
                                       ▼
┌────────────────────────────────────────────────────────────────────────────────────┐
│                           Domain & Security Policy Layer                           │
│                                                                                    │
│  AdminDashboardViewModel ──► AdminRepository (interface)                           │
│                          ──► AdminAccessPolicy (Salted SHA-256 + 3-Tier RBAC +     │
│                                                 Primary Owner Anti-Lockout)        │
│                          ──► AdminExportFormatter (RFC-4180 CSV & JSON Snapshot)   │
└──────────────────────────────────────┬─────────────────────────────────────────────┘
                                       │
                                       ▼
┌────────────────────────────────────────────────────────────────────────────────────┐
│                         Data, Session & AI Guardrail Layer                         │
│                                                                                    │
│  AuthRepositoryImpl  ◄──► AdminSessionStore (DataStore verified owner session)     │
│  AdminRepositoryImpl ◄──► FirebaseAuth (Sign-In, Bootstrap, Link, Password Reset)  │
│  FirebaseAiTaskService / AiQuotaGuard ◄──► Live /system_config/global enforcement  │
│  FirebaseAiTelemetry ──► Atomic FieldValue.increment on /ai_telemetry_daily/{day}  │
└──────────────────────────────────────┬─────────────────────────────────────────────┘
                                       │
                                       ▼
┌────────────────────────────────────────────────────────────────────────────────────┐
│                       Cloud Firestore (`firestore.rules` RBAC)                     │
│                                                                                    │
│  /users/{uid}                      (Owner non-priv write · Admin full read/write)  │
│  /users/{uid}/tasks/{taskId}       (Owner read/write · Admin collectionGroup CRUD) │
│  /users/{uid}/categories/{catId}   (Owner read/write · Admin collectionGroup CRUD) │
│  /admins/{uid}                     (Self read · Admin write)                       │
│  /system_config/global             (Signed-in read · Admin write)                  │
│  /ai_telemetry_daily/{yyyy-MM-dd}  (Signed-in atomic increment · Admin read)       │
│  /admin_audit_logs/{logId}         (Admin read + create ONLY; immutable)           │
└────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Authentication & 3-Stage Owner Login Sequence

```text
Admin enters email + password on LoginScreen
  │
  ├─► Stage 1: FirebaseAuth.signInWithEmailAndPassword(email, password)
  │     ├─ Success ──► Verify not suspended ──► Sync /users/{uid} & /admins/{uid}
  │     │              ──► If isPrimaryAdminEmail: AdminSessionStore.activate()
  │     │              ──► Route to Route.AdminDashboard
  │     └─ Throws Exception
  │          │
  │          ▼
  ├─► Check AdminAccessPolicy.matchesBootstrapCredential(email, password)
  │     ├─ False ──► Return friendly auth error
  │     └─ True (Constant-time salted SHA-256 match for tellmemaya25@gmail.com)
  │          │
  │          ├─► Stage 2: FirebaseAuth.createUserWithEmailAndPassword(email, password)
  │          │     ├─ Success ──► Seed /users/{uid}(ADMIN) + /admins/{uid} + Audit Log
  │          │     │              ──► Route to Route.AdminDashboard
  │          │     └─ Throws Collision / Provider Exception (e.g., Google-linked email)
  │          │          │
  │          │          ▼
  │          └─► Stage 3: Hybrid Verified Owner Session
  │                ├─ Attempt anonymous Firebase token fallback (if enabled)
  │                ├─ Persist verified owner session in AdminSessionStore (DataStore)
  │                └─ Route immediately to Route.AdminDashboard
  │                     (with optional 1-tap Google Token Linker in Security tab to
  │                      bind EmailAuthProvider + full Firestore token permanently)
```

---

## 4. Data Model & Firestore Schema Specification

### 4.1 `/users/{uid}`
| Field | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `displayName` | `String` | `""` | User display name (editable by owner or Admin) |
| `email` | `String` | `""` | Normalized email address |
| `photoUrl` | `String?` | `null` | Avatar URL or embedded data URI |
| `role` | `String` | `"USER"` | `"USER"` or `"ADMIN"` (protected by `ownerUserWriteAllowed()`) |
| `disabled` | `Boolean` | `false` | When `true`, blocks sign-in and revokes active access |
| `themePref` | `String` | `"SYSTEM"` | `"SYSTEM"`, `"LIGHT"`, or `"DARK"` |
| `createdAt` | `Timestamp?` | `@ServerTimestamp` | Account creation timestamp |
| `lastLoginAt` | `Timestamp?` | `null` | Updated automatically on every sign-in |

### 4.2 `/system_config/global`
| Field | Type | Default | Live Enforcement Target |
| :--- | :--- | :--- | :--- |
| `cloudAiGlobalEnabled` | `Boolean` | `true` | `FirebaseAiTaskService` master kill-switch (forces Rung-0 local fallback when `false`) |
| `prioritizeEnabled` | `Boolean` | `true` | `FirebaseAiTaskService.prioritizeTasks` gate |
| `suggestionsEnabled` | `Boolean` | `true` | `FirebaseAiTaskService.suggestNextTasks` & `suggestSubTasks` gate |
| `dailyAiQuotaPerUser` | `Int` | `50` | `AiQuotaGuard.runtimeMaxDailyCalls` dynamic cap |
| `maintenanceMode` | `Boolean` | `false` | Switches `HomeScreen` banner styling to high-visibility amber |
| `announcementBanner` | `String` | `""` | Streamed live to `HomeViewModel` -> `HomeScreen` top banner |
| `updatedByEmail` | `String` | `""` | Last admin editor |
| `updatedAt` | `Timestamp?` | `@ServerTimestamp` | Last config save timestamp |

### 4.3 `/ai_telemetry_daily/{yyyy-MM-dd}` (Zero-PII)
Aggregated via atomic `FieldValue.increment(...)` inside `FirebaseAiTelemetry`:
- `totalCalls`, `cloudSuccessCount`, `localFallbackCount`, `errorCount`, `totalLatencyMs`, `acceptedOutcomes`, `dismissedOutcomes`.
- Read across the trailing **7 days** by `AdminRepositoryImpl.loadAiTelemetryTrend()` to visualize Cloud vs. Local fallback trends and user suggestion acceptance rate.

### 4.4 `/admin_audit_logs/{logId}` (Append-Only Immutable Ledger)
- `actorUid`, `actorEmail`, `action`, `targetId`, `summary`, `createdAt`.
- `firestore.rules` sets `allow update, delete: if false;` so audit history cannot be tampered with even by an admin client.

---

## 5. Complete Admin Suite — Functional Specification

### Tab 1: Overview & Deep Analytics
- **Executive KPI Grid (8 Metrics)**:
  1. **Total Users** (`+N` last 7 days, active in 7d count)
  2. **Governance Split** (`Active Admins` · `Suspended Accounts`)
  3. **Total Platform Tasks** (`Open` vs. `Completed`)
  4. **Task Completion Rate** (`%` + `Overdue Open Tasks`)
  5. **Recurring Habits** (`Active recurring tasks` + `Top streak days`)
  6. **Projects / Categories** (`Total categories` + avg tasks/user)
  7. **Cloud AI Today** (`Total calls` + `Cloud hit %` + `Avg latency ms`)
  8. **AI Suggestion Acceptance** (`Accepted` vs. `Dismissed` ratio)
- **Visual Charts**:
  - **7-Day Task Velocity Chart** (Created vs. Completed daily dual bars).
  - **7-Day AI Usage Trend** (Cloud Gemini vs. Local Rung-0 fallback volume over the past 7 days).
  - **Open Task Priority Breakdown** (`Urgent`, `High`, `Medium`, `Low` horizontal bars).
  - **User Theme & Top Categories Breakdown** (`System / Light / Dark` adoption + busiest category names).

### Tab 2: Users & Account Governance (`UserInspectorSheet`)
- Real-time search (`displayName`, `email`, `uid`) + filter chips (`All`, `Admins`, `Active`, `Suspended`).
- Tapping any user opens **`UserInspectorSheet`**:
  - Displays workspace stats (`Open`, `Completed`, `Categories`, `Max Streak`, `Last Login`, `Theme`) + **recent tasks preview**.
  - **Edit Display Name** inline.
  - **Send Password Reset Email** (`FirebaseAuth.sendPasswordResetEmail`) in 1 tap.
  - **Make Admin / Revoke Admin** (syncs `/users/{uid}.role` + `/admins/{uid}.enabled`).
  - **Suspend / Reactivate Account** (toggles `/users/{uid}.disabled`).
  - **Delete User Workspace** (deletes `/users/{uid}/tasks`, `/users/{uid}/categories`, `/admins/{uid}`, and `/users/{uid}` after confirmation; blocked for primary owner and active admin session).

### Tab 3: Global Task Explorer & Task CRUD (`AdminTaskEditSheet`)
- Cross-tenant task browser across all users with search (`title`, `description`, `ownerEmail`, `categoryName`) and filter pills (`All`, `Open`, `Completed`, `Overdue`, `Urgent/High`, `Habits`).
- Each task card displays title, owner badge (`email`), priority pill, due status, recurring streak, and checklist count.
- **Direct Admin CRUD**:
  - **1-tap Complete / Reopen** toggle on any task.
  - **Edit Task (`AdminTaskEditSheet`)**: Modify title, description, or priority (`LOW`, `MEDIUM`, `HIGH`, `URGENT`).
  - **Delete Task**: Removes `/users/{ownerUid}/tasks/{taskId}` and logs `ADMIN_DELETE_TASK`.

### Tab 4: AI & Runtime Config
- Live toggles for **Cloud Gemini AI (Master Switch)**, **Task Prioritization**, and **Contextual Suggestions** — all enforced in `FirebaseAiTaskService`.
- **Daily Cloud AI Quota per User** selector (`15`, `25`, `50`, `100`, `250`) dynamically synced into `AiQuotaGuard.runtimeMaxDailyCalls`.
- **Broadcast System Banner**: Maintenance mode switch, 1-tap quick templates (*Scheduled Maintenance*, *AI Upgrade Live*, *Clear*), and a **Live Home Banner Preview** before saving.

### Tab 5: Audit Trail, Data Export & Security
- **Data Export Center (`ExportDataSheet`)**:
  - Generates RFC-4180 **Users CSV**, **Global Tasks CSV**, or **Full Platform Snapshot JSON**.
  - Shares directly via Android's native `Intent.ACTION_SEND` share sheet or copies to clipboard, and records `EXPORT_ADMIN_DATA` in `/admin_audit_logs`.
- **Session & Credential Security**:
  - Shows active authentication mode (`Firebase Native` vs. `Hybrid Owner Session`).
  - **Connect / Upgrade Google Firebase Token** (`GoogleAuthButton`) to link `tellmemaya25@gmail.com`'s Google OAuth session + `EmailAuthProvider` credential in 1 tap when needed.
  - **Change Admin Password (`ChangeAdminPasswordSheet`)** with show/hide toggles.
- **Filterable Immutable Audit Log**:
  - Filter pills (`All`, `Auth`, `Users`, `Tasks`, `Config`, `Export`) + keyword search across all `/admin_audit_logs` entries.

---

## 6. Phased Implementation Plan

| Phase | Deliverables | Key Files |
| :--- | :--- | :--- |
| **Phase 1 — Security Rules & Session Store** | Upgrade `firestore.rules` for admin task/category CRUD; add `AdminSessionStore` DataStore for hybrid owner session persistence; extend `AdminAccessPolicy` anti-lockout guards. | `firestore.rules`, `core/datastore/AdminSessionStore.kt`, `domain/admin/AdminAccessPolicy.kt` |
| **Phase 2 — Domain Models & Export Engine** | Add `AdminTaskRecord`, `AdminExportType`, 7-day AI trend & engagement models, expanded `AdminRepository` CRUD contract, and pure-Kotlin `AdminExportFormatter` (CSV/JSON). | `domain/admin/AdminModels.kt`, `domain/admin/AdminRepository.kt`, `domain/admin/AdminExportFormatter.kt` |
| **Phase 3 — Resilient Auth, Data & AI Enforcement** | Implement 3-stage owner login + Google credential linking in `AuthRepositoryImpl`; implement user/task CRUD + 7-day AI trend in `AdminRepositoryImpl`; bind live config & daily quota in `FirebaseAiTaskService` + `AiQuotaGuard`. | `data/repository/AuthRepositoryImpl.kt`, `data/repository/AdminRepositoryImpl.kt`, `data/ai/FirebaseAiTaskService.kt`, `data/ai/AiQuotaGuard.kt` |
| **Phase 4 — 5-Tab Admin Console UI** | Build upgraded 5-tab `AdminDashboardScreen`, `AdminDashboardViewModel`, `UserInspectorSheet` (with user edit/reset/delete & task preview), `AdminTaskEditSheet`, and `ExportDataSheet`. | `ui/admin/AdminDashboardUiState.kt`, `ui/admin/AdminDashboardViewModel.kt`, `ui/admin/AdminDashboardScreen.kt` |
| **Phase 5 — Verification & Unit Tests** | Unit tests for `AdminAccessPolicy` (RBAC, anti-lockout, digest verification) and `AdminExportFormatter` (CSV escaping & JSON snapshot structure); static import & secret scans. | `app/src/test/java/com/sabihon/todo/AdminAccessPolicyTest.kt`, `AdminExportFormatterTest.kt` |

---

## 7. Deployment & Operations Runbook

1. **Deploy Updated Firestore Security Rules** (choose either CLI or Firebase Console):
   - **Option A — Automated PowerShell Script (Windows, reads `project_id` from `app\google-services.json`)**:
     ```powershell
     .\deploy-firestore-rules.ps1
     ```
   - **Option B — Firebase CLI (`firebase.json` included at repo root)**:
     ```powershell
     firebase use --add   # select your Firebase project once
     firebase deploy --only firestore:rules
     ```
   - **Option C — Firebase Console UI (no CLI required)**:
     Open **Firebase Console -> Firestore Database -> Rules**, paste the contents of `firestore.rules`, and click **Publish**.
2. **Sign In to Admin Dashboard**:
   - Launch Daymark -> on `LoginScreen`, sign in with **`tellmemaya25@gmail.com`** and the configured admin password (or tap **Continue with Google** with `tellmemaya25@gmail.com`).
   - App routes directly to **`daymark . control` (`AdminDashboardScreen`)**.
   - Use the top-right **Workspace** button anytime to switch to your personal task list, and **Settings -> ADMIN -> Admin Dashboard** to return.
