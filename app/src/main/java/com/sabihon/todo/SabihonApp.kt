package com.sabihon.todo

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
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
     * App Check, installed before anything can make a request.
     *
     * Firebase enforces it for AI Logic from 2026-11-02 and it cannot be switched off
     * afterwards, so a build without this would call the model successfully today and
     * be blocked on that date. Play Integrity attests release builds; the debug provider
     * prints a token to Logcat that has to be registered in the Firebase console before
     * AI calls work on a debug build.
     *
     * Wrapped because a failure here must not stop the app from starting - every other
     * feature still works without App Check.
     */
    private fun installAppCheck() {
        try {
            val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val factory = if (debuggable) {
                DebugAppCheckProviderFactory.getInstance()
            } else {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory)
        } catch (e: Exception) {
            android.util.Log.e("SabihonApp", "App Check not installed", e)
        }
    }
}
