package de.tedoki.amelia.notify

import android.Manifest
import de.tedoki.amelia.data.db.senderDisplay
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.tedoki.amelia.MainActivity
import de.tedoki.amelia.R
import de.tedoki.amelia.data.AppSettings
import de.tedoki.amelia.data.MailRepository
import de.tedoki.amelia.data.NotificationContent
import de.tedoki.amelia.data.db.Account
import de.tedoki.amelia.data.db.Message
import de.tedoki.amelia.data.db.Pending
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Notifier {
    const val CHANNEL_MAIL = "mail"
    const val CHANNEL_PUSH = "push"
    const val CHANNEL_REMINDER = "reminder"
    const val EXTRA_MESSAGE_ID = "messageId"
    const val PUSH_NOTIFICATION_ID = 1

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIL, "Neue E-Mails", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Meldet neu eingegangene Nachrichten"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Geparkte Nachrichten", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Erinnert, wenn eine geparkte Nachricht zurück ist"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PUSH, "Push-Verbindung", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Hält die Verbindung für Sofort-Benachrichtigungen offen"
                setShowBadge(false)
            }
        )
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openIntent(context: Context, messageId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        return PendingIntent.getActivity(
            context, messageId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun action(context: Context, action: String, messageId: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        return PendingIntent.getBroadcast(
            context, (messageId * 4 + action.hashCode() % 4).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun newMail(context: Context, account: Account, list: List<Message>) {
        if (!allowed(context) || list.isEmpty() || !account.notify) return
        val settings = AppSettings.get(context)
        val content = settings.notificationContent.value
        val silent = settings.inQuietHours()
        val nm = NotificationManagerCompat.from(context)
        val group = "account_${account.id}"
        for (m in list.sortedBy { it.date }.takeLast(8)) {
            val subject = m.subject.ifBlank { "(Kein Betreff)" }
            val (title, text) = when (content) {
                NotificationContent.FULL -> m.senderDisplay to subject
                NotificationContent.SENDER -> m.senderDisplay to "Neue E-Mail"
                NotificationContent.MINIMAL -> "Neue E-Mail" to account.label
            }
            // Version für den Sperrbildschirm: immer ohne Inhalt
            val public = NotificationCompat.Builder(context, CHANNEL_MAIL)
                .setSmallIcon(R.drawable.ic_stat_mail)
                .setColor(account.color)
                .setContentTitle("Neue E-Mail")
                .setContentText(account.label)
                .build()
            val builder = NotificationCompat.Builder(context, CHANNEL_MAIL)
                .setSmallIcon(R.drawable.ic_stat_mail)
                .setColor(account.color)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(account.label)
                .setWhen(m.date)
                .setGroup(group)
                .setAutoCancel(true)
                .setSilent(silent)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(openIntent(context, m.id))
                .addAction(0, "Gelesen", action(context, NotificationActionReceiver.ACTION_READ, m.id))
                .addAction(0, "Löschen", action(context, NotificationActionReceiver.ACTION_DELETE, m.id))
            if (content == NotificationContent.FULL) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText("$subject\n${m.snippet}"))
            }
            try { nm.notify(m.id.toInt(), builder.build()) } catch (_: SecurityException) {}
        }
        val summary = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setColor(account.color)
            .setContentTitle(account.label)
            .setContentText("Neue Nachrichten")
            .setGroup(group)
            .setGroupSummary(true)
            .setSilent(silent)
            .setAutoCancel(true)
            .build()
        try { nm.notify(group.hashCode(), summary) } catch (_: SecurityException) {}
    }

    fun snoozeReturned(context: Context, m: Message) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle("Wieder da: ${m.subject.ifBlank { "(Kein Betreff)" }}")
            .setContentText(m.senderDisplay)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, m.id))
            .build()
        try { NotificationManagerCompat.from(context).notify(m.id.toInt(), n) } catch (_: SecurityException) {}
    }

    fun cancel(context: Context, messageId: Long) = NotificationManagerCompat.from(context).cancel(messageId.toInt())

    fun pushNotification(context: Context, text: String) = NotificationCompat.Builder(context, CHANNEL_PUSH)
        .setSmallIcon(R.drawable.ic_stat_mail)
        .setContentTitle("Amelia ist empfangsbereit")
        .setContentText(text)
        .setOngoing(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Notifier.EXTRA_MESSAGE_ID, -1)
        if (id < 0) return
        Notifier.cancel(context, id)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = MailRepository.get(context)
                when (intent.action) {
                    ACTION_READ -> repo.setSeen(listOf(id), true)
                    ACTION_DELETE -> repo.markPending(listOf(id), Pending.TRASH, immediate = true)
                }
                de.tedoki.amelia.sync.SyncScheduler.flushNow(context)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_READ = "de.tedoki.amelia.READ"
        const val ACTION_DELETE = "de.tedoki.amelia.DELETE"
    }
}
