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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
        client.from("profiles").upsert(profile)
    }
}

class GroupRepository(private val client: SupabaseClient) {
    suspend fun myGroups(userId: String): List<Group> {
        val ids = client.from("group_members")
            .select(Columns.list("group_id")) { filter { eq("user_id", userId) } }
            .decodeList<GroupIdRow>()
            .map { it.groupId }
        if (ids.isEmpty()) return emptyList()
        return client.from("groups").select { filter { isIn("id", ids) } }.decodeList()
    }

    suspend fun get(id: String): Group =
        client.from("groups").select { filter { eq("id", id) } }.decodeSingle()

    suspend fun create(name: String, emoji: String): Group =
        client.postgrest.rpc("create_group", buildJsonObject {
            put("p_name", name)
            put("p_emoji", emoji)
        }).decodeAs()

    suspend fun join(code: String): Group =
        client.postgrest.rpc("join_group", buildJsonObject { put("p_code", code) }).decodeAs()

    suspend fun members(groupId: String): List<Member> =
        client.from("group_members")
            .select(Columns.raw("user_id, role, profiles(*)")) { filter { eq("group_id", groupId) } }
            .decodeList()

    suspend fun leave(groupId: String, userId: String) {
        client.from("group_members").delete {
            filter {
                eq("group_id", groupId)
                eq("user_id", userId)
            }
        }
    }

    @Serializable
    private data class GroupIdRow(@SerialName("group_id") val groupId: String)
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

    suspend fun logLunch(dish: String, place: String?, emoji: String) {
        client.from("lunch_logs").upsert(NewLunchLog(LocalDate.now(IST).toString(), dish, place, emoji)) {
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
