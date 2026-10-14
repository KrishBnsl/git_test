package com.gng.app

import android.content.Context
import com.gng.app.call.CallManager
import com.gng.app.crypto.E2ee
import com.gng.app.data.AuthRepository
import com.gng.app.data.ChatCache
import com.gng.app.data.ChatRepository
import com.gng.app.data.GroupRepository
import com.gng.app.data.LocationRepository
import com.gng.app.data.MediaRepository
import com.gng.app.data.PlansRepository
import com.gng.app.data.ProfileRepository
import com.gng.app.data.SettingsStore
import com.gng.app.data.StickerStore
import com.gng.app.data.StudyRepository
import com.gng.app.data.Supabase
import com.gng.app.data.TimetableRepository
import com.gng.app.location.CheckIn
import com.gng.app.notify.Inbox
import com.gng.app.notify.Notifier
import com.gng.app.notify.PushRegistry
import com.gng.app.update.UpdateManager

/** Hand-rolled dependency container; small enough that a DI framework would be overkill. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = SettingsStore(context)
    private val client by lazy { Supabase.create() }

    /** True while any gng screen is visible. */
    @Volatile var foreground = false

    val auth by lazy { AuthRepository(client) }
    val profiles by lazy { ProfileRepository(client) }
    val groups by lazy { GroupRepository(client) }
    val cache by lazy { ChatCache(context) }
    val e2ee by lazy { E2ee(context, client) }
    val chat by lazy { ChatRepository(client, cache, e2ee) }
    val media by lazy { MediaRepository(context, client, cache) }
    val stickers by lazy { StickerStore(context, media) }
    val plans by lazy { PlansRepository(client) }
    val timetable by lazy { TimetableRepository(client) }
    val locations by lazy { LocationRepository(client) }
    val study by lazy { StudyRepository(client) }
    val checkIn by lazy { CheckIn(context, settings, locations) }
    val notifier by lazy { Notifier(context) }
    val push by lazy { PushRegistry(context, client) }
    val inbox by lazy { Inbox(this) }
    val calls by lazy { CallManager(context, client, { auth.userId }, notifier) }
    val updates by lazy { UpdateManager(context.applicationContext, settings) }
}
