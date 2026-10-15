package com.gng.app.update

import android.content.Context
import com.gng.app.BuildConfig
import com.gng.app.data.SettingsStore
import com.gng.core.AppVersions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Finds, downloads and installs new versions of gng from GitHub Releases.
 *
 * With "Update automatically" on (the default), [AutoUpdate] does all of this in the background and
 * this class only serves the Settings card ("Check" by hand, or auto-update switched off).
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val checker: UpdateChecker = UpdateChecker(BuildConfig.UPDATE_API_URL),
    private val installer: ApkInstaller = ApkInstaller(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val enabled: Boolean get() = BuildConfig.UPDATE_ENABLED
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    init {
        // Anything left from an earlier update has done its job.
        if (enabled) installer.clearOldDownloads(currentVersion)
    }

    /** On app start: look for an update, but not more than every few hours. */
    fun checkIfDue() {
        if (!enabled) return
        scope.launch {
            if (settings.autoUpdate.first()) {
                // Automatic: make sure the background check is scheduled, and run one now if it's been a while.
                AutoUpdate.schedule(context, enabled = true)
                if (System.currentTimeMillis() - settings.lastUpdateCheck() > CHECK_EVERY_MS) AutoUpdate.runNow(context)
                return@launch
            }
            val age = System.currentTimeMillis() - settings.lastUpdateCheck()
            if (age > CHECK_EVERY_MS) check(userInitiated = false)
        }
    }

    /** From Settings, or the app start check ([userInitiated] false: stay quiet unless there's news). */
    fun check(userInitiated: Boolean = true) {
        if (!enabled) return
        val current = _state.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        scope.launch {
            if (userInitiated) _state.value = UpdateState.Checking
            try {
                val latest = checker.latest()
                settings.setLastUpdateCheck(System.currentTimeMillis())
                val skipped = settings.skippedUpdateVersion()
                _state.value = when {
                    latest == null || !AppVersions.isNewer(latest.version, currentVersion) ->
                        if (userInitiated) UpdateState.UpToDate else UpdateState.Idle
                    !userInitiated && latest.version == skipped -> UpdateState.Idle
                    else -> UpdateState.Available(latest)
                }
            } catch (e: Exception) {
                _state.value = if (userInitiated) UpdateState.Failed("Couldn't check for updates. Are you online?", null) else UpdateState.Idle
            }
        }
    }

    /** Downloads the update shown as available, then opens Android's installer. */
    fun download() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Failed -> s.info ?: return
            else -> return
        }
        scope.launch {
            _state.value = UpdateState.Downloading(info, 0f)
            try {
                val file = installer.download(info) { _state.value = UpdateState.Downloading(info, it) }
                installer.verify(file)
                _state.value = UpdateState.ReadyToInstall(info, file)
                install()
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "The download failed. Try again.", info)
            }
        }
    }

    /** Opens the installer for a finished download, asking for permission first if Android needs it. */
    fun install() {
        val (info, file) = when (val s = _state.value) {
            is UpdateState.ReadyToInstall -> s.info to s.file
            is UpdateState.NeedsInstallPermission -> s.info to s.file
            else -> return
        }
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsInstallPermission(info, file)
            return
        }
        try {
            installer.installInBackground(file)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Couldn't open the installer.", info)
        }
    }

    /** The permission card's button: carry on if it's already allowed, otherwise open Android's switch for it. */
    fun allowInstalls() {
        if (installer.canInstall()) install() else installer.openInstallPermissionSettings()
    }

    /** Back in the app after the permission screen: carry on if it was allowed. */
    fun onAppResumed() {
        if (_state.value is UpdateState.NeedsInstallPermission && installer.canInstall()) install()
    }

    /** "Later": hide the card until the next check. */
    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    /** "Skip this version": don't mention it again at app start. */
    fun skip() {
        val info = (_state.value as? UpdateState.Available)?.info ?: return
        scope.launch {
            settings.skipUpdateVersion(info.version)
            _state.value = UpdateState.Idle
        }
    }

    private companion object {
        val CHECK_EVERY_MS: Long = TimeUnit.HOURS.toMillis(6)
    }
}
