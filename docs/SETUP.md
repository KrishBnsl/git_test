# Setting up gng

About 15 minutes, all free.

## 1. Create the Supabase project

1. Sign up at [supabase.com](https://supabase.com) and create a new project (region: **Mumbai / ap-south-1** is closest to Delhi).
2. Open **SQL Editor → New query**, paste all of `supabase/migrations/0001_init.sql`, and **Run**.
3. **Authentication → Providers → Email**: keep it enabled.
   For a friends-only app it's easiest to turn **Confirm email** *off*. Otherwise everyone has to click a link in their inbox before logging in.
4. **Project Settings → API**: copy the **Project URL** and the **anon public** key.

## 2. Build the app

You need [Android Studio](https://developer.android.com/studio) (Ladybug or newer; it bundles the JDK and Android SDK).

1. Clone the repo and open the folder in Android Studio.
2. Create `local.properties` in the project root (it's git-ignored) and add:
   ```properties
   SUPABASE_URL=https://xxxxxxxx.supabase.co
   SUPABASE_ANON_KEY=eyJhbGciOi...
   ```
   (Android Studio may already have put `sdk.dir=...` there; keep that line.)
3. Plug in your phone with USB debugging on (or start an emulator) and hit **Run ▶**.

From the terminal instead:

```bash
./gradlew :core:test          # unit tests for the logic module
./gradlew :app:assembleDebug  # APK at app/build/outputs/apk/debug/app-debug.apk
```

## 3. Get it onto your friends' phones

Easiest: let GitHub build it.

1. In the GitHub repo: **Settings → Secrets and variables → Actions → New repository secret**, then add `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
2. Every push runs the **Android** workflow. Open the run → **Artifacts → gng-debug-apk**, download it, and send the APK to the group.
3. On each phone, allow "Install unknown apps" for whatever app opens the APK (Files, WhatsApp, Chrome).

## 4. First run

1. Sign up, pick your emoji and name, and add your birthday.
2. **➕ New gang**, then share the 6-letter code (the share icon in the gang's top bar).
3. Everyone adds their classes: **⋮ → My classes**.
4. Walk to your usual spots (Library, Mic Mac, hostel, OAT…), go to **Squad → 📌 Pin this spot**. Each spot only needs pinning once per gang.
5. For automatic check-ins while the app is closed, set the app's location permission to **Allow all the time**.

## Notes

- The anon key is designed to ship inside apps; row-level security is what protects the data. Never put the **service_role** key in the app.
- Free-tier Supabase projects pause after a week with no activity. Open the dashboard and hit **Restore** if that happens.
