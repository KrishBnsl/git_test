package com.gng.app

import com.gng.app.update.AutoUpdate
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

class GngApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Stickers are often animated WebP.
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory()) }
                .build()
        }
        // Track whether anything is on screen, so we don't notify about the chat you're reading.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++
                container.foreground = true
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                container.foreground = started > 0
                // Just left the app: a downloaded update can install now without interrupting anything.
                if (started == 0) AutoUpdate.onAppBackgrounded(this@GngApp)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            // When any screen comes back to the front (for example from Android's install-permission page),
            // let a waiting update carry on.
            override fun onActivityResumed(activity: Activity) = container.updates.onAppResumed()
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

val Context.container: AppContainer get() = (applicationContext as GngApp).container
