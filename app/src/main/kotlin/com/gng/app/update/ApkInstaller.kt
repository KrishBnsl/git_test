package com.gng.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.gng.core.AppVersions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads an APK, checks it really is a newer build of this app, and installs it. */
class ApkInstaller(private val context: Context) {
    private val dir: File get() = File(context.cacheDir, "updates")

    /** Deletes downloads that aren't newer than [current] (they've been installed, or were superseded). */
    fun clearOldDownloads(current: String) {
        dir.listFiles().orEmpty().forEach { f ->
            val version = f.name.removePrefix("gng-").removeSuffix(".part").removeSuffix(".apk")
            if (f.name.endsWith(".part") || !AppVersions.isNewer(version, current)) f.delete()
        }
    }

    /** A finished download newer than [current], waiting to be installed. */
    fun pendingDownload(current: String): File? =
        dir.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
            .filter { AppVersions.isNewer(it.name.removePrefix("gng-").removeSuffix(".apk"), current) }
            .maxByOrNull { it.lastModified() }

    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, "gng-${info.version}.apk")
        // Downloaded earlier (e.g. while you were using the app): no need to fetch it again.
        if (target.exists() && runCatching { verify(target) }.isSuccess) return@withContext target
        val partial = File(dir, "gng-${info.version}.apk.part")
        val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "gng-android")
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true // GitHub hands the file off to its CDN
        }
        try {
            if (connection.responseCode != 200) throw IllegalStateException("Download failed (${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            var lastReported = -1
            connection.inputStream.use { input ->
                partial.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
            target.delete()
            check(partial.renameTo(target)) { "Couldn't save the download" }
            target
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Throws with a human-readable message unless [file] is a newer build of this app signed with the same
     * key. Android would refuse a mismatched one anyway; checking first means a clear message instead of
     * a vague "app not installed".
     */
    fun verify(file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: error("The download is damaged. Try again.")
        check(archive.packageName == context.packageName) { "That file isn't an update for gng." }
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(versionCode(archive) > versionCode(installed)) { "That version isn't newer than the one you have." }
        check(signers(archive) == signers(installed)) {
            "This update is signed with a different key than your installed app. Uninstall gng and install the new version once."
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Installs through Android's package installer. On Android 12+ this needs no tap once gng installed
     * the current version itself (and on 14+ gng claims to be the one that updates it). Otherwise
     * Android asks, and [UpdateInstallReceiver] shows that prompt or a notification.
     */
    fun installInBackground(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("gng.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    /** Android's "Install unknown apps" switch for gng. */
    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun signers(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().map { it.toCharsString() }.toSet()
        }
}
