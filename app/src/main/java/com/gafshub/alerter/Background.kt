package com.gafshub.alerter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

// ---------------- notifications ----------------

object Notifier {
    const val CH_MATCHES = "matches"
    const val CH_SERVICE = "service"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MATCHES, "Buying matches", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A new post in the buying section matches one of your search terms"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Fast checking", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while fast mode is on"
            }
        )
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun notifyMatch(ctx: Context, m: Match) {
        if (!canNotify(ctx)) return
        createChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, m.topicId.toInt(),
            Intent(Intent.ACTION_VIEW, Uri.parse(m.url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_MATCHES)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("Buying match: \"${m.term}\"")
            .setContentText(m.title)
            .setSubText(m.category)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(ctx).notify(m.topicId.toInt(), n)
    }

    fun test(ctx: Context) = notifyMatch(
        ctx,
        Match(0, "This is what an alert will look like.", "test", "Buying", Discourse.BASE, "", 0),
    )

    fun serviceNotification(ctx: Context, minutes: Int): Notification {
        createChannels(ctx)
        val openApp = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("Watching the GAFS Hub buying section")
            .setContentText("Checking every $minutes min")
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }
}

// ---------------- scheduling ----------------

/** Regular mode: Android runs a check at most every 15 minutes (a system limit), even with the app closed. */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Checker.run(applicationContext, manual = false)
        return Result.success()
    }
}

object Scheduler {
    private const val WORK = "poll"

    fun apply(ctx: Context) {
        val store = Store(ctx)
        val wm = WorkManager.getInstance(ctx)
        val svc = Intent(ctx, FastCheckService::class.java)

        if (!store.enabled) {
            wm.cancelUniqueWork(WORK)
            ctx.stopService(svc)
            return
        }
        if (store.fastMode) {
            wm.cancelUniqueWork(WORK)
            ContextCompat.startForegroundService(ctx, svc)
        } else {
            ctx.stopService(svc)
            val req = PeriodicWorkRequestBuilder<CheckWorker>(maxOf(15, store.intervalMin).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

/** Fast mode: a foreground service (with a small ongoing notification) that checks every 1-60 minutes. */
class FastCheckService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = Store(this)
        val minutes = store.intervalMin.coerceIn(1, 60)
        val n = Notifier.serviceNotification(this, minutes)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        // restart the loop so a changed interval takes effect
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                Checker.run(applicationContext, manual = false)
                delay(Store(applicationContext).intervalMin.coerceIn(1, 60) * 60_000L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.apply(context)
    }
}
