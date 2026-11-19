package dev.personal.berksapps.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import dev.personal.berksapps.BerksApp
import dev.personal.berksapps.MainActivity
import dev.personal.berksapps.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class PairingProgress {
    data object Idle : PairingProgress()
    data object Searching : PairingProgress()
    data class Found(val port: Int) : PairingProgress()
    data object Working : PairingProgress()
    data object Paired : PairingProgress()
    data class Failed(val reason: String) : PairingProgress()
}

class PairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discovery: AdbDiscovery? = null
    private var watcher: Job? = null
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_REPLY -> {
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)?.toString().orEmpty()
                onReply(reply)
            }
            else -> finish()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        discovery?.stop()
        scope.cancel()
        if (progress.value is PairingProgress.Searching ||
            progress.value is PairingProgress.Found ||
            progress.value is PairingProgress.Working
        ) {
            progress.value = PairingProgress.Idle
        }
        super.onDestroy()
    }

    private fun begin() {
        createChannel()
        if (working) {
            foreground(notification("Pairing…", "", false))
            return
        }
        watcher?.cancel()
        discovery?.stop()
        foreground(notification("Looking for the pairing dialog…", SEARCH_HINT, false))
        progress.value = PairingProgress.Searching
        val created = AdbDiscovery(this, AdbDiscovery.TYPE_PAIRING)
        discovery = created
        created.start()
        watcher = scope.launch {
            launch {
                delay(SESSION_TIMEOUT_MS)
                finish()
            }
            created.ports.collect { ports ->
                if (working || progress.value is PairingProgress.Paired) return@collect
                val port = ports.lastOrNull()
                if (port != null) {
                    progress.value = PairingProgress.Found(port)
                    notify(notification("Pairing dialog found", FOUND_HINT, true))
                } else if (progress.value is PairingProgress.Found) {
                    progress.value = PairingProgress.Searching
                    notify(notification("Looking for the pairing dialog…", SEARCH_HINT, false))
                }
            }
        }
    }

    private fun onReply(reply: String) {
        createChannel()
        if (discovery == null) {
            foreground(notification("Pairing…", "", false))
        }
        val numbers = NUMBER.findAll(reply).map { it.value }.toList()
        val code = numbers.firstOrNull { it.length == 6 }
        val typedPort = numbers.firstOrNull { it !== code }?.toIntOrNull()
        val port = typedPort ?: discovery?.ports?.value?.lastOrNull()
        if (code == null) {
            fail("That was not a six-digit code. Try again.")
            return
        }
        if (port == null) {
            fail("The pairing dialog was not found. Type the code and the port, like 123456 37123.")
            return
        }
        working = true
        progress.value = PairingProgress.Working
        notify(notification("Pairing…", "", false))
        val adb = (application as BerksApp).adb
        scope.launch {
            when (val result = adb.pair(code, port)) {
                is PairResult.Paired -> {
                    progress.value = PairingProgress.Paired
                    val connected = adb.connect()
                    postResult(
                        "Paired with this phone",
                        if (connected) "Connected. Go back to Berk's Apps." else "Go back to Berk's Apps to connect.",
                    )
                    finish()
                }
                is PairResult.Failed -> {
                    working = false
                    fail(result.reason)
                }
            }
        }
    }

    private fun fail(reason: String) {
        progress.value = PairingProgress.Failed(reason)
        notify(notification("Pairing did not work", reason, true))
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun foreground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: RuntimeException) {
            notify(notification)
        }
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun postResult(title: String, text: String) {
        val result = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, result)
    }

    private fun notification(title: String, text: String, withReply: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(!withReply)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp())
            .addAction(
                0,
                "Stop",
                PendingIntent.getService(
                    this,
                    REQUEST_STOP,
                    Intent(this, PairingService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (withReply) {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Six-digit pairing code").build()
            val replyIntent = PendingIntent.getService(
                this,
                REQUEST_REPLY,
                Intent(this, PairingService::class.java).setAction(ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Enter pairing code", replyIntent)
                    .addRemoteInput(input)
                    .build()
            )
        }
        return builder.build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pairing", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        private const val ACTION_START = "dev.personal.berksapps.action.PAIRING_START"
        private const val ACTION_REPLY = "dev.personal.berksapps.action.PAIRING_REPLY"
        private const val ACTION_STOP = "dev.personal.berksapps.action.PAIRING_STOP"
        private const val KEY_REPLY = "pairing_code"
        private const val CHANNEL_ID = "pairing"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_NOTIFICATION_ID = 2
        private const val REQUEST_REPLY = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_OPEN = 3
        private const val SESSION_TIMEOUT_MS = 10L * 60 * 1000
        private const val SEARCH_HINT =
            "In Wireless debugging, tap \"Pair device with pairing code\" and leave that dialog open."
        private const val FOUND_HINT =
            "Tap \"Enter pairing code\" below and type the six digits shown in the dialog."
        private val NUMBER = Regex("[0-9]+")

        private val progress = MutableStateFlow<PairingProgress>(PairingProgress.Idle)
        val state: StateFlow<PairingProgress> get() = progress

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, PairingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PairingService::class.java))
        }

        fun reset() {
            if (progress.value is PairingProgress.Failed || progress.value is PairingProgress.Paired) {
                progress.value = PairingProgress.Idle
            }
        }
    }
}
