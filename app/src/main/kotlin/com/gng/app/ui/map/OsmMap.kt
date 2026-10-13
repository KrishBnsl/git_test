package com.gng.app.ui.map

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gng.core.LatLng
import com.gng.core.MapBounds
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import java.io.File

/** DTU, Rohini: where the map starts when nobody is sharing. */
val CAMPUS_CENTER = LatLng(28.7499, 77.1170)
const val CAMPUS_ZOOM = 16.0

/**
 * Lets Compose draw on top of an osmdroid map: avatars are ordinary composables positioned
 * from [project], and [tick] changes whenever the map moves so they follow it.
 */
@Stable
class GngMapState {
    internal var view: MapView? = null
    var tick by mutableIntStateOf(0)
        internal set

    /** Screen position (px, inside the map view) of a coordinate, or null before the map exists. */
    fun project(point: LatLng): Offset? {
        val v = view ?: return null
        tick // read, so callers re-run when the map moves
        val p = v.projection.toPixels(GeoPoint(point.lat, point.lng), null)
        return Offset(p.x.toFloat(), p.y.toFloat())
    }

    fun fit(bounds: MapBounds?, paddingPx: Int) {
        val v = view ?: return
        if (bounds == null) {
            v.controller.animateTo(GeoPoint(CAMPUS_CENTER.lat, CAMPUS_CENTER.lng), CAMPUS_ZOOM, 600L)
            return
        }
        val tiny = (bounds.north - bounds.south) < 0.0001 && (bounds.east - bounds.west) < 0.0001
        if (tiny) {
            v.controller.animateTo(GeoPoint((bounds.north + bounds.south) / 2, (bounds.east + bounds.west) / 2), 18.0, 600L)
        } else {
            v.zoomToBoundingBox(BoundingBox(bounds.north, bounds.east, bounds.south, bounds.west), true, paddingPx)
        }
    }

    fun animateTo(point: LatLng, zoom: Double = 18.0) {
        view?.controller?.animateTo(GeoPoint(point.lat, point.lng), zoom, 600L)
    }
}

@Composable
fun rememberGngMapState(): GngMapState = remember { GngMapState() }

private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.userAgentValue = context.packageName // the tile servers ask apps to identify themselves
    // Keep the tile cache in the app's private cache: no storage permission needed.
    val base = File(context.cacheDir, "osmdroid")
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles")
    config.tileFileSystemCacheMaxBytes = 80L * 1024 * 1024
    config.tileFileSystemCacheTrimBytes = 60L * 1024 * 1024
}

/**
 * OpenStreetMap's standard tiles need no account or API key. Their servers are run by volunteers with a
 * fair-use policy (https://operations.osmfoundation.org/policies/tiles/): fine for a friend group, but if
 * gng grows, point this at a keyed provider (MapTiler, Stadia...) or host tiles yourself.
 */
const val MAP_ATTRIBUTION = "© OpenStreetMap contributors"

/** OpenStreetMap has no dark style, so dark mode inverts the tiles and rotates hues back (the usual CSS trick). */
private val DARK_TILES: ColorFilter = run {
    val invert = ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ))
    val hue180 = ColorMatrix(floatArrayOf(
        -0.574f, 1.430f, 0.144f, 0f, 0f,
        0.426f, 0.430f, 0.144f, 0f, 0f,
        0.426f, 1.430f, -0.856f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
    val dim = ColorMatrix().apply { setScale(0.86f, 0.86f, 0.92f, 1f) }
    ColorMatrixColorFilter(ColorMatrix().apply {
        postConcat(invert)
        postConcat(hue180)
        postConcat(dim)
    })
}

@Composable
fun OsmMap(
    state: GngMapState,
    dark: Boolean,
    modifier: Modifier = Modifier,
    /** Shown while tiles load, so the map never flashes white in dark mode. */
    background: Color = Color.Gray,
    onTap: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTileSource(TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true // 256 px tiles scaled up so labels stay readable on phones
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(CAMPUS_ZOOM)
            controller.setCenter(GeoPoint(CAMPUS_CENTER.lat, CAMPUS_CENTER.lng))
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean { state.tick++; return false }
                override fun onZoom(event: ZoomEvent?): Boolean { state.tick++; return false }
            })
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> state.tick++ }
            overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean { onTap(); return false }
                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                }),
            )
            state.view = this
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
            state.view = null
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.overlayManager.tilesOverlay.apply {
                setColorFilter(if (dark) DARK_TILES else null)
                loadingBackgroundColor = background.toArgb()
                loadingLineColor = background.toArgb()
            }
            view.invalidate()
        },
    )
}
