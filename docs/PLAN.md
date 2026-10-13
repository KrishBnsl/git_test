# gng: product & tech plan

> A private hangout app for college friend groups (built for our DTU gang).
> It's like Discord, but it knows where lunch is, who's in class, whose birthday is next, and where the PYQs are.

## 1. What it is

Discord is built for big communities. gng is built for one friend group, or a few (hostel gang, branch friends, project team).
Every screen answers a question we already ask in WhatsApp every day:

| Question we keep asking | gng feature |
|---|---|
| "Where are you?" | **Squad**: everyone's current spot (Library, Mic Mac, hostel…) plus optional live location for an hour |
| "Are you in class?" / "When are you free?" | **Classes**: each person adds their timetable; Squad shows "in DSA till 11am" or "free till 2pm" |
| "Lunch?" | **Plans**: one-tap lunch request with place and time, and RSVPs (in / maybe / out) |
| "When can we *all* go?" | **Lunch window finder**: merges everyone's timetables into the slots when the whole gang is free |
| "What are you eating?" | **Lunch board**: a daily "what I ate" feed |
| "Snacks at 5?" / "Outing Saturday?" | **Plans** for snacks, outings, study sessions, and anything else |
| "Whose birthday is next?" | **Birthdays**: countdown list, plus a confetti banner on the day |
| "When's the midsem?" / "Anyone has notes?" | **Study**: exam radar with a panic meter (🔥🔥🔥), and **the stack** of shared notes/PYQs/links with 🙏 upvotes |
| "Did you see the chat?" | **Hangout**: realtime group chat with emoji reactions; plans, exams and new notes post themselves into it |

## 2. Decisions

| Area | Choice | Why |
|---|---|---|
| Platform | **Android first, native Kotlin + Jetpack Compose** | Your call. Best Android feel; iOS comes later (see §7) |
| Backend | **Supabase** (Postgres + Auth + Realtime + Storage) | Free tier is plenty for a friend group, realtime chat out of the box, and row-level security keeps each gang's data private |
| Groups | **Multiple groups**, joined by a 6-character invite code | One person can be in "Hostel H4" and "CSE B3" at once |
| Location | **Both modes**: spot-only by default, plus "Live for 1 hour" | Spot mode never uploads coordinates, only the spot name |
| Theme | **Playful light + neon dark, with a toggle** | Settings → Theme (System / Playful / Neon) |
| Name | **gng** | |

## 3. Privacy model (location)

Location is the one feature that can feel creepy, so the rules are strict:

* **Off by default.** Ghost mode (👻) means nothing is stored.
* **Spot only** (default once enabled): the phone works out which pinned spot you're in *on the device* and uploads only the spot ID. Coordinates never leave the phone.
* **Live (1 hour)**: coordinates are uploaded and visible to group-mates **only until the timer ends**. After that the server hides them even if the phone didn't check in again (`group_presence()` masks expired coordinates), and the app drops back to spot-only.
* Other people's `locations` rows can't be read directly. The only way in is `group_presence(group)`, which returns people in *that* group only.
* Going ghost or logging out wipes the stored row.

Spots ("zones") are pinned by the gang itself: stand at the Library, tap **📌 Pin this spot**, name it. A single "DTU campus" zone is seeded so check-ins say *something* before anyone pins finer spots.

## 4. Architecture

```
┌────────────── Android app (Kotlin, Compose) ──────────────┐
│ ui/        screens + ViewModels (one per tab)             │
│ data/      repositories over supabase-kt                  │
│ location/  CheckIn + 15-min WorkManager job               │
│ core/      pure Kotlin: zones, timetable math, birthdays, │
│            exam countdowns (unit-tested, no Android deps) │
└──────────────┬────────────────────────────────────────────┘
               │ HTTPS (PostgREST) + WebSocket (Realtime)
┌──────────────▼──────────── Supabase ──────────────────────┐
│ Auth (email + password)                                   │
│ Postgres + row-level security (is_member / shares_group)  │
│ RPCs: create_group, join_group, group_presence            │
│ Triggers: plans/exams/materials → chat messages; auto-RSVP│
│ Realtime: messages, reactions, plans, rsvps, materials    │
│ Storage: private `materials` bucket, <group_id>/file      │
└───────────────────────────────────────────────────────────┘
```

* **Schema**: `supabase/migrations/0001_init.sql` (tables, policies, RPCs, triggers, bucket, realtime).
* **Modules**: `:core` is plain Kotlin/JVM so the fiddly logic (zone matching, common free windows, leap-day birthdays) is unit-tested without an emulator. `:app` is the Android app.
* **No DI framework**: `AppContainer` wires the repositories by hand. That's plenty at this size.

### Data model

| Table | Purpose |
|---|---|
| `profiles` | name, emoji avatar, branch, year, birthday, status |
| `groups`, `group_members` | gangs and who's in them (admin/member) |
| `messages`, `reactions` | chat; non-text kinds are posted by triggers |
| `plans`, `plan_rsvps` | lunch / snacks / outing / study / other, with in-maybe-out |
| `lunch_logs` | one "what I ate" per person per day |
| `timetable_slots` | weekly classes (ISO weekday, start, end, subject, room) |
| `zones`, `locations` | pinned spots; each user's current sharing state |
| `exams`, `materials`, `material_votes` | exam radar and the notes stack |

## 5. MVP scope (this commit)

- [x] Sign up / log in (email + password)
- [x] Profile: emoji avatar, name, branch, year, birthday, status
- [x] Create a gang / join with a code / share invite / leave
- [x] **Hangout**: realtime chat, long-press reactions, auto-posted plan/exam/notes events
- [x] **Plans**: lunch, snacks, outing, study, and other plans; RSVP; lunch window finder; lunch board
- [x] **Squad**: ghost / spot / live-1h sharing, check in, pin a spot, everyone's spot and class status, live distance + "Find" in Maps, birthdays + confetti
- [x] **Study**: exam radar with countdown & panic meter, notes stack (links or file uploads ≤ 25 MB), 🙏 upvotes, filter by exam
- [x] **My classes**: weekly timetable editor (Mon–Sat)
- [x] Settings: light/dark/system theme, edit profile, log out
- [x] CI: GitHub Actions runs core tests and builds a debug APK you can sideload

## 5b. Design refresh

The MVP's emoji-driven look was replaced with a proper **Material You (Material 3)** design. See [DESIGN.md](DESIGN.md).

- Dynamic colour from the wallpaper on Android 12+, with a pink-and-teal brand palette as the fallback
- Plus Jakarta Sans typography, rounder shapes, Material Symbols Rounded icons instead of emoji
- Profile photos (initials as the fallback), icon pickers for gangs, spots and meals
- Reworked Chat, Plans, Squad, Study, onboarding and settings, with bottom sheets for create flows
- Debug-only screen gallery with sample data for design review

## 5c. Map and group settings

- **Map tab.** Everyone in the gang as an avatar on an OpenStreetMap map of campus, Snap Map style. People in **Live** mode appear at their exact position (with a pulsing ring); people in **Spot** mode appear on their pinned spot, never their real position; **Ghost** people don't appear. Check-ins older than 6 hours drop off, and ones older than 45 minutes are drawn faded. Avatars on the same spot fan out so none hides another. Tap an avatar for a details card with "Open in Maps".
- **Group settings** (tap the gang's name, or menu, Group settings). Anyone allowed to edit can change the gang's **photo**, name and icon. **Admins** choose who can edit the gang (members or admins only) and who can send messages (everyone or admins only), and can make or remove admins. If the last admin leaves, the longest-standing member is promoted.
- All of this is enforced **in the database** (migration `0003`), not just hidden in the app: members can't change permissions, a locked chat rejects messages from non-admins, and group photos can only be uploaded to the gang's own folder.

## 6. Roadmap

**Phase 2: make it sticky**
- Push notifications (Firebase Cloud Messaging + a Supabase Edge Function): new lunch request, someone RSVP'd to your plan, birthday morning, exam tomorrow
- Daily birthday post in chat (pg_cron)
- Images in chat & lunch board photos
- Reply threads, mentions (@name), message delete/edit
- Polls ("Domino's or Mic Mac?")

**Phase 3: more fun**
- Streaks ("ate lunch together 5 days in a row 🔥")
- Hangout leaderboard / badges (most plans made, most notes stacked)
- "Bored" button that pings everyone free right now
- Shared expenses for outings (who owes whom)
- Import timetable from a photo / DTU timetable PDF
- Google sign-in

**Phase 4: iOS**
- Option A: rewrite the UI in SwiftUI against the same Supabase backend (supabase-swift). All server logic is in SQL, so only the client is new.
- Option B: move to **Compose Multiplatform**. `:core` is already pure Kotlin, and supabase-kt is multiplatform, so the data layer can be shared as-is.
- Option B is likely less work given the codebase is Kotlin end to end.

## 7. Known limitations

- On the map, people whose spots are close together can overlap when you zoom far out. Zoom in and they separate.
- The seeded "DTU campus" spot has its centre in the middle of campus, so anyone in spot mode who isn't at a more specific pinned spot is drawn there.
- Map tiles use OpenStreetMap's free public servers (fair use, no key).

- Android limits background checks to about every 15 minutes, and only with "Allow all the time" location permission. Without it, check-ins happen when you open the app or tap **Check in**.
- Realtime can't filter DELETE events, so un-reacting and un-RSVPing by *other* people show up on the next refresh rather than instantly.
- A zone pinned in one gang can appear by name to another gang you're in, if you're standing in it.
- Lunch board is per-person per-day and visible to all your gangs.
