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
    val emoji: String = "😎",
    val branch: String? = null,
    val year: Int? = null,
    val birthday: String? = null,
    @SerialName("status_text") val statusText: String? = null,
) {
    val birthdayDate: LocalDate? get() = birthday?.let(LocalDate::parse)
}

@Serializable
data class Group(
    val id: String,
    val name: String,
    val emoji: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("created_by") val createdBy: String,
)

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

enum class PlanKind(val db: String, val emoji: String, val label: String) {
    Lunch("lunch", "🍛", "Lunch"),
    Snacks("snacks", "🍟", "Snacks"),
    Outing("outing", "🚗", "Outing"),
    Study("study", "📚", "Study sesh"),
    Other("other", "✨", "Other");

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
    val emoji: String = "🍛",
)

@Serializable
data class NewLunchLog(
    val day: String,
    val dish: String,
    val place: String?,
    val emoji: String,
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
    val emoji: String,
    val lat: Double,
    val lng: Double,
    @SerialName("radius_m") val radiusM: Int,
)

@Serializable
data class NewZone(
    @SerialName("group_id") val groupId: String,
    val name: String,
    val emoji: String,
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
    @SerialName("zone_emoji") val zoneEmoji: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("live_until") val liveUntil: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

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
