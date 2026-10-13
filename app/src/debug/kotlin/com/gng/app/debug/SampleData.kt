package com.gng.app.debug

import com.gng.app.data.DmInboxRow
import com.gng.app.data.DmMessage
import com.gng.app.data.Exam
import com.gng.app.data.Group
import com.gng.app.data.CountRow
import com.gng.app.data.IST
import com.gng.app.data.LunchLog
import com.gng.app.data.Material
import com.gng.app.data.MaterialVote
import com.gng.app.data.Message
import com.gng.app.data.Plan
import com.gng.app.data.Presence
import com.gng.app.data.Profile
import com.gng.app.data.Reaction
import com.gng.app.data.Rsvp
import com.gng.app.data.TimetableSlot
import com.gng.app.ui.group.GroupContext
import com.gng.core.ClassSlot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Believable fake data so every screen can be rendered without a backend. */
object SampleData {
    private val today = LocalDate.now(IST)
    /** A fixed "now" (12:40 IST today) so screenshots look the same whenever they're taken. */
    val now: Instant = ZonedDateTime.of(today, LocalTime.of(12, 40), IST).toInstant()
    private fun ago(minutes: Long) = now.minus(minutes, ChronoUnit.MINUTES).toString()
    private fun inDays(days: Long) = today.plusDays(days).toString()

    val me = Profile("u-krish", "Krish Bansal", null, "CSE", 3, "2005-03-14", "surviving on maggi")
    private val aarav = Profile("u-aarav", "Aarav Mehta", null, "ECE", 3, "2005-11-02", null)
    private val bhavya = Profile("u-bhavya", "Bhavya Singh", null, "CSE", 3, "2005-07-21", "in the library, do not disturb")
    private val chirag = Profile("u-chirag", "Chirag Gupta", null, "ME", 2, "2006-01-30", null)
    private val diya = Profile("u-diya", "Diya Sharma", null, "SE", 3, "2005-${"%02d-%02d".format(today.monthValue, today.dayOfMonth)}", null)
    private val esha = Profile("u-esha", "Esha Verma", null, "IT", 3, null, null)

    val people = listOf(me, aarav, bhavya, chirag, diya, esha)

    val group = Group("g1", "Hostel H4 Maggi Club", "restaurant", "K7P2QX", me.id, memberRows = listOf(CountRow(people.size)))
    val groups = listOf(
        group,
        Group("g2", "CSE B3", "school", "A9D4LM", me.id, memberRows = listOf(CountRow(34))),
        Group("g3", "Cricket Sundays", "sports_cricket", "Z3W8TN", aarav.id, memberRows = listOf(CountRow(11))),
    )
    private val roles = people.associate { it.id to if (it.id == me.id || it.id == aarav.id) "admin" else "member" }
    val ctx = GroupContext(group, me, people.associateBy { it.id }, roles)

    /** The same gang seen by a regular member, after an admin locked editing and messaging. */
    val memberCtx = GroupContext(
        group.copy(editPermission = "admins", messagePermission = "admins"),
        bhavya, people.associateBy { it.id }, roles,
    )

    val messages = listOf(
        Message(1, "g1", null, "Hostel H4 Maggi Club is live. Share the code K7P2QX to bring in the gang.", "system", null, ago(60 * 26)),
        Message(2, "g1", aarav.id, "who has the DSA PYQs", "text", null, ago(60 * 25)),
        Message(3, "g1", bhavya.id, "uploading them tonight", "text", null, ago(60 * 25 - 2)),
        Message(4, "g1", null, "Midterm: Data Structures on Fri 24 Oct", "exam", "e1", ago(190)).copy(userId = bhavya.id),
        Message(5, "g1", chirag.id, "mic mac at 1?", "text", null, ago(64)),
        Message(6, "g1", chirag.id, "I'm starving", "text", null, ago(63)),
        Message(7, "g1", me.id, "yes please, finishing the lab", "text", null, ago(55)),
        Message(8, "g1", null, "Lunch: Lunch together? at Mic Mac · Tue 1:00 pm", "plan", "p1", ago(52)).copy(userId = aarav.id),
        Message(9, "g1", diya.id, "count me in", "text", null, ago(40)),
        Message(10, "g1", diya.id, "also it's my birthday so someone is paying", "text", null, ago(39)),
        Message(11, "g1", me.id, "happy birthday!! chai on me", "text", null, ago(12)),
        Message(12, "g1", esha.id, "wait it's today?? HBD", "text", null, ago(4)),
    )
    val reactions = listOf(
        Reaction(10, aarav.id, "g1", "😂"), Reaction(10, bhavya.id, "g1", "😂"), Reaction(10, me.id, "g1", "🔥"),
        Reaction(11, diya.id, "g1", "❤️"),
    )

    private fun at(hour: Int, minute: Int = 0): String =
        ZonedDateTime.of(today, LocalTime.of(hour, minute), IST).toInstant().toString()

    val plans = listOf(
        Plan("p1", "g1", aarav.id, "lunch", "Lunch together?", "Mic Mac", at(13), null),
        Plan("p2", "g1", me.id, "snacks", "Evening snacks", "Nescafe", at(17, 30), "Diya's birthday chai"),
        Plan("p3", "g1", bhavya.id, "study", "DSA revision", "Central Library", ZonedDateTime.of(today.plusDays(1), LocalTime.of(19, 0), IST).toInstant().toString(), null),
    )
    val rsvps = listOf(
        Rsvp("p1", aarav.id, "g1", "in"), Rsvp("p1", chirag.id, "g1", "in"), Rsvp("p1", diya.id, "g1", "in"),
        Rsvp("p1", me.id, "g1", "maybe"), Rsvp("p1", esha.id, "g1", "in"), Rsvp("p1", bhavya.id, "g1", "out"),
        Rsvp("p2", me.id, "g1", "in"), Rsvp("p2", diya.id, "g1", "in"),
        Rsvp("p3", bhavya.id, "g1", "in"),
    )
    val lunch = listOf(
        LunchLog(aarav.id, today.toString(), "Rajma chawal", "Hostel mess", "set_meal"),
        LunchLog(diya.id, today.toString(), "Chole bhature", "Main canteen", "lunch_dining"),
        LunchLog(chirag.id, today.toString(), "Maggi", null, "ramen_dining"),
    )

    val exams = listOf(
        Exam("e1", "g1", "Data Structures", "midterm", inDays(2)),
        Exam("e2", "g1", "Operating Systems", "midterm", inDays(6)),
        Exam("e3", "g1", "Engineering Maths", "quiz", inDays(12)),
        Exam("e4", "g1", "DBMS", "endterm", inDays(38)),
    )
    val materials = listOf(
        Material("m1", "g1", "e1", bhavya.id, "Data Structures", "Last 5 years PYQs", null, "g1/pyq.pdf", ago(300)),
        Material("m2", "g1", "e1", aarav.id, "Data Structures", "Striver DSA playlist", "https://youtube.com/x", null, ago(900)),
        Material("m3", "g1", "e2", chirag.id, "Operating Systems", "Unit 3 handwritten notes", null, "g1/os3.jpg", ago(1500)),
        Material("m4", "g1", null, diya.id, "General", "Placement prep sheet", "https://drive.google.com/x", null, ago(2500)),
    )
    val votes = listOf(
        MaterialVote("m1", aarav.id, "g1"), MaterialVote("m1", me.id, "g1"), MaterialVote("m1", diya.id, "g1"),
        MaterialVote("m2", me.id, "g1"), MaterialVote("m3", bhavya.id, "g1"),
    )

    // Direct messages
    val inbox = listOf(
        DmInboxRow("t1", aarav.id, "are you coming to mic mac or not", aarav.id, ago(4), unread = 2),
        DmInboxRow("t2", bhavya.id, "uploading the PYQs tonight", me.id, ago(60 * 5), unread = 0),
        DmInboxRow("t3", diya.id, "thank you for the chai!!", diya.id, ago(60 * 26), unread = 0),
        DmInboxRow("t4", chirag.id, "lab file kal tak?", me.id, ago(60 * 24 * 4), unread = 0),
    )
    val inboxPeople = people.associateBy { it.id }
    val dmWithAarav = listOf(
        DmMessage(1, "t1", me.id, "did you finish the DSA assignment", ago(60 * 3)),
        DmMessage(2, "t1", aarav.id, "half of it, stuck on the last question", ago(60 * 3 - 2)),
        DmMessage(3, "t1", aarav.id, "the one with the segment tree", ago(60 * 3 - 3)),
        DmMessage(4, "t1", me.id, "same lol, let's do it together after lunch", ago(60 * 2)),
        DmMessage(5, "t1", aarav.id, "deal", ago(60 * 2 - 1)),
        DmMessage(6, "t1", aarav.id, "are you coming to mic mac or not", ago(4)),
    )

    // Spots around DTU Rohini
    private val library = 28.7511 to 77.1180
    private val micMac = 28.7502 to 77.1172
    private val hostel = 28.7487 to 77.1160
    private val campus = 28.7499 to 77.1170

    private fun spot(user: Profile, name: String, icon: String, at: Pair<Double, Double>, minutesAgo: Long) =
        Presence(user.id, "zone", name, icon, at.first, at.second, null, null, null, ago(minutesAgo))

    private fun live(user: Profile, near: String, at: Pair<Double, Double>, minutesAgo: Long) = Presence(
        user.id, "live", near, "local_library", library.first, library.second, at.first, at.second,
        now.plus(40, ChronoUnit.MINUTES).toString(), ago(minutesAgo),
    )

    val presence = listOf(
        spot(aarav, "Mic Mac", "restaurant", micMac, 6),
        spot(me, "Mic Mac", "restaurant", micMac, 3), // same spot as Aarav, so the two avatars fan out
        live(bhavya, "Central Library", 28.7513 to 77.1183, 12),
        spot(chirag, "H4 Hostel", "apartment", hostel, 190), // stale: drawn faded
        spot(diya, "DTU campus", "school", campus, 2),
    ).associateBy { it.userId }

    private fun slot(user: Profile, start: LocalTime, end: LocalTime, subject: String, room: String? = null) =
        TimetableSlot("s-${user.id}-$subject", user.id, today.dayOfWeek.value, start.toString(), end.toString(), subject, room)

    private val nowTime = LocalTime.of(12, 40)
    private val timetableSlots = listOf(
        slot(aarav, nowTime.minusMinutes(30), nowTime.plusMinutes(35), "Signals", "SPS-12"),
        slot(bhavya, nowTime.plusMinutes(70), nowTime.plusMinutes(130), "DBMS Lab", "CC-2"),
        slot(chirag, nowTime.minusHours(4), nowTime.minusHours(3), "Thermodynamics"),
        slot(me, LocalTime.of(9, 0), LocalTime.of(10, 0), "DSA", "SPS-12"),
        slot(me, LocalTime.of(11, 0), LocalTime.of(13, 0), "OOPS Lab", "CC-1"),
        slot(me, LocalTime.of(14, 30), LocalTime.of(15, 30), "Maths"),
    )
    val myClasses = timetableSlots.filter { it.userId == me.id }
    val classes: Map<String, List<ClassSlot>> = timetableSlots.groupBy({ it.userId }, { it.toCore() })

    private fun TimetableSlot.toCore() = ClassSlot(java.time.DayOfWeek.of(day), startTime, endTime, subject, room)
}
