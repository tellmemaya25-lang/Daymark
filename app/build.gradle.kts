plugins {
    // Fixed: Do NOT specify versions here – versions are defined in root build.gradle.kts with apply false
    // This avoids "plugin is already on the classpath with an unknown version" error
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
}

// --- Firebase config bootstrap -------------------------------------------------
// `app/google-services.json` is gitignored, so a fresh clone has no config and the
// build fails with "File google-services.json is missing" - which leaves the old
// APK installed, so the rebrand never appears. The repo ships the same config as
// `app/cls`, so copy it into place whenever it is missing.
// An existing google-services.json is never touched.
val bundledFirebaseConfig = file("cls")
val firebaseConfig = file("google-services.json")
if (!firebaseConfig.exists() && bundledFirebaseConfig.exists()) {
    bundledFirebaseConfig.copyTo(firebaseConfig)
    logger.lifecycle("daymark: created app/google-services.json from app/cls")
}

android {
    namespace = "com.sabihon.todo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sabihon.todo"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Removed applicationIdSuffix to avoid google-services.json mismatch
            // If you want .debug suffix, add com.sabihon.todo.debug to Firebase project
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "2.1.20"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    // Hilt (2.56+ supports Kotlin metadata 2.2.0)
    implementation("com.google.dagger:hilt-android:2.56")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    ksp("com.google.dagger:hilt-compiler:2.56")

    // Firebase BOM
    //
    // 35.1.0 is the first line that ships `firebase-ai` (the KTX-era `firebase-vertexai`
    // is gone). This is a major bump for every Firebase library at once, so it is worth
    // smoke-testing sign-in, add, complete, activity and restore after it lands.
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-analytics")

    // AI quick add. `firebase-ai` talks to the Gemini Developer API through the Firebase
    // project, so no API key ships in the app.
    implementation("com.google.firebase:firebase-ai")

    // Required, not optional: Firebase enforces App Check for AI Logic from 2026-11-02,
    // and requests without a token are rejected from that date on. Play Integrity for
    // release builds, the debug provider for local runs.
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")

    // Play Services Auth (Google Sign-In)
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    // Coil for profile image
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Coroutines + DateTime + Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // runTest lives here, not in coroutines-core. Its absence is why the three use-case
    // test classes could not compile: every `runTest` was an unresolved reference and
    // every `suspend` call inside them was an error.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
