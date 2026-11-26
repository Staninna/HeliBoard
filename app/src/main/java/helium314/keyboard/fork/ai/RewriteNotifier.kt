// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import helium314.keyboard.fork.ForkSettings
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs

/**
 * Fork: tells the user when a rewrite they walked away from is finished. A long rewrite can take
 * minutes; the keyboard may be hidden or the screen off by then. The notification never shows the
 * text itself, and tapping it opens the app the text came from, where the rewrite panel comes back
 * on the same field.
 */
object RewriteNotifier {
    private const val CHANNEL = "fork_rewrite"
    private const val ID = 0x5EED

    /** The keyboard is on screen, on the field this key names. Set by [ForkAi]. */
    var visibleField: String? = null

    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun enabled(context: Context): Boolean =
        context.prefs().getBoolean(ForkSettings.PREF_REWRITE_NOTIFY, ForkSettings.DEFAULT_REWRITE_NOTIFY)

    /** A rewrite on [field] (in [packageName]) ended; notify unless the user is looking at it. */
    fun finished(context: Context, field: String?, packageName: String?, ready: Boolean) {
        if (!enabled(context) || !allowed(context)) return
        val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
        if (screenOn && field != null && field == visibleField) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.fork_rewrite_notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = packageName?.let { context.packageManager.getLaunchIntentForPackage(it) }?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_fork_rewrite)
            .setContentTitle(context.getString(if (ready) R.string.fork_rewrite_notify_ready else R.string.fork_rewrite_notify_failed))
            .setContentText(context.getString(R.string.fork_rewrite_notify_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        manager.notify(ID, notification)
    }

    /** The user is back on the rewrite, or it was closed. */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
