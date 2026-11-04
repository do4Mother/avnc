/*
 * SPDX-License-Identifier:  GPL-3.0-or-later
 */

package com.gaurav.avnc.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gaurav.avnc.R
import com.gaurav.avnc.ui.vnc.VncActivity

/**
 * Foreground service (type mediaPlayback) that keeps the process, and therefore desktop audio,
 * alive while VncActivity is in the background. It also holds a Wi-Fi lock and shows an ongoing
 * notification with a Disconnect action.
 *
 * The service does not own the session. Playback lives in VncViewModel; this service only
 * keeps the process prioritised. Disconnect is forwarded to [onDisconnect], which the
 * ViewModel of the active audio session registers.
 */
class AudioService : Service() {

    companion object {
        private const val TAG = "AudioService"
        private const val CHANNEL_ID = "audio_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.gaurav.avnc.audio.action.START"
        const val ACTION_DISCONNECT = "com.gaurav.avnc.audio.action.DISCONNECT"
        private const val EXTRA_SERVER_NAME = "server_name"

        /**
         * Process-wide handler invoked (on main thread) when user taps Disconnect in the notification.
         * Registered by the ViewModel owning the active audio session.
         */
        @Volatile
        var onDisconnect: (() -> Unit)? = null

        fun start(context: Context, serverName: String) {
            val intent = Intent(context, AudioService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SERVER_NAME, serverName)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // E.g. ForegroundServiceStartNotAllowedException if we connected while in background.
                // Audio still plays, just without background protection.
                Log.e(TAG, "Cannot start audio service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioService::class.java))
        }
    }

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> handleDisconnect()
            else -> {
                val name = intent?.getStringExtra(EXTRA_SERVER_NAME) ?: ""
                goForeground(name)
                acquireWifiLock()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWifiLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    @MainThread
    private fun handleDisconnect() {
        val handler = onDisconnect
        if (handler != null)
            handler()
        else
            stopSelf() // No session owns us anymore
    }

    private fun goForeground(serverName: String) {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(serverName), type)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot enter foreground", e)
            stopSelf()
        }
    }

    private fun createChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.audio_notification_channel))
                .setShowBadge(false)
                .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(serverName: String): Notification {
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

        val openIntent = Intent(this, VncActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                          Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val openPi = PendingIntent.getActivity(this, 0, openIntent, immutable or PendingIntent.FLAG_UPDATE_CURRENT)

        val disconnectIntent = Intent(this, AudioService::class.java).setAction(ACTION_DISCONNECT)
        val disconnectPi = PendingIntent.getService(this, 1, disconnectIntent, immutable or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_computer)
                .setContentTitle(getString(R.string.audio_notification_title, serverName))
                .setContentIntent(openPi)
                .addAction(0, getString(R.string.audio_notification_disconnect), disconnectPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "avnc:audio").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot acquire Wi-Fi lock", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot release Wi-Fi lock", e)
        }
        wifiLock = null
    }
}
