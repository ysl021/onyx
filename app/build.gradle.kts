import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Secrets are injected at build time and never committed. Copy
// secrets.properties.example to secrets.properties for local builds.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String =
    (secrets.getProperty(name) ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

// Version comes from the root build script so Android and desktop always share
// one global value. version.properties only stores the human version (0.9.0);
// the build number is derived from it instead of being a fixed constant.
val appVersionName: String = rootProject.extra["onyxVersionName"] as String
val appVersionCode: Int = rootProject.extra["onyxVersionCode"] as Int

// Release signing lives outside the repo: keystore.properties is ignored by
// git, the keystore itself is ignored by *.jks. CI restores both from secrets.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreFile.exists() &&
    !keystore.getProperty("storeFile").isNullOrBlank()
// `-PdebugSigning` builds the transitional APK that must install over the old
// debug-signed release before the one-time reinstall.
val useDebugSigning = project.hasProperty("debugSigning") || !hasReleaseKey

android {
    namespace = "app.onyx"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.onyx"
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "SPICY_KEY", "\"${secret("SPICY_KEY")}\"")
        // True for the 0.8.10 migration build signed with the old debug key.
        // That build can export data but must not offer the release-signed
        // update (Android rejects it with a signature mismatch).
        buildConfigField("boolean", "TRANSITIONAL", useDebugSigning.toString())
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug signing is only used for the 0.8.10 migration build; every
            // published release uses the dedicated keystore above.
            signingConfig = if (useDebugSigning) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("dev.chrisbanes.haze:haze:0.7.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    // NewPipeExtractor v0.27.0, built locally from the v0.27.0 tag (JitPack
    // publishes no usable artifacts for 0.25+). Sources: github.com/TeamNewPipe/NewPipeExtractor
    implementation(files("libs/NewPipeExtractor-0.27.0.jar"))
    implementation(files("libs/nanojson.jar"))
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("net.jthink:jaudiotagger:3.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
