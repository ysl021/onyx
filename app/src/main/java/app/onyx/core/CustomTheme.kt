package app.onyx.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A user-imported palette. Only a few colours are required: the rest of the
 * palette is derived so imported themes cannot break contrast or glass.
 *
 * Example:
 * {
 *   "schema": 1,
 *   "id": "midnight",
 *   "name": "Midnight",
 *   "light": false,
 *   "background": "#050506",
 *   "surface": "#101013",
 *   "text": "#F6F6F7",
 *   "accent": "#7DA7FF"
 * }
 */
@Serializable
data class CustomTheme(
    val schema: Int = 1,
    val id: String = "",
    val name: String = "",
    val light: Boolean = false,
    val background: String = "#050506",
    val surface: String = "#101013",
    val text: String = "#F6F6F7",
    val accent: String = "#EDEDEF",
) {
    fun normalized(): CustomTheme? {
        if (name.isBlank()) return null
        if (!HEX.matches(background) || !HEX.matches(surface) || !HEX.matches(text) || !HEX.matches(accent)) {
            return null
        }
        return copy(
            id = id.ifBlank { name.lowercase().filter { it.isLetterOrDigit() } },
            name = name.trim().take(40),
        )
    }

    companion object {
        val HEX = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    }
}

object CustomThemeCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

    fun decodeList(raw: String): List<CustomTheme> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<CustomTheme>>(raw)
                .mapNotNull { it.normalized() }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    fun encodeList(list: List<CustomTheme>): String =
        runCatching { json.encodeToString(list) }.getOrDefault("[]")

    fun decodeOne(raw: String): CustomTheme? = runCatching {
        json.decodeFromString<CustomTheme>(raw).normalized()
    }.getOrNull()

    fun encodeOne(theme: CustomTheme): String =
        runCatching { json.encodeToString(theme) }.getOrDefault("{}")
}
