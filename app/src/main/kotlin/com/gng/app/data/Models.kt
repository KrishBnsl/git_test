package com.gng.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

// Timestamps travel as ISO strings and are parsed on demand, which keeps the
// models independent of any particular date library.

@Serializable
data class Profile(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val branch: String? = null,
    val year: Int? = null,
    val birthday: String? = null,
    @SerialName("status_text") val statusText: String? = null,
) {
    val birthdayDate: LocalDate? get() = birthday?.let(LocalDate::parse)
}

@Serializable
data class CountRow(val count: Int)

@Serializable
data class Group(
    val id: String,
    val name: String,
    /** An icon key (see ui/icons/IconChoices.kt). The column is still called `emoji` for history. */
    @SerialName("emoji") val icon: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("photo_url") val photoUrl: String? = null,
    /** Who may change the photo, name and icon. */
    @SerialName("edit_permission") val editPermission: String = "members",
    /** Who may send chat messages. */
    @SerialName("message_permission") val messagePermission: String = "everyone",
    // Present when selected with `group_members(count)`.
    @SerialName("group_members") val memberRows: List<CountRow> = emptyList(),
) {
    val memberCount: Int get() = memberRows.firstOrNull()?.count ?: 1
}

@Serializable
data class Member(
    @SerialName("user_id") val userId: String,
    val role: String,
    val profiles: Profile,
)

@Serializable
data class Message(
    val id: Long,
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String? = null,
    val body: String,
    val kind: String = "text",
    @SerialName("ref_id") val refId: String? = null,
    @SerialName("created_at") val createdAt: String,
) {
    val createdInstant: Instant get() = parseInstant(createdAt)
}

@Serializable
data class NewMessage(
    @SerialName("group_id") val groupId: String,
    val body: String,
)

@Serializable
data class Reaction(
    @SerialName("message_id") val messageId: Long,
    @SerialName("user_id") val userId: String,
    @SerialName("group_id") val groupId: String,
    val emoji: String,
)

@Serializable
data class NewReaction(
    @SerialName("message_id") val messageId: Long,
    @SerialName("group_id") val groupId: String,
    val emoji: String,
)

enum class PlanKind(val db: String, val label: String) {
    Lunch("lunch", "Lunch"),
    Snacks("snacks", "Snacks"),
    Outing("outing", "Outing"),
    Study("study", "Study"),
    Other("other", "Other");

    companion object {
        fun of(db: String) = entries.firstOrNull { it.db == db } ?: Other
    }
}

@Serializable
data class Plan(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("created_by") val createdBy: String,
    val kind: String,
    val title: String,
    val place: String? = null,
    @SerialName("starts_at") val startsAt: String,
    val note: String? = null,
) {
    val planKind get() = PlanKind.of(kind)
    val startsInstant: Instant get() = parseInstant(startsAt)
}

@Serializable
data class NewPlan(
    @SerialName("group_id") val groupId: String,
    val kind: String,
    val title: String,
    val place: String?,
    @SerialName("starts_at") val startsAt: String,
    val note: String?,
)

@Serializable
data class Rsvp(
    @SerialName("plan_id") val planId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("group_id") val groupId: String,
    val answer: String,
)

@Serializable
data class LunchLog(
    @SerialName("user_id") val userId: String,
    val day: String,
    val dish: String,
    val place: String? = null,
    /** An icon key (see ui/icons/IconChoices.kt). */
    @SerialName("emoji") val icon: String = "lunch_dining",
)

@Serializable
data class NewLunchLog(
    val day: String,
    val dish: String,
    val place: String?,
    @SerialName("emoji") val icon: String,
)

@Serializable
data class TimetableSlot(
    val id: String,
    @SerialName("user_id") val userId: String,
    val day: Int,
    val starts: String,
    val ends: String,
    val subject: String,
    val room: String? = null,
) {
    val startTime: LocalTime get() = LocalTime.parse(starts)
    val endTime: LocalTime get() = LocalTime.parse(ends)
}

@Serializable
data class NewTimetableSlot(
    val day: Int,
    val starts: String,
    val ends: String,
    val subject: String,
    val room: String?,
)

@Serializable
data class Zone(
    val id: String,
    @SerialName("group_id") val groupId: String? = null,
    val name: String,
    @SerialName("emoji") val icon: String,
    val lat: Double,
    val lng: Double,
    @SerialName("radius_m") val radiusM: Int,
)

@Serializable
data class NewZone(
    @SerialName("group_id") val groupId: String,
    val name: String,
    @SerialName("emoji") val icon: String,
    val lat: Double,
    val lng: Double,
    @SerialName("radius_m") val radiusM: Int,
)

@Serializable
data class LocationRow(
    val mode: String,
    @SerialName("zone_id") val zoneId: String?,
    val lat: Double?,
    val lng: Double?,
    @SerialName("live_until") val liveUntil: String?,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class Presence(
    @SerialName("user_id") val userId: String,
    val mode: String,
    @SerialName("zone_name") val zoneName: String? = null,
    @SerialName("zone_emoji") val zoneIcon: String? = null,
    @SerialName("zone_lat") val zoneLat: Double? = null,
    @SerialName("zone_lng") val zoneLng: Double? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("live_until") val liveUntil: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class DmThread(
    val id: String,
    @SerialName("user_a") val userA: String,
    @SerialName("user_b") val userB: String,
)

@Serializable
data class DmMessage(
    val id: Long,
    @SerialName("thread_id") val threadId: String,
    @SerialName("sender_id") val senderId: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class NewDmMessage(
    @SerialName("thread_id") val threadId: String,
    val body: String,
)

@Serializable
data class DmReaction(
    @SerialName("message_id") val messageId: Long,
    @SerialName("thread_id") val threadId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String,
)

@Serializable
data class NewDmReaction(
    @SerialName("message_id") val messageId: Long,
    @SerialName("thread_id") val threadId: String,
    val emoji: String,
)

/** One line of the inbox: the other person, the last message, and how many are unread. */
@Serializable
data class DmInboxRow(
    @SerialName("thread_id") val threadId: String,
    @SerialName("other_id") val otherId: String,
    @SerialName("last_body") val lastBody: String? = null,
    @SerialName("last_sender") val lastSender: String? = null,
    @SerialName("last_at") val lastAt: String,
    val unread: Int = 0,
) {
    val lastInstant: java.time.Instant get() = parseInstant(lastAt)
}

object GroupPermission {
    const val MEMBERS = "members"
    const val ADMINS = "admins"
    const val EVERYONE = "everyone"
}

object GroupRole {
    const val ADMIN = "admin"
    const val MEMBER = "member"
}

enum class ExamKind(val db: String, val label: String) {
    Midterm("midterm", "Midsem"),
    Endterm("endterm", "Endsem"),
    Quiz("quiz", "Quiz"),
    Lab("lab", "Lab"),
    Other("other", "Other");

    companion object {
        fun of(db: String) = entries.firstOrNull { it.db == db } ?: Other
    }
}

@Serializable
data class Exam(
    val id: String,
    @SerialName("group_id") val groupId: String,
    val subject: String,
    val kind: String,
    @SerialName("exam_date") val examDate: String,
) {
    val date: LocalDate get() = LocalDate.parse(examDate)
}

@Serializable
data class NewExam(
    @SerialName("group_id") val groupId: String,
    val subject: String,
    val kind: String,
    @SerialName("exam_date") val examDate: String,
)

@Serializable
data class Material(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("exam_id") val examId: String? = null,
    @SerialName("uploaded_by") val uploadedBy: String,
    val subject: String,
    val title: String,
    @SerialName("link_url") val linkUrl: String? = null,
    @SerialName("storage_path") val storagePath: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class NewMaterial(
    @SerialName("group_id") val groupId: String,
    @SerialName("exam_id") val examId: String?,
    val subject: String,
    val title: String,
    @SerialName("link_url") val linkUrl: String?,
    @SerialName("storage_path") val storagePath: String?,
)

@Serializable
data class MaterialVote(
    @SerialName("material_id") val materialId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("group_id") val groupId: String,
)

fun parseInstant(iso: String): Instant = OffsetDateTime.parse(iso).toInstant()
