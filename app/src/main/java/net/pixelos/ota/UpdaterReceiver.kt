/*
 * Copyright (C) 2017-2022 The LineageOS Project
 * Copyright (C) 2025 PixelOS
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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemProperties
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import net.pixelos.ota.misc.Constants
import net.pixelos.ota.misc.StringGenerator.getDateLocalizedUTC
import java.text.DateFormat

class UpdaterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION_INSTALL_REBOOT == intent.action) {
            val pm = context.getSystemService(PowerManager::class.java)!!
            pm.reboot(null)
        } else if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val pref = PreferenceManager.getDefaultSharedPreferences(context)
            pref.edit().remove(Constants.PREF_NEEDS_REBOOT_ID).apply()

            if (shouldShowUpdateFailedNotification(context)) {
                pref.edit().putBoolean(Constants.PREF_INSTALL_NOTIFIED, true).apply()
                showUpdateFailedNotification(context)
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_REBOOT: String = "net.pixelos.ota.action.INSTALL_REBOOT"

        private const val INSTALL_ERROR_NOTIFICATION_CHANNEL = "install_error_notification_channel"

        private fun shouldShowUpdateFailedNotification(context: Context): Boolean {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)

            // We can't easily detect failed re-installations
            if (preferences.getBoolean(Constants.PREF_INSTALL_AGAIN, false) ||
                preferences.getBoolean(Constants.PREF_INSTALL_NOTIFIED, false)
            ) {
                return false
            }

            val buildTimestamp = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
            val lastBuildTimestamp = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, -1)
            return buildTimestamp == lastBuildTimestamp
        }

        private fun showUpdateFailedNotification(context: Context) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val buildDate =
                getDateLocalizedUTC(
                    context,
                    DateFormat.MEDIUM,
                    preferences.getLong(Constants.PREF_INSTALL_NEW_TIMESTAMP, 0)
                )

            val buildInfo =
                context.getString(
                    R.string.list_build_version_date,
                    SystemProperties.get(Constants.PROP_BUILD_VERSION),
                    buildDate
                )

            val notificationIntent = Intent(context, UpdatesActivity::class.java)
            val intent =
                PendingIntent.getActivity(
                    context,
                    0,
                    notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

            val notificationChannel =
                NotificationChannel(
                    INSTALL_ERROR_NOTIFICATION_CHANNEL,
                    context.getString(R.string.update_failed_channel_title),
                    NotificationManager.IMPORTANCE_LOW
                )
            val builder =
                NotificationCompat.Builder(context, INSTALL_ERROR_NOTIFICATION_CHANNEL)
                    .setContentIntent(intent)
                    .setSmallIcon(R.drawable.ic_system_update)
                    .setContentTitle(context.getString(R.string.update_failed_notification))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(buildInfo))
                    .setContentText(buildInfo)

            val nm = context.getSystemService(NotificationManager::class.java)!!
            nm.createNotificationChannel(notificationChannel)
            nm.notify(0, builder.build())
        }
    }
}
