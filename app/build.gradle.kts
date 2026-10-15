import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Push notifications need app/google-services.json from your Firebase project (git-ignored).
// Without it the app still builds; push is simply unavailable.
val hasFirebase = file("google-services.json").exists()
if (hasFirebase) apply(plugin = libs.plugins.google.services.get().pluginId)

// Supabase credentials come from local.properties (not committed) or environment variables.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String): String = localProps.getProperty(name) ?: System.getenv(name) ?: ""

android {
    namespace = "com.gng.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gng.app"
        minSdk = 26
        targetSdk = 36
        // CI sets these per release; every update must have a higher versionCode.
        versionCode = secret("VERSION_CODE").toIntOrNull() ?: 1
        versionName = secret("VERSION_NAME").ifBlank { "0.1.0" }

        // Phones are ARM; skipping x86 roughly halves the APK (WebRTC is mostly native code).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        // Where new versions are announced: the latest GitHub release of this repo. Override it in
        // local.properties (UPDATE_API_URL) to test the updater against a local server.
        buildConfigField(
            "String", "UPDATE_API_URL",
            "\"${secret("UPDATE_API_URL").ifBlank { "https://api.github.com/repos/KrishBnsl/gng/releases/latest" }}\"",
        )
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
        buildConfigField("boolean", "HAS_FIREBASE", hasFirebase.toString())
        // Optional TURN relay for calls on networks that block peer-to-peer (comma-separated turn: URLs).
        buildConfigField("String", "TURN_URLS", "\"${secret("TURN_URLS")}\"")
        buildConfigField("String", "TURN_USERNAME", "\"${secret("TURN_USERNAME")}\"")
        buildConfigField("String", "TURN_CREDENTIAL", "\"${secret("TURN_CREDENTIAL")}\"")
    }

    // Release signing key, from local.properties or CI secrets. Without it the release build is unsigned.
    val keystorePath = secret("RELEASE_KEYSTORE_PATH")
    if (keystorePath.isNotBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = secret("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds are signed with a different key than releases, so they can't update from one.
            // The updater is only on in debug when a test server is configured.
            buildConfigField("boolean", "UPDATE_ENABLED", secret("UPDATE_API_URL").isNotBlank().toString())
        }
        release {
            buildConfigField("boolean", "UPDATE_ENABLED", "true")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.tink.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.stream.webrtc)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.network.okhttp)
    // Any emoji as a reaction.
    implementation(libs.androidx.emoji.picker)
    // Cutting the subject out of a photo for stickers (model comes via Google Play services).
    implementation(libs.mlkit.subject.segmentation)
    // Invite QR codes: drawing them, and scanning them with Google's scanner (no camera permission needed).
    implementation(libs.zxing.core)
    implementation(libs.play.services.code.scanner)
    implementation(libs.osmdroid)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
