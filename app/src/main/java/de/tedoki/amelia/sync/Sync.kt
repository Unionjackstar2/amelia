package de.tedoki.amelia.sync

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.tedoki.amelia.data.AppSettings
import de.tedoki.amelia.data.MailRepository
import de.tedoki.amelia.data.SyncMode
import de.tedoki.amelia.data.db.Protocol
import de.tedoki.amelia.mail.imap.ImapConnection
import de.tedoki.amelia.notify.Notifier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SyncScheduler {
    private const val PERIODIC = "amelia.periodic"
    private const val FLUSH = "amelia.flush"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun apply(context: Context, mode: SyncMode) {
        val wm = WorkManager.getInstance(context)
        if (mode == SyncMode.MANUAL) {
            wm.cancelUniqueWork(PERIODIC)
            PushService.stop(context)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(mode.minutes, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        if (mode == SyncMode.PUSH) PushService.start(context) else PushService.stop(context)
    }

    /** Ausstehende Löschungen und Markierungen zügig übertragen. */
    fun flushNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setInputData(workDataOf("flush" to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FLUSH, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun snoozeReminder(context: Context, messageId: Long, at: Long) {
        val delayMs = (at - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<SnoozeWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to messageId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("snooze.$messageId", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelSnoozeReminder(context: Context, messageId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("snooze.$messageId")
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = MailRepository.get(applicationContext)
        return try {
            if (inputData.getBoolean("flush", false)) {
                repo.commitPending()
                repo.pushAllFlags()
            } else {
                repo.syncAll(notify = true)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

class SnoozeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("id", -1)
        val m = MailRepository.get(applicationContext).message(id) ?: return Result.success()
        if (m.snoozedUntil in 1..System.currentTimeMillis() + 60_000) Notifier.snoozeReturned(applicationContext, m)
        return Result.success()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            SyncScheduler.apply(context, AppSettings.get(context).syncMode.value)
        }
    }
}

/**
 * Hält pro IMAP-Konto eine IDLE-Verbindung zum Posteingang offen.
 * POP3-Konten und Server ohne IDLE werden alle 5 Minuten abgefragt.
 */
class PushService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Long, Job>()
    private val restartMutex = Mutex()
    private var pollJob: Job? = null
    private lateinit var repo: MailRepository
    private var connectivity: ConnectivityManager? = null
    private var lastNetworkRestart = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Nach einem Netzwechsel sofort neu verbinden
            val now = System.currentTimeMillis()
            if (now - lastNetworkRestart < 5_000) return
            lastNetworkRestart = now
            scope.launch { restartAll() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = MailRepository.get(this)
        Notifier.createChannels(this)
        val notification = Notifier.pushNotification(this, "Neue E-Mails werden sofort gemeldet")
        try {
            when {
                Build.VERSION.SDK_INT >= 34 ->
                    startForeground(Notifier.PUSH_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= 29 ->
                    startForeground(Notifier.PUSH_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(Notifier.PUSH_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vordergrunddienst nicht erlaubt", e)
            stopSelf()
            return
        }
        connectivity = getSystemService(ConnectivityManager::class.java)
        try { connectivity?.registerDefaultNetworkCallback(networkCallback) } catch (_: Exception) {}

        scope.launch {
            repo.flowAccounts()
                .map { list -> list.map { it.id to it.protocol } }
                .distinctUntilChanged()
                .collect { restartAll() }
        }
    }

    private suspend fun restartAll() = restartMutex.withLock {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pollJob?.cancel()
        val accounts = repo.allAccounts()
        for (a in accounts.filter { it.protocol == Protocol.IMAP }) {
            jobs[a.id] = scope.launch { idleLoop(a.id) }
        }
        pollJob = scope.launch {
                while (isActive) {
                    delay(5 * 60_000L)
                    for (a in repo.allAccounts()) {
                    if (a.protocol == Protocol.POP3 || !a.supportsIdle) repo.syncAccount(a.id, notify = true)
                }
            }
        }
    }

    private suspend fun CoroutineScope.idleLoop(accountId: Long) {
        var backoff = 10_000L
        var first = true
        while (isActive) {
            val account = repo.account(accountId) ?: return
            if (first) {
                // Erstabgleich aller Ordner über eine eigene Verbindung
                repo.syncAccount(accountId, notify = true)
                first = false
            }
            val conn = try {
                ImapConnection(repo.incomingConfig(account))
            } catch (e: Exception) {
                return // Passwort nicht lesbar
            }
            val closer = launch {
                try { awaitCancellation() } finally { conn.close() }
            }
            try {
                conn.open()
                if (!conn.has("IDLE")) {
                    conn.logout()
                    return
                }
                val inbox = repo.inboxPath(accountId)
                repo.syncInboxOn(conn, accountId)
                conn.select(inbox)
                backoff = 10_000L
                while (isActive) {
                    val changed = conn.idle(IDLE_MILLIS) { !isActive }
                    if (!isActive) break
                    if (changed) {
                        repo.syncInboxOn(conn, accountId)
                        conn.select(inbox)
                    } else {
                        conn.noop()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "IDLE ${account.email}: ${e.message}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
            } finally {
                closer.cancel()
                conn.close()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { connectivity?.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AmeliaPush"
        private const val IDLE_MILLIS = 24 * 60_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PushService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Push-Dienst konnte nicht starten: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
        }
    }
}
