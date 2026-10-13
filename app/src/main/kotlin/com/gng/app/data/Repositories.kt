package com.gng.app.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Duration.Companion.hours

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

class AuthRepository(private val client: SupabaseClient) {
    val sessionStatus get() = client.auth.sessionStatus
    val userId: String? get() = client.auth.currentUserOrNull()?.id

    /** Waits for the stored session to load (it is read asynchronously at startup). */
    suspend fun awaitUserId(): String? {
        client.auth.sessionStatus.first { it !is SessionStatus.Initializing }
        return userId
    }

    suspend fun signUp(email: String, password: String) {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() = client.auth.signOut()
}

class ProfileRepository(private val client: SupabaseClient) {
    suspend fun get(id: String): Profile? =
        client.from("profiles").select { filter { eq("id", id) } }.decodeSingleOrNull()

    suspend fun save(profile: Profile) {
        // Built by hand so cleared fields are sent as explicit nulls and actually clear the column.
        val row = buildJsonObject {
            put("id", profile.id)
            put("display_name", profile.displayName)
            put("avatar_url", profile.avatarUrl)
            put("branch", profile.branch)
            put("year", profile.year)
            put("birthday", profile.birthday)
            put("status_text", profile.statusText)
        }
        client.from("profiles").upsert(row)
    }

    suspend fun getMany(ids: List<String>): List<Profile> =
        if (ids.isEmpty()) emptyList()
        else client.from("profiles").select { filter { isIn("id", ids) } }.decodeList()

    /** Everyone you share a gang with (row-level security hides everyone else), minus yourself. */
    suspend fun gangMates(myId: String): List<Profile> =
        client.from("profiles").select().decodeList<Profile>().filter { it.id != myId }

    /** Uploads a square JPEG and returns its public URL. Each upload gets a fresh path, so caches never go stale. */
    suspend fun uploadAvatar(userId: String, jpeg: ByteArray): String {
        val path = "$userId/${UUID.randomUUID()}.jpg"
        val bucket = client.storage.from("avatars")
        bucket.upload(path, jpeg)
        return bucket.publicUrl(path)
    }
}

class GroupRepository(private val client: SupabaseClient) {
    /** Row-level security only lets you see your own gangs, so no filter is needed. */
    suspend fun myGroups(): List<Group> =
        client.from("groups").select(Columns.raw("*, group_members(count)")).decodeList()

    suspend fun get(id: String): Group =
        client.from("groups").select(Columns.raw("*, group_members(count)")) { filter { eq("id", id) } }.decodeSingle()

    suspend fun create(name: String, icon: String): Group =
        client.postgrest.rpc("create_group", buildJsonObject {
            put("p_name", name)
            put("p_emoji", icon)
        }).decodeAs()

    suspend fun join(code: String): Group =
        client.postgrest.rpc("join_group", buildJsonObject { put("p_code", code) }).decodeAs()

    suspend fun members(groupId: String): List<Member> =
        client.from("group_members")
            .select(Columns.raw("user_id, role, profiles(*)")) { filter { eq("group_id", groupId) } }
            .decodeList()

    /** Photo, name and icon. Allowed for admins, or for every member when the gang's edit permission is open. */
    suspend fun updateInfo(
        groupId: String,
        name: String? = null,
        icon: String? = null,
        photoUrl: String? = null,
        clearPhoto: Boolean = false,
    ): Group =
        client.postgrest.rpc("update_group_info", buildJsonObject {
            put("p_group", groupId)
            if (name != null) put("p_name", name)
            if (icon != null) put("p_icon", icon)
            if (photoUrl != null) put("p_photo_url", photoUrl)
            if (clearPhoto) put("p_clear_photo", true)
        }).decodeAs()

    /** Admins only: who can edit the gang, and who can send messages. */
    suspend fun updatePermissions(groupId: String, edit: String? = null, message: String? = null): Group =
        client.postgrest.rpc("update_group_permissions", buildJsonObject {
            put("p_group", groupId)
            if (edit != null) put("p_edit", edit)
            if (message != null) put("p_message", message)
        }).decodeAs()

    suspend fun setRole(groupId: String, userId: String, role: String) {
        client.postgrest.rpc("set_member_role", buildJsonObject {
            put("p_group", groupId)
            put("p_user", userId)
            put("p_role", role)
        })
    }

    /** Uploads a square JPEG to the group's folder and returns its public URL. */
    suspend fun uploadPhoto(groupId: String, jpeg: ByteArray): String {
        val path = "$groupId/${UUID.randomUUID()}.jpg"
        val bucket = client.storage.from("group-photos")
        bucket.upload(path, jpeg)
        return bucket.publicUrl(path)
    }

    /** Fires when this gang's row changes (photo, name, permissions). */
    fun changes(groupId: String): Flow<Unit> = rowChanges(client, "groups", "id", groupId)

    suspend fun leave(groupId: String, userId: String) {
        client.from("group_members").delete {
            filter {
                eq("group_id", groupId)
                eq("user_id", userId)
            }
        }
    }

}

class ChatRepository(private val client: SupabaseClient) {
    suspend fun recent(groupId: String, limit: Long = 100): List<Message> =
        client.from("messages").select {
            filter { eq("group_id", groupId) }
            order("created_at", Order.DESCENDING)
            limit(limit)
        }.decodeList<Message>().reversed()

    suspend fun send(groupId: String, body: String) {
        client.from("messages").insert(NewMessage(groupId, body))
    }

    suspend fun reactions(groupId: String): List<Reaction> =
        client.from("reactions").select { filter { eq("group_id", groupId) } }.decodeList()

    suspend fun toggleReaction(message: Message, emoji: String, userId: String, alreadyReacted: Boolean) {
        if (alreadyReacted) {
            client.from("reactions").delete {
                filter {
                    eq("message_id", message.id)
                    eq("user_id", userId)
                    eq("emoji", emoji)
                }
            }
        } else {
            client.from("reactions").insert(NewReaction(message.id, message.groupId, emoji))
        }
    }

    /** New messages as they arrive. Collect from a scope that lives as long as the screen. */
    fun incoming(groupId: String): Flow<Message> = tableChanges(client, "messages", groupId)
        .map { it.decodeRecord<Message>() }

    /** Fires whenever a reaction in this group is added or removed. */
    fun reactionChanges(groupId: String): Flow<Unit> = anyChange(client, "reactions", groupId)
}

class DmRepository(private val client: SupabaseClient) {
    /** Finds or creates your conversation with someone you share a gang with. */
    suspend fun open(otherId: String): DmThread =
        client.postgrest.rpc("open_dm", buildJsonObject { put("p_other", otherId) }).decodeAs()

    suspend fun inbox(): List<DmInboxRow> = client.postgrest.rpc("dm_inbox").decodeList()

    suspend fun messages(threadId: String, limit: Long = 100): List<DmMessage> =
        client.from("dm_messages").select {
            filter { eq("thread_id", threadId) }
            order("id", Order.DESCENDING)
            limit(limit)
        }.decodeList<DmMessage>().reversed()

    suspend fun send(threadId: String, body: String) {
        client.from("dm_messages").insert(NewDmMessage(threadId, body))
    }

    suspend fun reactions(threadId: String): List<DmReaction> =
        client.from("dm_reactions").select { filter { eq("thread_id", threadId) } }.decodeList()

    suspend fun toggleReaction(message: DmMessage, emoji: String, userId: String, alreadyReacted: Boolean) {
        if (alreadyReacted) {
            client.from("dm_reactions").delete {
                filter {
                    eq("message_id", message.id)
                    eq("user_id", userId)
                    eq("emoji", emoji)
                }
            }
        } else {
            client.from("dm_reactions").insert(NewDmReaction(message.id, message.threadId, emoji))
        }
    }

    suspend fun markRead(threadId: String) {
        client.postgrest.rpc("mark_dm_read", buildJsonObject { put("p_thread", threadId) })
    }

    /** New messages in one conversation as they arrive. */
    fun incoming(threadId: String): Flow<DmMessage> =
        inserts(client, "dm_messages", "thread_id", threadId).map { it.decodeRecord<DmMessage>() }

    fun reactionChanges(threadId: String): Flow<Unit> = rowChanges(client, "dm_reactions", "thread_id", threadId)

    /** Any new message in any of your conversations (row-level security limits what you hear about). */
    fun anyIncoming(): Flow<Unit> = inserts(client, "dm_messages", null, null).map { }
}

class PlansRepository(private val client: SupabaseClient) {
    suspend fun upcoming(groupId: String): List<Plan> =
        client.from("plans").select {
            filter {
                eq("group_id", groupId)
                // keep plans visible for a couple of hours after they start
                gte("starts_at", Instant.now().minusSeconds(3 * 3600).toString())
            }
            order("starts_at", Order.ASCENDING)
        }.decodeList()

    suspend fun rsvps(groupId: String): List<Rsvp> =
        client.from("plan_rsvps").select { filter { eq("group_id", groupId) } }.decodeList()

    suspend fun create(plan: NewPlan) {
        client.from("plans").insert(plan)
    }

    suspend fun delete(planId: String) {
        client.from("plans").delete { filter { eq("id", planId) } }
    }

    suspend fun rsvp(plan: Plan, answer: String) {
        client.from("plan_rsvps").upsert(Rsvp(plan.id, null, plan.groupId, answer)) {
            onConflict = "plan_id,user_id"
        }
    }

    suspend fun lunchToday(): List<LunchLog> =
        client.from("lunch_logs").select { filter { eq("day", LocalDate.now(IST).toString()) } }.decodeList()

    suspend fun logLunch(dish: String, place: String?, icon: String) {
        client.from("lunch_logs").upsert(NewLunchLog(LocalDate.now(IST).toString(), dish, place, icon)) {
            onConflict = "user_id,day"
        }
    }

    fun changes(groupId: String): Flow<Unit> = anyChange(client, "plans", groupId, "plan_rsvps")
}

class TimetableRepository(private val client: SupabaseClient) {
    suspend fun forUsers(userIds: List<String>): List<TimetableSlot> =
        if (userIds.isEmpty()) emptyList()
        else client.from("timetable_slots").select { filter { isIn("user_id", userIds) } }.decodeList()

    suspend fun add(slot: NewTimetableSlot) {
        client.from("timetable_slots").insert(slot)
    }

    suspend fun remove(id: String) {
        client.from("timetable_slots").delete { filter { eq("id", id) } }
    }
}

class LocationRepository(private val client: SupabaseClient) {
    suspend fun zonesVisibleToMe(): List<Zone> = client.from("zones").select().decodeList()

    suspend fun pinZone(zone: NewZone) {
        client.from("zones").insert(zone)
    }

    suspend fun presence(groupId: String): List<Presence> =
        client.postgrest.rpc("group_presence", buildJsonObject { put("gid", groupId) }).decodeList()

    suspend fun upload(mode: String, zoneId: String?, lat: Double?, lng: Double?, liveUntil: Instant?) {
        // Built by hand so nulls are sent explicitly: dropping from live to zone must wipe lat/lng.
        val row = buildJsonObject {
            put("mode", mode)
            put("zone_id", zoneId)
            put("lat", lat)
            put("lng", lng)
            put("live_until", liveUntil?.toString())
            put("updated_at", Instant.now().toString())
        }
        client.from("locations").upsert(row) { onConflict = "user_id" }
    }

    /** Clears any stored coordinates; group-mates see you as off the map. */
    suspend fun goDark() = upload("off", null, null, null, null)
}

class StudyRepository(private val client: SupabaseClient) {
    suspend fun exams(groupId: String): List<Exam> =
        client.from("exams").select {
            filter {
                eq("group_id", groupId)
                gte("exam_date", LocalDate.now(IST).minusDays(1).toString())
            }
            order("exam_date", Order.ASCENDING)
        }.decodeList()

    suspend fun addExam(exam: NewExam) {
        client.from("exams").insert(exam)
    }

    suspend fun materials(groupId: String): List<Material> =
        client.from("materials").select {
            filter { eq("group_id", groupId) }
            order("created_at", Order.DESCENDING)
        }.decodeList()

    suspend fun votes(groupId: String): List<MaterialVote> =
        client.from("material_votes").select { filter { eq("group_id", groupId) } }.decodeList()

    suspend fun toggleVote(material: Material, userId: String, voted: Boolean) {
        if (voted) {
            client.from("material_votes").delete {
                filter {
                    eq("material_id", material.id)
                    eq("user_id", userId)
                }
            }
        } else {
            client.from("material_votes").insert(MaterialVote(material.id, null, material.groupId))
        }
    }

    suspend fun addLink(groupId: String, examId: String?, subject: String, title: String, url: String) {
        client.from("materials").insert(NewMaterial(groupId, examId, subject, title, url, null))
    }

    suspend fun uploadFile(
        groupId: String, examId: String?, subject: String, title: String,
        fileName: String, bytes: ByteArray,
    ) {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val path = "$groupId/${UUID.randomUUID()}-$safeName"
        client.storage.from("materials").upload(path, bytes)
        client.from("materials").insert(NewMaterial(groupId, examId, subject, title, null, path))
    }

    suspend fun downloadUrl(material: Material): String =
        material.linkUrl ?: client.storage.from("materials").createSignedUrl(material.storagePath!!, 1.hours)
}

// --- realtime helpers -------------------------------------------------------

private fun tableChanges(client: SupabaseClient, table: String, groupId: String): Flow<PostgresAction.Insert> = flow {
    val channel = client.channel("$table-$groupId-${UUID.randomUUID()}")
    val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
        this.table = table
        filter("group_id", FilterOperator.EQ, groupId)
    }
    channel.subscribe()
    try {
        emitAll(inserts)
    } finally {
        withContext(NonCancellable) { client.realtime.removeChannel(channel) }
    }
}

private fun anyChange(client: SupabaseClient, table: String, groupId: String, vararg moreTables: String): Flow<Unit> = flow {
    val channel = client.channel("$table-$groupId-${UUID.randomUUID()}")
    val changes = (listOf(table) + moreTables).map { t ->
        channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            this.table = t
            filter("group_id", FilterOperator.EQ, groupId)
        }
    }
    channel.subscribe()
    try {
        emitAll(changes.merge().map { })
    } finally {
        withContext(NonCancellable) { client.realtime.removeChannel(channel) }
    }
}

private fun rowChanges(client: SupabaseClient, table: String, column: String, value: String): Flow<Unit> = flow {
    val channel = client.channel("$table-$column-$value-${UUID.randomUUID()}")
    val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
        this.table = table
        filter(column, FilterOperator.EQ, value)
    }
    channel.subscribe()
    try {
        emitAll(changes.map { })
    } finally {
        withContext(NonCancellable) { client.realtime.removeChannel(channel) }
    }
}

/** Inserts into a table, optionally only where [column] equals [value]. */
private fun inserts(client: SupabaseClient, table: String, column: String?, value: String?): Flow<PostgresAction.Insert> = flow {
    val channel = client.channel("$table-inserts-${value ?: "all"}-${UUID.randomUUID()}")
    val changes = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
        this.table = table
        if (column != null && value != null) filter(column, FilterOperator.EQ, value)
    }
    channel.subscribe()
    try {
        emitAll(changes)
    } finally {
        withContext(NonCancellable) { client.realtime.removeChannel(channel) }
    }
}
