# Setting up gng

About 15 minutes, all free.

## 1. Create the Supabase project

1. Sign up at [supabase.com](https://supabase.com) and create a new project (region: **Mumbai / ap-south-1** is closest to Delhi).
2. Open **SQL Editor → New query**, paste all of `supabase/migrations/0001_init.sql`, and **Run**.
   Then do the same for `supabase/migrations/0002_design_refresh.sql` (profile photos, icon keys, plain-text chat events).
   Run each file **once**. Running one again fails with `relation ... already exists`, which is harmless.
   Already ran `0001` earlier? Just run `0002`.
   Then run `supabase/migrations/0003_group_settings_and_map.sql` (group photos and permissions, admin roles, map data).
   Then run `supabase/migrations/0004_direct_messages.sql` (the first, plain-text direct messages).
   Then run `supabase/migrations/0005_chat.sql` (end-to-end encryption, encrypted DMs, read receipts, media, push, calls),
   and `0006_push_triggers.sql` (sends new messages and calls to the push function; see §3).
   Then `0007_mentions.sql` (@mentions), `0008_history_and_push.sql` (chat history for new members and new phones:
   the last 100 messages of a gang, all of a DM; reaction notifications, unread counts in notifications), and
   `0009_people_and_requests.sql` (@usernames, colleges, finding anyone on gng, Instagram-style message requests),
   `0010_gang_preview.sql` (see a gang before joining it from a QR code or invite link),
   `0011_edit_delete_receipts.sql` (edit / delete for everyone, exact "Seen" times) and `0012_calls_tidy.sql`
   (one chat entry per call, missed calls, the "typing…" channel).

   Invite links point at `https://<you>.github.io/gng/join/?c=CODE`, a small page in `web/` that the
   **Invite page** workflow publishes to GitHub Pages (Settings › Pages › Source: GitHub Actions). It opens gng,
   or offers the download. If you fork, change `LINK_BASE` in `core/.../InviteCodes.kt` and the host in the manifest.
   All six can be run again safely if you're unsure whether you did; run them in order.
   ⚠️ After `0005` the server only accepts encrypted chat messages, so everyone needs the new app. Its first run also resets reactions.
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

### Run on an emulator, with a design gallery

If you have the Android SDK and an emulator (or a phone over USB), the debug build includes a **gallery** that shows
every screen with sample data, so you can review the design without a backend or an account:

```bash
./gradlew :app:installDebug
adb shell am start -n com.gng.app/.debug.GalleryActivity --es screen chat --ez dark true --ez dynamic false
```

`--es screen` takes `auth-signup`, `auth-login`, `profile-new`, `profile-edit`, `groups`, `groups-empty`, `chat`, `plans`,
`squad`, `study`, `timetable`, `settings`, or a sheet: `sheet-plan`, `sheet-lunch`, `sheet-gang`, `sheet-join`, `sheet-pin`,
`sheet-exam`, `sheet-material`, `sheet-class`, `sheet-group-edit`. Also `map`, `map-selected`, `map-empty`,
`group-settings`, `group-settings-member` (the read-only view a regular member gets), `chat-locked`,
`dm-chat`, `sheet-new-message`, and the update cards `update-available`,
`update-downloading`, `update-permission` and `update-failed`. `--ez dark` switches to dark theme and `--ez dynamic` to wallpaper colours.
The gallery only exists in debug builds; release builds don't contain it.

## 3. Push notifications (optional, ~10 minutes)

Without this the app still works; you just only get notified while gng is open.

1. **Firebase**: at [console.firebase.google.com](https://console.firebase.google.com) create a project (no Analytics needed),
   then **Add app → Android** with package name `com.gng.app`. Download `google-services.json` into `app/`
   (it's git-ignored). For CI builds, paste its contents into a repo secret named `GOOGLE_SERVICES_JSON`.
2. **Service account**: Firebase **Project settings → Service accounts → Generate new private key**. Keep that JSON file private.
   If it says *"Key creation is not allowed ... organization policies"*, your Google Cloud organization blocks key downloads
   by default. In Google Cloud **IAM & Admin → Organization policies**, open **Disable service account key creation**
   (`iam.disableServiceAccountKeyCreation`) for the project, **Override parent's policy** with enforcement **Off**,
   make the key, then set it back to **Inherit parent's policy**. Changing it needs the *Organization Policy Administrator* role.
3. **Deploy the push function** with the [Supabase CLI](https://supabase.com/docs/guides/cli):
   ```bash
   supabase login
   supabase link --project-ref <your-project-ref>
   supabase secrets set FIREBASE_SERVICE_ACCOUNT="$(cat path/to/service-account.json)"
   supabase secrets set WEBHOOK_SECRET="$(openssl rand -hex 32)"   # keep this value for step 4
   supabase functions deploy push --no-verify-jwt
   ```
4. **Hook the database up to it**: run `supabase/migrations/0006_push_triggers.sql` (SQL editor, or
   `supabase db query --linked -f supabase/migrations/0006_push_triggers.sql`). Then store the function's URL and the
   same `WEBHOOK_SECRET` from step 3 in Supabase Vault, in the SQL editor:
   ```sql
   select vault.create_secret('https://<project-ref>.supabase.co/functions/v1/push', 'push_function_url');
   select vault.create_secret('<your WEBHOOK_SECRET>', 'push_webhook_secret');
   ```
   (This replaces clicking together Database Webhooks in the dashboard, and keeps the secret out of git.)
5. Rebuild the app. Push can be switched off per phone in **Settings**, and each chat can be muted from its ⋮ menu.

Pushes never contain readable message text: they carry the still-encrypted message (when it fits in a push), plus
names and unread counts, and the phone decrypts it itself. After pulling a new version of `supabase/functions/push`,
deploy it again (`supabase functions deploy push --no-verify-jwt`).

If notifications still arrive late on a phone, open gng's **Settings**: it shows a "Get notifications on time" row
when the phone's battery saver is holding gng back (common on Xiaomi, Oppo, Vivo, Realme and Samsung).

## 4. Calls on mobile data (optional)

Calls connect phone-to-phone. On some networks (campus Wi-Fi, some carriers) that's blocked and calls need a relay (a TURN server).
A free tier is enough for a friend group, e.g. [Metered](https://www.metered.ca/tools/openrelay/) or Cloudflare Calls TURN.
Add what they give you to `local.properties` (and as repo secrets for CI builds). List every transport the
provider offers, especially TLS on port 443 (`turns:...:443?transport=tcp`): it's the one that gets through strict
Wi-Fi and carrier firewalls. For Metered (use your region's host):

```properties
TURN_URLS=turn:asia.relay.metered.ca:80,turn:asia.relay.metered.ca:80?transport=tcp,turn:asia.relay.metered.ca:443,turns:asia.relay.metered.ca:443?transport=tcp
TURN_USERNAME=...
TURN_CREDENTIAL=...
```

The relay only ever sees encrypted audio/video.

## 5. Get it onto your friends' phones

Easiest: let GitHub build it.

1. In the GitHub repo: **Settings → Secrets and variables → Actions → New repository secret**, then add `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
2. Every push runs the **Android** workflow. Open the run → **Artifacts → gng-debug-apk**, download it, and send the APK to the group.
3. On each phone, allow "Install unknown apps" for whatever app opens the APK (Files, WhatsApp, Chrome).

## 6. Signed release APKs

(Once friends have a build that contains the updater, they never need this page again: see [Updates](#updates) below.)

Debug APKs are fine for testing. For proper releases (installable as updates over each other) you sign with your own key.

**One-time: make the signing key.** In a terminal with Java (Android Studio's *Terminal* tab works):

```bash
keytool -genkeypair -v -keystore gng-release.jks -alias gng -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 gng-release.jks > gng-release.jks.b64   # macOS: base64 -i gng-release.jks -o gng-release.jks.b64
```

⚠️ **Back up `gng-release.jks` and its password somewhere safe (not in the repo).** If you lose it, phones can't update to new versions; everyone has to uninstall and reinstall.

Add four more repository secrets:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the contents of `gng-release.jks.b64` |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password you chose |
| `RELEASE_KEY_ALIAS` | `gng` |
| `RELEASE_KEY_PASSWORD` | the key password (same as the keystore password unless you set a different one) |

**Each release:** push a version tag, or use **Actions → Release → Run workflow** and type a version.

```bash
git tag v0.2.0 && git push origin v0.2.0
```

The **Release** workflow builds a signed `gng-0.2.0.apk` and attaches it to a new page under the repo's **Releases**. Share that link with the gang. Installing a newer release updates the app in place, keeping everyone logged in.

### Updates

gng updates itself from GitHub Releases, in the background (**Settings, About, Update automatically**, on by default):

1. When a release is published, the release workflow tells every phone through a silent push (needs the
   `RELEASE_SECRET` secret, below). Phones also check by themselves every few hours and on app start.
2. The phone downloads the APK and checks it's a newer build of gng signed with the same key.
3. As soon as gng isn't on screen and there's no call going, it installs through Android's package installer.
   - **Android 12 and newer:** no prompt, once gng installed the version that's there now (on Android 14+
     gng also claims to be the app that updates itself). So the very first update may still ask once, through
     a "gng update ready" notification; after that, updates are silent.
   - **Android 11 and older:** Android always asks; the notification is one tap.
4. The first time, Android may also ask you to allow gng to "install unknown apps".

With automatic updates off, an **Update available** card appears instead, and **Update** does the same steps
while you watch.

**Instant updates on release:** pick any long random string and store it in two places:

```sh
supabase secrets set RELEASE_SECRET=<string>
gh secret set RELEASE_SECRET   # paste the same string
```

Without it everything still works; phones just find the update on their next check.

**Publishing an update** is just cutting a release (tag `v0.3.1` or use **Releases, Draft a new release**). Release notes become the "What's new" list.

Things to know:

- The updater first ships in **v0.3.0**. Friends on an older build must install v0.3.0 by hand once (from the Releases page), and updates are automatic from then on.
- Updates only work between builds signed with **your release key**. A debug build, or an APK from a CI run, can't update to a release (and vice versa): uninstall first.
- The repo must stay **public**: the app reads the release through GitHub's public API without a token.
- Update checks are off in debug builds. To test the updater locally, set `UPDATE_API_URL` in `local.properties` to a server that serves GitHub's release JSON (see `UpdateChecker.kt`), and build with `VERSION_CODE=2 VERSION_NAME=9.9.9` for the "new" APK.

## 7. First run

1. Sign up, pick your emoji and name, and add your birthday.
2. **➕ New gang**, then share the 6-letter code (the share icon in the gang's top bar).
3. Everyone adds their classes: **⋮ → My classes**.
4. Walk to your usual spots (Library, Mic Mac, hostel, OAT…), go to **Squad → 📌 Pin this spot**. Each spot only needs pinning once per gang.
5. For automatic check-ins while the app is closed, set the app's location permission to **Allow all the time**.
6. DMs: tap the **new message** icon at the top of the home screen (or **Message** on someone in Squad, the map or group settings).
   DMs sit in the same list as your gangs.
7. Stickers: in any chat tap the smiley in the message box, then **From WhatsApp**, and pick the `WhatsApp Stickers` folder it opens
   (all the stickers you've ever received on WhatsApp), or import `.wastickers` packs. You can also long-press a sticker someone sent to save it.

## Notes

- **Map tiles** come from OpenStreetMap's public servers, which need no key. They are run by volunteers with a fair-use policy, which is fine for a friend group. If gng grows, switch the tile source in `ui/map/OsmMap.kt` to a keyed provider (MapTiler, Stadia, or your own).

- The anon key is designed to ship inside apps; row-level security is what protects the data. Never put the **service_role** key in the app.
- Free-tier Supabase projects pause after a week with no activity. Open the dashboard and hit **Restore** if that happens.
