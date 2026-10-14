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
| "Did you see the chat?" | **Hangout**: end-to-end encrypted group chat with replies, tapbacks, photos, files, stickers and iMessage-style read receipts; plans, exams and new notes post themselves into it |
| "Can I just text you?" | **DMs**: 1:1 encrypted chats, listed alongside your gangs |
| "Call?" | **Voice & video calls**: 1:1 in DMs, small group calls (up to ~6) in gangs |

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

## 3b. Privacy model (messages and calls)

* **Everything people write is end-to-end encrypted**: text, photos, files, stickers, replies and reactions.
  The server stores ciphertext; it can't read any of it.
* Each phone has an X25519 identity key (Tink HPKE). The private key stays in the phone's keystore-protected storage.
* Each conversation has **key epochs**: a random AES-256-GCM key, wrapped for every member's public key
  (`conversation_keys`). Before sending, a phone checks the newest epoch was wrapped for exactly the current members'
  current keys; if not (someone joined, left or got a new phone) it publishes a new epoch (`publish_epoch()`).
  So people who leave can't read what comes next, and new members can't read the history from before they joined.
* Attachments get their own random key, are encrypted before upload to the private `chat-media` bucket,
  and the key travels inside the encrypted message. The phone checks a SHA-256 before showing a download.
* **What the server does see** (metadata): who is in which conversation, who sent a message and when, its size,
  that a reaction changed, delivered/read cursors, and call start/end times. Plans, exams, the notes stack, lunch board,
  timetables and locations are *not* end-to-end encrypted (they're protected by row-level security, as before).
* **Verify encryption** (DM ⋮ menu) shows a 60-digit safety code. If it matches on both phones, nobody swapped a key.
* **Calls** are WebRTC: audio/video is encrypted phone-to-phone (DTLS-SRTP), even through a TURN relay.
  Supabase only carries the connection handshake, over a private Realtime channel that only the conversation's members can use.
* **Push** carries only ids. The phone fetches and decrypts the message and writes the notification itself,
  so Google never sees message content either.

## 4. Architecture

```
┌────────────── Android app (Kotlin, Compose) ──────────────┐
│ ui/        screens + ViewModels (one per tab)             │
│ data/      repositories over supabase-kt                  │
│ location/  CheckIn + 15-min WorkManager job               │
│ crypto/    identity keys, key epochs, AES-GCM (Tink)      │
│ call/      WebRTC mesh calls, call screen, call service   │
│ notify/    notifications, FCM push, inline reply          │
│ data/ChatCache  SQLite cache of decrypted messages        │
│ core/      pure Kotlin: zones, timetable math, birthdays, │
│            exam countdowns (unit-tested, no Android deps) │
└──────────────┬────────────────────────────────────────────┘
               │ HTTPS (PostgREST) + WebSocket (Realtime)
┌──────────────▼──────────── Supabase ──────────────────────┐
│ Auth (email + password)                                   │
│ Postgres + row-level security (is_member / shares_group)  │
│ RPCs: create_group, join_group, group_presence, open_dm,  │
│       publish_epoch, mark_seen, my_conversations,         │
│       start_call, end_call                                │
│ Triggers: plans/exams/materials → chat messages; auto-RSVP│
│ Realtime: messages, reactions, receipts, keys, calls, …   │
│ Storage: private `materials` + `chat-media` buckets       │
│ Edge Function `push` → FCM (ids only)                     │
└───────────────────────────────────────────────────────────┘
```

* **Schema**: `supabase/migrations/0001_init.sql` (tables, policies, RPCs, triggers, bucket, realtime) and
  `0002_chat.sql` (DMs, encryption keys, receipts, reactions, push tokens, calls, chat media).
* **Caching**: decrypted messages and reactions live in an on-device SQLite cache (`ChatCache`), so chats open
  instantly and work offline; only newer messages are fetched. Sends are optimistic ("Sending…" → "Sent").
* **Modules**: `:core` is plain Kotlin/JVM so the fiddly logic (zone matching, common free windows, leap-day birthdays) is unit-tested without an emulator. `:app` is the Android app.
* **No DI framework**: `AppContainer` wires the repositories by hand. That's plenty at this size.

### Data model

| Table | Purpose |
|---|---|
| `profiles` | name, emoji avatar, branch, year, birthday, status |
| `groups`, `group_members` | gangs and who's in them (admin/member) |
| `groups.kind` | `gang` or `dm` (a DM is a two-person group with a unique `dm_key`) |
| `messages`, `reactions` | chat; user text is ciphertext; non-text kinds (plan/exam/call…) are posted by the server |
| `user_keys`, `conversation_keys` | public identity keys; per-epoch conversation keys wrapped per member |
| `read_states` | delivered/read cursor per person per conversation (powers Sent → Delivered → Read) |
| `device_tokens` | FCM tokens for push |
| `calls` | call start/end; signaling goes over Realtime |
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

## 5b. Chat overhaul (this branch)

- [x] End-to-end encryption for all chat content (see §3b)
- [x] DMs in the main list, alongside gangs; unread badges and previews
- [x] iMessage-style status under your latest message: Sending… → Sent → Delivered → Read 10:42 / Read by Aman, Riya; per-person Info sheet
- [x] Tapback reactions (one per person, like iMessage); double-tap for ❤️
- [x] Telegram-style replies: swipe right on a message, tap the quote to jump to the original
- [x] Photos (compressed, blurred preview while loading, zoomable viewer, save to gallery) and files up to 25 MB
- [x] Stickers: import from WhatsApp's stickers folder, `.wastickers` packs, images, or "share to gng"; save stickers others send
- [x] Local message cache, optimistic sending, infinite scroll, catch-up after reconnecting
- [x] Push notifications with inline reply and mark-as-read; global switch, per-chat mute, preview toggle, read-receipt toggle
- [x] Voice and video calls: 1:1 and small group (mesh), incoming-call screen over the lock screen

## 6. Roadmap

**Phase 2: make it sticky**
- More push types: someone RSVP'd to your plan, birthday morning, exam tomorrow
- Daily birthday post in chat (pg_cron)
- Map view of live friends (osmdroid / Google Maps Compose)
- Lunch board photos
- Mentions (@name), message delete/edit, typing indicators, voice notes
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

- Android limits background checks to about every 15 minutes, and only with "Allow all the time" location permission. Without it, check-ins happen when you open the app or tap **Check in**.
- Realtime can't filter DELETE events, so un-reacting and un-RSVPing by *other* people show up on the next refresh rather than instantly.
- A zone pinned in one gang can appear by name to another gang you're in, if you're standing in it.
- Lunch board is per-person per-day and visible to all your gangs.
- **One phone per account** for encryption. Logging in on a new phone creates a new key: chats continue,
  but older messages can't be decrypted there (shown as 🔒). Same after reinstalling.
- In a gang, any member holds the conversation key, so a malicious *member* could forge a message that looks like it's
  from someone else. (Sender signatures would fix this; Signal-style sender keys are the long-term option.)
- Safety codes are only offered in DMs. In gangs you'd verify each person via your DM with them.
- Group calls are a mesh: each phone sends its video to everyone else, so beyond ~5–6 people quality drops.
- Without a TURN server, calls may fail on networks that block peer-to-peer traffic.
- Read times are exact only for the latest message (receipts are per-person cursors, not per message).
- People on the old app version can't send messages after `0002_chat.sql` until they update.
