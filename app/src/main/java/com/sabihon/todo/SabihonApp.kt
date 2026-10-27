package com.sabihon.todo

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.sabihon.todo.notifications.NotificationHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for daymark . – entry point for Hilt DI.
 * Implements WorkManager Configuration.Provider for Hilt workers.
 */
@HiltAndroidApp
class SabihonApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var notificationHelper: NotificationHelper

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        installAppCheck()
        // Create notification channel early to avoid crashes
        try {
            notificationHelper.createChannel()
        } catch (e: Exception) {
            android.util.Log.e("SabihonApp", "Failed to create notification channel", e)
        }
    }

    /**
     * App Check for release builds via Play Integrity.
     *
     * On local debug builds we intentionally avoid installing an unregistered
     * `DebugAppCheckProviderFactory`: an unregistered debug secret fails
     * `exchangeDebugToken` with HTTP 403 and causes `firebase-ai` requests to be
     * rejected before reaching Gemini whenever the developer has not manually copied a
     * per-device UUID from Logcat into Firebase Console.
     */
    private fun installAppCheck() {
        try {
            val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (!debuggable) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("SabihonApp", "App Check not installed", e)
        }
    }
}
