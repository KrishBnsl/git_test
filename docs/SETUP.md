# Setting up gng

About 15 minutes, all free.

## 1. Create the Supabase project

1. Sign up at [supabase.com](https://supabase.com) and create a new project (region: **Mumbai / ap-south-1** is closest to Delhi).
2. Open **SQL Editor → New query**, paste all of `supabase/migrations/0001_init.sql`, and **Run**.
   Then do the same for `supabase/migrations/0002_design_refresh.sql` (profile photos, icon keys, plain-text chat events).
   Run each file **once**. Running one again fails with `relation ... already exists`, which is harmless.
   Already ran `0001` earlier? Just run `0002`.
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
`sheet-exam`, `sheet-material`, `sheet-class`. `--ez dark` switches to dark theme and `--ez dynamic` to wallpaper colours.
The gallery only exists in debug builds; release builds don't contain it.

## 3. Get it onto your friends' phones

Easiest: let GitHub build it.

1. In the GitHub repo: **Settings → Secrets and variables → Actions → New repository secret**, then add `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
2. Every push runs the **Android** workflow. Open the run → **Artifacts → gng-debug-apk**, download it, and send the APK to the group.
3. On each phone, allow "Install unknown apps" for whatever app opens the APK (Files, WhatsApp, Chrome).

## 4. Signed release APKs

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

## 5. First run

1. Sign up, pick your emoji and name, and add your birthday.
2. **➕ New gang**, then share the 6-letter code (the share icon in the gang's top bar).
3. Everyone adds their classes: **⋮ → My classes**.
4. Walk to your usual spots (Library, Mic Mac, hostel, OAT…), go to **Squad → 📌 Pin this spot**. Each spot only needs pinning once per gang.
5. For automatic check-ins while the app is closed, set the app's location permission to **Allow all the time**.

## Notes

- The anon key is designed to ship inside apps; row-level security is what protects the data. Never put the **service_role** key in the app.
- Free-tier Supabase projects pause after a week with no activity. Open the dashboard and hit **Restore** if that happens.
