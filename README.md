# gng 🫶

A private hangout app for our DTU friend group. It's like Discord, but it knows where lunch is.

- 💬 **Hangout**: realtime group chat with emoji reactions
- 🍛 **Plans**: one-tap lunch requests, snacks runs, outings, study sessions with RSVPs; a "when is everyone free" finder built from everyone's timetable; a daily lunch board
- 📍 **Squad**: who's at the Library / Mic Mac / hostel (spot-only or live for an hour, or go ghost), who's in class, upcoming birthdays with confetti
- 📚 **Study**: midsem/endsem radar with a panic meter, and a shared stack of notes, PYQs and links

Android (Kotlin + Jetpack Compose) on a Supabase backend. Multiple gangs, invite codes, light and neon-dark themes.

- **Set it up:** [docs/SETUP.md](docs/SETUP.md)
- **Product & tech plan, roadmap:** [docs/PLAN.md](docs/PLAN.md)

```
app/        Android app (Compose UI, Supabase repositories, location check-ins)
core/       Pure Kotlin logic with unit tests (zones, timetables, birthdays, exams)
supabase/   Database schema, security policies, RPCs, triggers
```
