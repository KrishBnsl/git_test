# gng 🫶

A private hangout app for our DTU friend group. It's like Discord, but it knows where lunch is.

- 💬 **Hangout & DMs**: end-to-end encrypted chat with replies, tapbacks, photos, files, WhatsApp stickers, and iMessage-style Delivered / Read
- 📞 **Calls**: voice and video, 1:1 or with the gang
- 🔔 **Push notifications** you can switch off, mute per chat, with inline reply
- 🍛 **Plans**: one-tap lunch requests, snacks runs, outings, study sessions with RSVPs; a "when is everyone free" finder built from everyone's timetable; a daily lunch board
- 📍 **Squad**: who's at the Library / Mic Mac / hostel (spot-only or live for an hour, or go ghost), who's in class, upcoming birthdays with confetti
- 📚 **Study**: midsem/endsem radar with a panic meter, and a shared stack of notes, PYQs and links

Android (Kotlin + Jetpack Compose) on a Supabase backend. Multiple gangs, invite codes, light and neon-dark themes.

- **Set it up:** [docs/SETUP.md](docs/SETUP.md)
- **Product & tech plan, roadmap:** [docs/PLAN.md](docs/PLAN.md)

```
app/        Android app (Compose UI, encryption, calls, push, Supabase repositories, location check-ins)
core/       Pure Kotlin logic with unit tests (zones, timetables, birthdays, exams)
supabase/   Database schema, security policies, RPCs, triggers, and the `push` Edge Function
```
