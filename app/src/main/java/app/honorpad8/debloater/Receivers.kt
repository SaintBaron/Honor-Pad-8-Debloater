package app.honorpad8.debloater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle

/** Device admin entry point. Becomes device owner via: adb shell dpm set-device-owner app.honorpad8.debloater/.AdminReceiver */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Scheduler.runSoon(context, "admin-enabled")
    }
}

/**
 * Runs the enforcer straight after boot and after this app is updated.
 * Exported so the system can deliver these broadcasts; both actions are "protected" broadcasts
 * that only the system can send, and anything else is ignored. The worst another app could
 * cause is an extra (harmless, idempotent) enforcement run.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app-updated"
            else -> return
        }
        if (!Enforcer(context).isOwner()) return   // not set up (or released): do nothing
        Scheduler.ensurePeriodic(context)
        val pending = goAsync()
        Thread {
            try {
                Enforcer(context).enforce(trigger)
            } catch (e: Exception) {
                Store(context).log("[$trigger] crashed: $e")
            } finally {
                pending.finish()
            }
        }.start()
        // Some packages appear late in boot; check again a few minutes later.
        Scheduler.runSoon(context, "$trigger+5min", delayMs = 5 * 60_000L)
    }
}

/** Periodic and one-off runs. */
class EnforceJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val trigger = params.extras.getString("trigger") ?: "periodic"
        Thread {
            try {
                Enforcer(this).enforce(trigger)
            } catch (e: Exception) {
                Store(this).log("[$trigger] crashed: $e")
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

object Scheduler {
    private const val PERIODIC_ID = 1
    private const val ONESHOT_ID = 2
    private const val PERIOD_MS = 6 * 60 * 60 * 1000L

    private fun component(ctx: Context) = ComponentName(ctx, EnforceJob::class.java)

    fun ensurePeriodic(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(PERIODIC_ID) != null) return
        js.schedule(
            JobInfo.Builder(PERIODIC_ID, component(ctx))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build()
        )
    }

    fun cancelAll(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java).cancelAll()
    }

    fun runSoon(ctx: Context, trigger: String, delayMs: Long = 0L) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        val extras = PersistableBundle().apply { putString("trigger", trigger) }
        js.schedule(
            JobInfo.Builder(ONESHOT_ID, component(ctx))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 60_000L)
                .setExtras(extras)
                .build()
        )
        ensurePeriodic(ctx)
    }
}

object Notifier {
    private const val CHANNEL = "debloater"

    fun show(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Honor Pad 8 Debloater", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)   // no package names on the lock screen
            .build()
        try {
            nm.notify(1, n)
        } catch (e: SecurityException) {
            // notifications not allowed; the log still has it
        }
    }
}
