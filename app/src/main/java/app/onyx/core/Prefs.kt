package app.onyx.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.dataStore by preferencesDataStore("onyx")

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class AppLanguage { RU, EN }

@Serializable
data class LocalFolder(
    val uri: String,
    val name: String,
)

data class PrefsData(
    val spotifyClientId: String = "",
    val spotifyAccess: String = "",
    val spotifyRefresh: String = "",
    val spotifyExpiresAt: Long = 0L,
    val spotifyUser: String = "",
    val karaoke: Boolean = false,
    val translate: Boolean = true,
    val lyricFontSize: Float = 30f,
    val lyricOffsetMs: Long = 0L,
    val lyricActiveColor: Long = 0xFFF6F6F7,
    val lyricFont: String = "system",
    val uiFont: String = "system",
    val spicyKey: String = Prefs.SPICY_DEFAULT_KEY,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val customThemes: String = "",
    val customThemeId: String = "",
    val appIcon: Int = 1,
    val language: AppLanguage = AppLanguage.RU,
    val coverBackdrop: Boolean = true,
    val coverIntensity: Int = 60,
    val coverPages: String = "player,lyrics,queue,playlists,music,visualizer,settings",
    val pageBackgrounds: String = "",
    val pageBackgroundBlur: String = "",
    val eqEnabled: Boolean = false,
    val eqPreset: Int = 0,
    val eqLevels: String = "",
    val localFolderUri: String = "",
    val localFolderName: String = "",
    val localFolders: List<LocalFolder> = emptyList(),
    val budsAutoConnect: Boolean = true,
    val budsMac: String = "",
)

class Prefs(private val context: Context) {
    private object K {
        val clientId = stringPreferencesKey("spotify_client_id")
        val access = stringPreferencesKey("spotify_access")
        val refresh = stringPreferencesKey("spotify_refresh")
        val expires = longPreferencesKey("spotify_expires")
        val user = stringPreferencesKey("spotify_user")
        val karaoke = booleanPreferencesKey("karaoke")
        val translate = booleanPreferencesKey("translate")
        val fontSize = floatPreferencesKey("font_size")
        val offset = longPreferencesKey("offset")
        val lyricActiveColor = longPreferencesKey("lyric_active_color")
        val lyricFont = stringPreferencesKey("lyric_font")
        val uiFont = stringPreferencesKey("ui_font")
        val spicyKey = stringPreferencesKey("spicy_key")
        val themeMode = stringPreferencesKey("theme_mode")
        val customThemes = stringPreferencesKey("custom_themes")
        val customThemeId = stringPreferencesKey("custom_theme_id")
        val appIcon = intPreferencesKey("app_icon")
        val language = stringPreferencesKey("language")
        val coverBackdrop = booleanPreferencesKey("cover_backdrop")
        val coverIntensity = intPreferencesKey("cover_intensity")
        val coverPages = stringPreferencesKey("cover_pages")
        val pageBackgrounds = stringPreferencesKey("page_backgrounds")
        val pageBackgroundBlur = stringPreferencesKey("page_background_blur")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = intPreferencesKey("eq_preset")
        val eqLevels = stringPreferencesKey("eq_levels")
        val localFolderUri = stringPreferencesKey("local_folder_uri")
        val localFolderName = stringPreferencesKey("local_folder_name")
        val localFolders = stringPreferencesKey("local_folders")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val budsMac = stringPreferencesKey("buds_mac")
    }

    val data: Flow<PrefsData> = context.dataStore.data.map { p -> p.toData() }

    suspend fun snapshot(): PrefsData = context.dataStore.data.first().toData()

    private fun Preferences.toData() = PrefsData(
        spotifyClientId = this[K.clientId] ?: "",
        spotifyAccess = this[K.access] ?: "",
        spotifyRefresh = this[K.refresh] ?: "",
        spotifyExpiresAt = this[K.expires] ?: 0L,
        spotifyUser = this[K.user] ?: "",
        karaoke = this[K.karaoke] ?: false,
        translate = this[K.translate] ?: true,
        lyricFontSize = this[K.fontSize] ?: 30f,
        lyricOffsetMs = this[K.offset] ?: 0L,
        lyricActiveColor = this[K.lyricActiveColor] ?: 0xFFF6F6F7,
        lyricFont = this[K.lyricFont] ?: "system",
        uiFont = this[K.uiFont] ?: "system",
        spicyKey = this[K.spicyKey] ?: SPICY_DEFAULT_KEY,
        themeMode = runCatching {
            ThemeMode.valueOf(this[K.themeMode] ?: ThemeMode.DARK.name)
        }.getOrDefault(ThemeMode.DARK),
        customThemes = this[K.customThemes] ?: "",
        customThemeId = this[K.customThemeId] ?: "",
        appIcon = this[K.appIcon] ?: 1,
        language = runCatching {
            AppLanguage.valueOf(this[K.language] ?: AppLanguage.RU.name)
        }.getOrDefault(AppLanguage.RU),
        coverBackdrop = this[K.coverBackdrop] ?: true,
        coverIntensity = this[K.coverIntensity] ?: 60,
        coverPages = this[K.coverPages] ?: "player,lyrics,queue,playlists,music,visualizer,settings",
        pageBackgrounds = this[K.pageBackgrounds] ?: "",
        pageBackgroundBlur = this[K.pageBackgroundBlur] ?: "",
        eqEnabled = this[K.eqEnabled] ?: false,
        eqPreset = this[K.eqPreset] ?: 0,
        eqLevels = this[K.eqLevels] ?: "",
        localFolderUri = this[K.localFolderUri] ?: "",
        localFolderName = this[K.localFolderName] ?: "",
        localFolders = decodeFolders(this[K.localFolders]).ifEmpty {
            val u = this[K.localFolderUri].orEmpty()
            if (u.isNotBlank()) listOf(LocalFolder(u, this[K.localFolderName].orEmpty())) else emptyList()
        },
        budsAutoConnect = this[K.autoConnect] ?: true,
        budsMac = this[K.budsMac] ?: "",
    )

    suspend fun setClientId(v: String) = edit { it[K.clientId] = v.trim() }
    suspend fun setTokens(access: String, refresh: String, expiresAt: Long, user: String) = edit {
        it[K.access] = access
        it[K.refresh] = refresh
        it[K.expires] = expiresAt
        it[K.user] = user
    }
    suspend fun clearSpotify() = edit {
        it.remove(K.access); it.remove(K.refresh); it.remove(K.expires); it.remove(K.user)
    }
    suspend fun setKaraoke(v: Boolean) = edit { it[K.karaoke] = v }
    suspend fun setTranslate(v: Boolean) = edit { it[K.translate] = v }
    suspend fun setFontSize(v: Float) = edit { it[K.fontSize] = v }
    suspend fun setOffset(v: Long) = edit { it[K.offset] = v }
    suspend fun setLyricActiveColor(v: Long) = edit { it[K.lyricActiveColor] = v }
    suspend fun setLyricFont(v: String) = edit { it[K.lyricFont] = v }
    suspend fun setUiFont(v: String) = edit { it[K.uiFont] = v }
    suspend fun setSpicyKey(v: String) = edit { it[K.spicyKey] = v.trim() }
    suspend fun setThemeMode(v: ThemeMode) = edit { it[K.themeMode] = v.name }
    suspend fun setCustomThemes(v: String) = edit { it[K.customThemes] = v }
    suspend fun setCustomThemeId(v: String) = edit { it[K.customThemeId] = v }
    suspend fun setAppIcon(v: Int) = edit { it[K.appIcon] = v }
    suspend fun setLanguage(v: AppLanguage) = edit { it[K.language] = v.name }
    suspend fun setCoverBackdrop(v: Boolean) = edit { it[K.coverBackdrop] = v }
    suspend fun setCoverIntensity(v: Int) = edit { it[K.coverIntensity] = v.coerceIn(0, 100) }
    suspend fun setCoverPages(v: String) = edit { it[K.coverPages] = v }
    suspend fun setPageBackgrounds(v: String) = edit { it[K.pageBackgrounds] = v }
    suspend fun setPageBackgroundBlur(v: String) = edit { it[K.pageBackgroundBlur] = v }
    suspend fun setEq(enabled: Boolean, preset: Int, levels: String) = edit {
        it[K.eqEnabled] = enabled
        it[K.eqPreset] = preset
        it[K.eqLevels] = levels
    }
    suspend fun setLocalFolder(uri: String, name: String) = edit {
        it[K.localFolderUri] = uri
        it[K.localFolderName] = name
    }
    suspend fun setLocalFolders(folders: List<LocalFolder>) = edit {
        it[K.localFolders] = encodeFolders(folders)
        it[K.localFolderUri] = folders.firstOrNull()?.uri.orEmpty()
        it[K.localFolderName] = folders.firstOrNull()?.name.orEmpty()
    }
    suspend fun clearLocalFolder() = edit {
        it.remove(K.localFolderUri); it.remove(K.localFolderName); it.remove(K.localFolders)
    }
    suspend fun setAutoConnect(v: Boolean) = edit { it[K.autoConnect] = v }
    suspend fun setBudsMac(v: String) = edit { it[K.budsMac] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    companion object {
        /**
         * Injected from secrets.properties at build time. The public repo contains
         * no key; the private release APK is built with the local secret.
         */
        val SPICY_DEFAULT_KEY: String = app.onyx.BuildConfig.SPICY_KEY
    }
}

private const val FOLDER_RECORD = '\u001E'
private const val FOLDER_FIELD = '\u001F'

private fun encodeFolders(folders: List<LocalFolder>): String =
    folders.joinToString(FOLDER_RECORD.toString()) { f ->
        f.uri + FOLDER_FIELD + f.name
    }

private fun decodeFolders(raw: String?): List<LocalFolder> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(FOLDER_RECORD).mapNotNull { record ->
        if (record.isBlank()) return@mapNotNull null
        val parts = record.split(FOLDER_FIELD, limit = 2)
        val uri = parts.getOrNull(0).orEmpty()
        if (uri.isBlank()) null else LocalFolder(uri, parts.getOrNull(1).orEmpty())
    }
}
