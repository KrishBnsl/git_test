package com.gng.app

import android.content.Context
import com.gng.app.data.AuthRepository
import com.gng.app.data.ChatRepository
import com.gng.app.data.GroupRepository
import com.gng.app.data.LocationRepository
import com.gng.app.data.PlansRepository
import com.gng.app.data.ProfileRepository
import com.gng.app.data.SettingsStore
import com.gng.app.data.StudyRepository
import com.gng.app.data.Supabase
import com.gng.app.data.TimetableRepository
import com.gng.app.location.CheckIn

/** Hand-rolled dependency container; small enough that a DI framework would be overkill. */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    private val client by lazy { Supabase.create() }

    val auth by lazy { AuthRepository(client) }
    val profiles by lazy { ProfileRepository(client) }
    val groups by lazy { GroupRepository(client) }
    val chat by lazy { ChatRepository(client) }
    val plans by lazy { PlansRepository(client) }
    val timetable by lazy { TimetableRepository(client) }
    val locations by lazy { LocationRepository(client) }
    val study by lazy { StudyRepository(client) }
    val checkIn by lazy { CheckIn(context, settings, locations) }
}
