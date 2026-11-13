package app.honorless.debloater

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

/** Device admin entry point. Becomes device owner via: adb shell dpm set-device-owner app.honorless.debloater/.AdminReceiver */
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
                // No wiping here: a boot broadcast only has a few seconds. The run 5 minutes later wipes.
                val e = Enforcer(context)
                e.enforce(trigger, wipe = false)
                // Read it all back. Problems are only reported if the run 5 minutes later still finds them.
                val problems = e.checkup(trigger, notify = false)
                if (trigger == "boot") e.recordBootCheck(problems) else e.catchUpIfBootMissed(trigger, problems)
            } catch (e: Exception) {
                Store(context).log("[$trigger] crashed: $e")
            } finally {
                pending.finish()
            }
        }.start()
        // Some packages appear late in boot; check again a few minutes later.
        Scheduler.runSoon(context, "$trigger+5min", delayMs = 5 * 60_000L)
        // A tablet that's often off can miss the twice-daily update check, so look once after every restart too.
        if (trigger == "boot") Scheduler.updateSoon(context, delayMs = 10 * 60_000L)
    }
}

/** Periodic and one-off runs. */
class EnforceJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val trigger = params.extras.getString("trigger") ?: "periodic"
        if (params.extras.getString("task") == "update") {
            Thread {
                try {
                    if (Enforcer(this).isOwner()) Installer(this).autoUpdateAll()
                } catch (e: java.io.IOException) {
                    Store(this).log("[app updates] ${NetDiagnosis.explain(this, e, "GitHub")}")
                } catch (e: Exception) {
                    Store(this).log("[app updates] didn't finish: ${e.message}")
                } finally {
                    jobFinished(params, false)
                }
            }.start()
            return true
        }
        Thread {
            try {
                val e = Enforcer(this)
                e.enforce(trigger)
                if (e.isOwner()) e.catchUpIfBootMissed(trigger, e.checkup(trigger))
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
    private const val UPDATE_ID = 3
    private const val UPDATE_SOON_ID = 4
    private const val PERIOD_MS = 6 * 60 * 60 * 1000L
    const val UPDATE_PERIOD_MS = 12 * 60 * 60 * 1000L

    private fun component(ctx: Context) = ComponentName(ctx, EnforceJob::class.java)

    fun ensurePeriodic(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(PERIODIC_ID) == null) {
            js.schedule(
                JobInfo.Builder(PERIODIC_ID, component(ctx))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build()
            )
        }
        // Twice-daily check for app updates (this app, Lawnchair, HeliBoard), only when there's a network. A job
        // scheduled by an older version with a different interval is replaced.
        val upd = js.getPendingJob(UPDATE_ID)
        if (upd == null || upd.intervalMillis != UPDATE_PERIOD_MS) {
            js.schedule(
                JobInfo.Builder(UPDATE_ID, component(ctx))
                    .setPeriodic(UPDATE_PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setExtras(PersistableBundle().apply { putString("task", "update") })
                    .build()
            )
        }
    }

    /** Check for app updates once the network is up, [delayMs] from now (after a restart, say). */
    fun updateSoon(ctx: Context, delayMs: Long) {
        ctx.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(UPDATE_SOON_ID, component(ctx))
                .setMinimumLatency(delayMs)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(PersistableBundle().apply { putString("task", "update") })
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
            NotificationChannel(CHANNEL, "Honorless Debloater", NotificationManager.IMPORTANCE_DEFAULT)
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

/**
 * Receives the result of installing an app, stripping an app's updates, or uninstalling an app you
 * installed, and logs it. Not exported: only this app's own PendingIntent reaches it.
 */
class PackageResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_TARGET) ?: "?"
        val action = intent.getStringExtra(EXTRA_ACTION) ?: "uninstall"
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val ok = status == android.content.pm.PackageInstaller.STATUS_SUCCESS
        Store(context).log(when (action) {
            "install" -> if (ok) "installed $pkg" else "couldn't install $pkg (status $status) $msg"
            else -> if (ok) "stripped updates from (or uninstalled) $pkg" else "couldn't strip updates from $pkg (status $status) $msg"
        })
        val pending = status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION
        if (action == "install" && !ok && !pending) {
            try { Installer(context).installFailed(pkg, status) } catch (e: Exception) { }
        }
        if (action == "install") {
            val name = if (pkg == context.packageName) "Honorless Debloater" else pkg
            Notifier.show(context, "Honorless Debloater", if (ok) "Installed $name." else "Couldn't install $name: $msg")
        }
    }

    companion object {
        private const val EXTRA_TARGET = "app.honorless.debloater.TARGET"
        private const val EXTRA_ACTION = "app.honorless.debloater.ACTION"

        /** Explicit, so it's safe to make mutable: the system has to add the result to it. */
        fun sender(ctx: Context, pkg: String, action: String = "uninstall"): android.content.IntentSender {
            val intent = Intent(ctx, PackageResultReceiver::class.java).putExtra(EXTRA_TARGET, pkg).putExtra(EXTRA_ACTION, action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            return PendingIntent.getBroadcast(ctx, (action + pkg).hashCode(), intent, flags).intentSender
        }
    }
}
