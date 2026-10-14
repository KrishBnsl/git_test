# Setting up gng

About 15 minutes, all free.

## 1. Create the Supabase project

1. Sign up at [supabase.com](https://supabase.com) and create a new project (region: **Mumbai / ap-south-1** is closest to Delhi).
2. Open **SQL Editor → New query**, paste all of `supabase/migrations/0001_init.sql`, and **Run**.
   Then do the same with `supabase/migrations/0002_chat.sql` (DMs, encryption, receipts, calls).
   Already running gng? Just run `0002_chat.sql`. Note it resets existing reactions, and
   everyone needs the new APK, because the server now only accepts encrypted messages.
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

## 3. Push notifications (optional, ~10 minutes)

Without this the app still works; you just only get notified while gng is open.

1. **Firebase**: at [console.firebase.google.com](https://console.firebase.google.com) create a project (no Analytics needed),
   then **Add app → Android** with package name `com.gng.app`. Download `google-services.json` into `app/`
   (it's git-ignored). For CI builds, paste its contents into a repo secret named `GOOGLE_SERVICES_JSON`.
2. **Service account**: Firebase **Project settings → Service accounts → Generate new private key**. Keep that JSON file private.
3. **Deploy the push function** with the [Supabase CLI](https://supabase.com/docs/guides/cli):
   ```bash
   supabase login
   supabase link --project-ref <your-project-ref>
   supabase secrets set FIREBASE_SERVICE_ACCOUNT="$(cat path/to/service-account.json)"
   supabase secrets set WEBHOOK_SECRET="$(openssl rand -hex 32)"   # note this value for step 4
   supabase functions deploy push --no-verify-jwt
   ```
4. **Webhooks**: Supabase dashboard **Database → Webhooks → Create a new hook**, once per row:
   | Name | Table | Events |
   |---|---|---|
   | push-messages | `messages` | Insert |
   | push-calls | `calls` | Insert, Update |

   For each: type **Supabase Edge Functions**, function `push`, method POST,
   and add an HTTP header `x-webhook-secret` with the value from step 3.
5. Rebuild the app. Push can be switched off per phone in **Settings**, and each chat can be muted from its ⋮ menu.

Pushes contain only ids, never message text: the phone downloads and decrypts the message itself before showing it.

## 4. Calls on mobile data (optional)

Calls connect phone-to-phone. On some networks (campus Wi-Fi, some carriers) that's blocked and calls need a relay (a TURN server).
A free tier is enough for a friend group, e.g. [Metered](https://www.metered.ca/tools/openrelay/) or Cloudflare Calls TURN.
Add what they give you to `local.properties` (and as repo secrets for CI builds):

```properties
TURN_URLS=turn:global.relay.metered.ca:80,turns:global.relay.metered.ca:443?transport=tcp
TURN_USERNAME=...
TURN_CREDENTIAL=...
```

The relay only ever sees encrypted audio/video.

## 5. Get it onto your friends' phones

Easiest: let GitHub build it.

1. In the GitHub repo: **Settings → Secrets and variables → Actions → New repository secret**, then add `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
2. Every push runs the **Android** workflow. Open the run → **Artifacts → gng-debug-apk**, download it, and send the APK to the group.
3. On each phone, allow "Install unknown apps" for whatever app opens the APK (Files, WhatsApp, Chrome).

## 6. First run

1. Sign up, pick your emoji and name, and add your birthday.
2. **➕ New gang**, then share the 6-letter code (the share icon in the gang's top bar).
3. Everyone adds their classes: **⋮ → My classes**.
4. Walk to your usual spots (Library, Mic Mac, hostel, OAT…), go to **Squad → 📌 Pin this spot**. Each spot only needs pinning once per gang.
5. For automatic check-ins while the app is closed, set the app's location permission to **Allow all the time**.
6. DMs: tap **✉️ New DM** on the main screen and pick someone from your gangs. DMs sit in the same list as gangs.
7. Stickers: in any chat tap 😀 → **From WhatsApp**, and pick the `WhatsApp Stickers` folder it opens
   (all the stickers you've ever received on WhatsApp), or import `.wastickers` packs. You can also long-press a sticker someone sent to save it.

## Notes

- The anon key is designed to ship inside apps; row-level security is what protects the data. Never put the **service_role** key in the app.
- Free-tier Supabase projects pause after a week with no activity. Open the dashboard and hit **Restore** if that happens.
