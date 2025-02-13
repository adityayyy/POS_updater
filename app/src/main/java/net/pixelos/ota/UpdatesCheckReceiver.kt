/*
 * Copyright (C) 2017-2022 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.pixelos.ota

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import net.pixelos.ota.download.DownloadClient
import net.pixelos.ota.misc.Constants
import net.pixelos.ota.misc.Utils.checkForNewUpdates
import net.pixelos.ota.misc.Utils.cleanupDownloadsDir
import net.pixelos.ota.misc.Utils.getCachedUpdateList
import net.pixelos.ota.misc.Utils.getServerURL
import net.pixelos.ota.misc.Utils.isNetworkAvailable
import net.pixelos.ota.misc.Utils.isUpdateCheckEnabled
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.UUID

class UpdatesCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            cleanupDownloadsDir(context)
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        if (!isUpdateCheckEnabled(context)) {
            return
        }

        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            // Set a repeating alarm on boot to check for new updates once per day
            scheduleRepeatingUpdatesCheck(context)
        }

        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "Network not available, scheduling new check")
            scheduleUpdatesCheck(context)
            return
        }

        val json = getCachedUpdateList(context)
        val jsonNew = File(json.absolutePath + UUID.randomUUID())
        val url = getServerURL(context)
        val callback: DownloadClient.DownloadCallback =
            object : DownloadClient.DownloadCallback {
                override fun onFailure(cancelled: Boolean) {
                    Log.e(TAG, "Could not download updates list, scheduling new check")
                    scheduleUpdatesCheck(context)
                }

                override fun onResponse(headers: DownloadClient.Headers) {}

                override fun onSuccess() {
                    try {
                        if (json.exists() && checkForNewUpdates(json, jsonNew)) {
                            showNotification(context)
                            updateRepeatingUpdatesCheck(context)
                        }
                        jsonNew.renameTo(json)
                        val currentMillis = System.currentTimeMillis()
                        preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, currentMillis)
                            .apply()
                        // In case we set a one-shot check because of a previous failure
                        cancelUpdatesCheck(context)
                    } catch (e: IOException) {
                        Log.e(TAG, "Could not parse list, scheduling new check", e)
                        scheduleUpdatesCheck(context)
                    } catch (e: JSONException) {
                        Log.e(TAG, "Could not parse list, scheduling new check", e)
                        scheduleUpdatesCheck(context)
                    }
                }
            }

        try {
            val downloadClient =
                DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonNew)
                    .setDownloadCallback(callback)
                    .build()
            downloadClient.start()
        } catch (e: IOException) {
            Log.e(TAG, "Could not fetch list, scheduling new check", e)
            scheduleUpdatesCheck(context)
        }
    }

    companion object {
        private const val TAG = "UpdatesCheckReceiver"

        private const val DAILY_CHECK_ACTION = "daily_check_action"
        private const val ONESHOT_CHECK_ACTION = "oneshot_check_action"

        private const val NEW_UPDATES_NOTIFICATION_CHANNEL = "new_updates_notification_channel"

        private fun showNotification(context: Context) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)!!
            val notificationChannel =
                NotificationChannel(
                    NEW_UPDATES_NOTIFICATION_CHANNEL,
                    context.getString(R.string.new_updates_channel_title),
                    NotificationManager.IMPORTANCE_LOW
                )
            val notificationBuilder =
                NotificationCompat.Builder(context, NEW_UPDATES_NOTIFICATION_CHANNEL)
            notificationBuilder.setSmallIcon(R.drawable.ic_system_update)
            val notificationIntent = Intent(context, UpdatesActivity::class.java)
            val intent =
                PendingIntent.getActivity(
                    context,
                    0,
                    notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            notificationBuilder.setContentIntent(intent)
            notificationBuilder.setContentTitle(context.getString(R.string.new_updates_found_title))
            notificationBuilder.setAutoCancel(true)
            notificationManager.createNotificationChannel(notificationChannel)
            notificationManager.notify(0, notificationBuilder.build())
        }

        private fun getRepeatingUpdatesCheckIntent(context: Context): PendingIntent {
            val intent = Intent(context, UpdatesCheckReceiver::class.java)
            intent.setAction(DAILY_CHECK_ACTION)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        fun updateRepeatingUpdatesCheck(context: Context) {
            cancelRepeatingUpdatesCheck(context)
            scheduleRepeatingUpdatesCheck(context)
        }

        fun scheduleRepeatingUpdatesCheck(context: Context) {
            if (!isUpdateCheckEnabled(context)) {
                return
            }

            val updateCheckIntent = getRepeatingUpdatesCheckIntent(context)
            val alarmMgr = context.getSystemService(AlarmManager::class.java)!!
            val checkAutoUpdateTime = AlarmManager.INTERVAL_DAY * 14 // 2 weeks
            alarmMgr.setRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + checkAutoUpdateTime,
                checkAutoUpdateTime,
                updateCheckIntent
            )

            val nextCheckDate = Date(System.currentTimeMillis() + checkAutoUpdateTime)
            Log.d(TAG, "Setting automatic updates check: $nextCheckDate")
        }

        fun cancelRepeatingUpdatesCheck(context: Context) {
            val alarmMgr = context.getSystemService(AlarmManager::class.java)!!
            alarmMgr.cancel(getRepeatingUpdatesCheckIntent(context))
        }

        private fun getUpdatesCheckIntent(context: Context): PendingIntent {
            val intent = Intent(context, UpdatesCheckReceiver::class.java)
            intent.setAction(ONESHOT_CHECK_ACTION)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        fun scheduleUpdatesCheck(context: Context) {
            val millisToNextCheck = AlarmManager.INTERVAL_HOUR * 2
            val updateCheckIntent = getUpdatesCheckIntent(context)
            val alarmMgr = context.getSystemService(AlarmManager::class.java)!!
            alarmMgr[AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + millisToNextCheck] =
                updateCheckIntent

            val nextCheckDate = Date(System.currentTimeMillis() + millisToNextCheck)
            Log.d(TAG, "Setting one-shot updates check: $nextCheckDate")
        }

        fun cancelUpdatesCheck(context: Context) {
            val alarmMgr = context.getSystemService(AlarmManager::class.java)!!
            alarmMgr.cancel(getUpdatesCheckIntent(context))
            Log.d(TAG, "Cancelling pending one-shot check")
        }
    }
}
