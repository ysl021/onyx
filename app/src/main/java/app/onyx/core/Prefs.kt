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

private val Context.dataStore by preferencesDataStore("onyx")

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
    val spicyKey: String = Prefs.SPICY_DEFAULT_KEY,
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
        val spicyKey = stringPreferencesKey("spicy_key")
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
        spicyKey = this[K.spicyKey] ?: SPICY_DEFAULT_KEY,
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
    suspend fun setSpicyKey(v: String) = edit { it[K.spicyKey] = v.trim() }
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
