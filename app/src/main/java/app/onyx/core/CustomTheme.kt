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

val BuiltInThemes = listOf(
    CustomTheme(
        id = "graphite",
        name = "Graphite",
        light = false,
        background = "#0A0A0B",
        surface = "#141416",
        text = "#F2F2F4",
        accent = "#9AA4B2",
    ),
    CustomTheme(
        id = "paper",
        name = "Paper",
        light = true,
        background = "#F7F5F0",
        surface = "#FFFFFF",
        text = "#141416",
        accent = "#2F6BFF",
    ),
    CustomTheme(
        id = "midnight",
        name = "Midnight",
        light = false,
        background = "#050A18",
        surface = "#0B1326",
        text = "#EAF0FF",
        accent = "#6FA8FF",
    ),
    CustomTheme(
        id = "sakura",
        name = "Sakura",
        light = true,
        background = "#FFF6F8",
        surface = "#FFFFFF",
        text = "#231A1E",
        accent = "#D65B85",
    ),
)

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
