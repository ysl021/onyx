import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
}

// One global version for every module. version.properties only carries the
// human version (0.9.0); the build number is derived from it, so it is never a
// hand-maintained fixed value. Android and desktop read the same extras.
val onyxVersionProps = Properties().apply {
    val file = file("version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val onyxVersionName: String = onyxVersionProps.getProperty("versionName") ?: "0.0.0"
val onyxVersionCode: Int = onyxVersionProps.getProperty("versionCode")?.toIntOrNull()
    ?: run {
        val parts = onyxVersionName.split('.').map { it.toIntOrNull() ?: 0 }
        parts.getOrElse(0) { 0 } * 1_000_000 +
            parts.getOrElse(1) { 0 } * 1_000 +
            parts.getOrElse(2) { 0 }
    }
extra["onyxVersionName"] = onyxVersionName
extra["onyxVersionCode"] = onyxVersionCode
