package com.gng.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gng.app.BuildConfig
import com.gng.app.container
import com.gng.core.AppVersions
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "gng-update"

/**
 * Keeps gng up to date without anyone doing anything.
 *
 * A background job checks GitHub Releases every few hours (and right away when a release push
 * arrives), downloads the new APK, checks it's a newer build signed with the same key, and installs
 * it once gng isn't on screen and you're not in a call.
 *
 * From Android 12, an app may update itself without a prompt once it installed the version that's
 * there now. So the first update may still ask "Install?" (via a notification); after that they're
 * silent. On older Android it always asks, but everything up to that tap is automatic.
 */
object AutoUpdate {
    private const val PERIODIC = "auto-update"
    private const val NOW = "auto-update-now"

    /** Turns the background check on or off (follows the Settings switch). */
    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled || !BuildConfig.UPDATE_ENABLED) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(4, TimeUnit.HOURS)
            .setConstraints(connected())
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Check (and update) as soon as possible, e.g. a release was just published. [delayMinutes]: wait first. */
    fun runNow(context: Context, delayMinutes: Long = 0) {
        if (!BuildConfig.UPDATE_ENABLED) return
        val builder = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(connected())
        if (delayMinutes > 0) builder.setInitialDelay(delayMinutes, TimeUnit.MINUTES)
        else builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, builder.build())
    }

    /** You just left the app: if an update is downloaded and waiting for that, install it now. */
    fun onAppBackgrounded(context: Context) {
        if (ApkInstaller(context).pendingDownload(BuildConfig.VERSION_NAME) != null) runNow(context)
    }

    private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!BuildConfig.UPDATE_ENABLED || !c.settings.autoUpdate.first()) return Result.success()
        val installer = ApkInstaller(applicationContext)
        return try {
            val latest = UpdateChecker(BuildConfig.UPDATE_API_URL).latest()
            c.settings.setLastUpdateCheck(System.currentTimeMillis())
            if (latest == null || !AppVersions.isNewer(latest.version, BuildConfig.VERSION_NAME)) return Result.success()
            val file = installer.download(latest) {}
            installer.verify(file)
            // Installing restarts gng, so never while you're using it or on a call. Try again in a bit.
            if (c.foreground || c.calls.state.value != null) {
                Log.i(TAG, "${latest.version} downloaded, waiting until gng is in the background")
                AutoUpdate.runNow(applicationContext, delayMinutes = 15)
                return Result.success()
            }
            Log.i(TAG, "installing ${latest.version}")
            installer.installInBackground(file)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "auto-update failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

/** Hears back from Android's installer about a background update. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Android wants a tap this time (first update, or Android 11 and older).
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (c.foreground) {
                    runCatching { context.startActivity(confirm) }
                } else {
                    c.notifier.showUpdateReady(
                        PendingIntent.getActivity(context, 4343, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // we're being replaced; MY_PACKAGE_REPLACED says hello from the new version
            else -> Log.w(TAG, "install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

/** Runs in the new version right after an update: say so, and keep the background check going. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val c = context.container
        c.notifier.cancelUpdateReady()
        c.notifier.showUpdated(BuildConfig.VERSION_NAME)
        AutoUpdate.schedule(context, enabled = true)
        File(context.cacheDir, "updates").deleteRecursively()
    }
}
