use crate::library::{Library, Track};
use crate::lyrics::Lyrics;
use crate::player::{PlayerHandle, PlayerState};
use crate::playlists::{Playlist, PlaylistStore};
use crate::settings::{self, Settings};
use crate::youtube::Video;
use std::sync::Arc;
use tauri::State;

#[tauri::command]
pub fn app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[tauri::command]
pub fn load_settings() -> Settings {
    settings::load()
}

#[tauri::command]
pub fn save_settings(settings: Settings) -> Result<(), String> {
    settings::save(&settings)
}

#[tauri::command]
pub fn player_state(player: State<'_, PlayerHandle>) -> PlayerState {
    player.state()
}

#[tauri::command]
pub fn player_toggle(player: State<'_, PlayerHandle>) -> Result<PlayerState, String> {
    player.toggle()?;
    Ok(player.state())
}

#[tauri::command]
pub fn player_load(player: State<'_, PlayerHandle>, path: String) -> Result<(), String> {
    player.load(&path)
}

#[tauri::command]
pub fn player_seek(player: State<'_, PlayerHandle>, seconds: f64) -> Result<(), String> {
    player.seek(seconds)
}

#[tauri::command]
pub fn player_volume(player: State<'_, PlayerHandle>, volume: u8) -> Result<(), String> {
    player.set_volume(volume)
}

#[tauri::command]
pub fn player_pause(player: State<'_, PlayerHandle>) -> Result<(), String> {
    player.pause()
}

#[tauri::command]
pub fn library_tracks(library: State<'_, Arc<Library>>) -> Vec<Track> {
    library.tracks()
}

#[tauri::command]
pub async fn library_scan(
    library: State<'_, Arc<Library>>,
    folders: Vec<String>,
) -> Result<Vec<Track>, String> {
    let library = Arc::clone(library.inner());
    tauri::async_runtime::spawn_blocking(move || library.scan(&folders))
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub fn track_cover(library: State<'_, Arc<Library>>, path: String) -> Option<String> {
    library.cover(&path)
}

#[tauri::command]
pub fn playlists_load(store: State<'_, Arc<PlaylistStore>>) -> Vec<Playlist> {
    store.list()
}

#[tauri::command]
pub fn playlist_create(
    store: State<'_, Arc<PlaylistStore>>,
    name: String,
) -> Vec<Playlist> {
    store.create(&name)
}

#[tauri::command]
pub fn playlist_rename(
    store: State<'_, Arc<PlaylistStore>>,
    id: String,
    name: String,
) -> Vec<Playlist> {
    store.rename(&id, &name)
}

#[tauri::command]
pub fn playlist_delete(
    store: State<'_, Arc<PlaylistStore>>,
    id: String,
) -> Vec<Playlist> {
    store.delete(&id)
}

#[tauri::command]
pub fn playlist_add_tracks(
    store: State<'_, Arc<PlaylistStore>>,
    id: String,
    track_ids: Vec<String>,
) -> Vec<Playlist> {
    store.add_tracks(&id, &track_ids)
}

#[tauri::command]
pub fn playlist_remove_track(
    store: State<'_, Arc<PlaylistStore>>,
    id: String,
    track_id: String,
) -> Vec<Playlist> {
    store.remove_track(&id, &track_id)
}

#[tauri::command]
pub async fn lyrics_fetch(
    artist: String,
    title: String,
    album: String,
    duration_ms: u64,
) -> Option<Lyrics> {
    crate::lyrics::fetch(&artist, &title, &album, duration_ms).await
}

#[tauri::command]
pub async fn youtube_search(query: String) -> Result<Vec<Video>, String> {
    crate::youtube::search(&query).await
}

#[tauri::command]
pub async fn youtube_play(
    player: State<'_, PlayerHandle>,
    id: String,
) -> Result<(), String> {
    let proxy = crate::youtube::active_proxy().ok_or(
        "Включи VPN/прокси для YouTube (Happ/Clash на 127.0.0.1:20808 или 7890).",
    )?;
    let url = crate::youtube::resolve(&id, &proxy).await?;
    player.load_with_proxy(&url, Some(&proxy))
}
