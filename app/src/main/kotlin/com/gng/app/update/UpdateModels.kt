package com.gng.app.update

import java.io.File

/** A newer version of the app, as announced by a GitHub release. */
data class UpdateInfo(
    val version: String,
    /** Plain bullets for "What's new", already cleaned up. */
    val notes: List<String>,
    val apkUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    /** Nothing to show. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Only shown after the person asked to check. */
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** [progress] is 0..1, or negative when the server didn't say how big the file is. */
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    /** Android needs the person to allow gng to install apps first. */
    data class NeedsInstallPermission(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}
