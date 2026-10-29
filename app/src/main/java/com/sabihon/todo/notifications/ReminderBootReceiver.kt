package com.sabihon.todo.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-arms every pending reminder after a reboot.
 *
 * `AlarmManager` is cleared at shutdown, so without this the app stops reminding people after
 * their first restart - silently, and only noticeable days later. The queue in
 * [ScheduledReminderStore] exists precisely so this receiver needs no network: a boot receiver
 * has a short window and no guarantee of connectivity.
 */
@AndroidEntryPoint
class ReminderBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: ReminderScheduler

    @Inject
    lateinit var notificationHelper: NotificationHelper

    override fun onReceive(context: Context, intent: Intent) {
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        if (!relevant) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                notificationHelper.ensureChannels()
                scheduler.rescheduleAll()
            } catch (e: Exception) {
                android.util.Log.e("ReminderBoot", "Could not restore reminders", e)
            } finally {
                pending.finish()
            }
        }
    }
}
