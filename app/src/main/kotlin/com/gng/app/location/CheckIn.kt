package com.gng.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gng.app.container
import com.gng.app.data.LocationRepository
import com.gng.app.data.SettingsStore
import com.gng.app.data.SharingMode
import com.gng.core.CampusZone
import com.gng.core.Geo
import com.gng.core.LatLng
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Figures out where you are and tells the gang, at the precision you chose:
 * zone mode only uploads the name of the spot; live mode also uploads coordinates.
 */
class CheckIn(
    private val context: Context,
    private val settings: SettingsStore,
    private val locations: LocationRepository,
) {
    sealed interface Result {
        data class Shared(val zoneName: String?) : Result
        data object SharingOff : Result
        data object NoPermission : Result
        data object NoFix : Result
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentLatLng(): LatLng? {
        if (!hasPermission()) return null
        val loc = LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .await() ?: return null
        return LatLng(loc.latitude, loc.longitude)
    }

    suspend fun run(): Result {
        val state = settings.currentSharing()
        val mode = state.effective()
        if (mode != state.mode) settings.setSharing(mode) // live timer ran out
        if (mode == SharingMode.Off) return Result.SharingOff
        if (!hasPermission()) return Result.NoPermission
        val here = currentLatLng() ?: return Result.NoFix

        val zones = locations.zonesVisibleToMe().map {
            CampusZone(it.id, it.name, it.emoji, LatLng(it.lat, it.lng), it.radiusM)
        }
        val zone = Geo.resolveZone(here, zones)
        if (mode == SharingMode.Live) {
            locations.upload("live", zone?.id, here.lat, here.lng, state.liveUntil)
        } else {
            locations.upload("zone", zone?.id, null, null, null)
        }
        return Result.Shared(zone?.name)
    }

    suspend fun setMode(mode: SharingMode, liveFor: java.time.Duration? = null) {
        val until = liveFor?.let { Instant.now().plus(it) }
        settings.setSharing(mode, until)
        if (mode == SharingMode.Off) {
            locations.goDark()
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } else {
            schedule(context)
            run()
        }
    }

    companion object {
        private const val WORK_NAME = "gng-check-in"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CheckInWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class CheckInWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.container
        if (container.auth.awaitUserId() == null) return Result.success()
        return runCatching { container.checkIn.run() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}
