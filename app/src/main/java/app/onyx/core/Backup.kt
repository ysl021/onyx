package app.onyx.core

import android.content.Context
import android.net.Uri
import app.onyx.music.LocalPlaylist
import app.onyx.music.PlaylistFolder
import app.onyx.music.PlaylistsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * One JSON file with everything the phone owns: settings, likes, playlists and
 * the local-music folder list. Used to move data across the one-time signing
 * change (debug key -> release key) without losing anything.
 *
 * The file contains Spotify/Discord tokens, so the UI warns the user to keep it
 * private.
 */
@Serializable
data class OnyxBackup(
    val schema: Int = SCHEMA,
    val versionName: String = "",
    val versionCode: Int = 0,
    val createdAt: Long = 0L,
    val prefs: PrefsData = PrefsData(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val playlistFolders: List<PlaylistFolder> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
    }
}

object BackupCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(backup: OnyxBackup): String = json.encodeToString(backup)

    fun decode(text: String): OnyxBackup = json.decodeFromString(text)
}

suspend fun writeBackup(context: Context, uri: Uri, backup: OnyxBackup) =
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(BackupCodec.encode(backup).toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Cannot open the selected file")
    }

suspend fun readBackup(context: Context, uri: Uri): OnyxBackup =
    withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: throw IOException("Cannot read the selected file")
        BackupCodec.decode(text)
    }

fun buildBackup(
    prefs: PrefsData,
    playlists: PlaylistsState,
    versionName: String,
    versionCode: Int,
): OnyxBackup = OnyxBackup(
    schema = OnyxBackup.SCHEMA,
    versionName = versionName,
    versionCode = versionCode,
    createdAt = System.currentTimeMillis(),
    prefs = prefs,
    playlists = playlists.playlists,
    playlistFolders = playlists.folders,
)
