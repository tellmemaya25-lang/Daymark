package com.sabihon.todo

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
        // Create notification channel early to avoid crashes
        try {
            notificationHelper.createChannel()
        } catch (e: Exception) {
            android.util.Log.e("SabihonApp", "Failed to create notification channel", e)
        }
    }
}
