use crate::favorites::{Favorite, Favorites};
use crate::hub::{Hub, HubStatus, SyncState};
use crate::library::{Library, Track};
use crate::lyrics::Lyrics;
use crate::player::{AudioDevice, PlayerHandle, PlayerState};
use crate::playlists::{MergePlaylist, MergeTrack, Playlist, PlaylistStore, PlaylistTrackInput};
use crate::settings::{self, Settings};
use crate::visualizer::Visualizer;
use crate::youtube::{DownloadJob, DownloadQueue, DownloadSection, Video};
use serde::Serialize;
use serde_json::{json, Value};
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
pub async fn player_state(player: State<'_, PlayerHandle>) -> Result<PlayerState, String> {
    let player = player.inner().clone();
    tauri::async_runtime::spawn_blocking(move || player.state())
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn player_toggle(player: State<'_, PlayerHandle>) -> Result<PlayerState, String> {
    let player = player.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        player.toggle()?;
        Ok(player.state())
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn player_load(
    player: State<'_, PlayerHandle>,
    path: String,
) -> Result<(), String> {
    let player = player.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let config = settings::load();
        player.load(&path)?;
        if !config.audio_device.trim().is_empty() && config.audio_device != "auto" {
            let _ = player.set_audio_device(&config.audio_device);
        }
        if config.eq_enabled {
            let _ = player.set_eq(true, &config.eq_bands);
        }
        Ok(())
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn player_seek(
    player: State<'_, PlayerHandle>,
    seconds: f64,
) -> Result<(), String> {
    let player = player.inner().clone();
    tauri::async_runtime::spawn_blocking(move || player.seek(seconds))
        .await
        .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn player_volume(
    player: State<'_, PlayerHandle>,
    volume: u8,
) -> Result<(), String> {
    let player = player.inner().clone();
    tauri::async_runtime::spawn_blocking(move || player.set_volume(volume))
        .await
        .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn player_pause(player: State<'_, PlayerHandle>) -> Result<(), String> {
    let player = player.inner().clone();
    tauri::async_runtime::spawn_blocking(move || player.pause())
        .await
        .map_err(|error| error.to_string())?
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
pub fn missing_folders(folders: Vec<String>) -> Vec<String> {
    folders
        .into_iter()
        .filter(|folder| !std::path::Path::new(folder).exists())
        .collect()
}

#[tauri::command]
pub async fn track_cover(
    library: State<'_, Arc<Library>>,
    path: String,
) -> Result<Option<String>, String> {
    let library = Arc::clone(library.inner());
    tauri::async_runtime::spawn_blocking(move || library.cover(&path))
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn track_update_tags(
    library: State<'_, Arc<Library>>,
    path: String,
    title: String,
    artist: String,
    album: String,
) -> Result<(), String> {
    let library = Arc::clone(library.inner());
    tauri::async_runtime::spawn_blocking(move || {
        library.update_tags(&path, &title, &artist, &album)
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn tracks_update_album(
    library: State<'_, Arc<Library>>,
    paths: Vec<String>,
    album: String,
) -> Result<usize, String> {
    let library = Arc::clone(library.inner());
    tauri::async_runtime::spawn_blocking(move || library.update_album_bulk(&paths, &album))
        .await
        .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn tracks_update_artist(
    library: State<'_, Arc<Library>>,
    paths: Vec<String>,
    artist: String,
) -> Result<usize, String> {
    let library = Arc::clone(library.inner());
    tauri::async_runtime::spawn_blocking(move || library.update_artist_bulk(&paths, &artist))
        .await
        .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn tracks_delete(
    library: State<'_, Arc<Library>>,
    paths: Vec<String>,
) -> Result<usize, String> {
    let library = Arc::clone(library.inner());
    tauri::async_runtime::spawn_blocking(move || library.delete_tracks(&paths))
        .await
        .map_err(|error| error.to_string())?
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
pub fn playlist_update(
    store: State<'_, Arc<PlaylistStore>>,
    id: String,
    name: String,
    description: String,
    pinned: bool,
) -> Vec<Playlist> {
    store.update(&id, &name, &description, pinned)
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
pub async fn youtube_search(
    query: String,
    music_only: Option<bool>,
) -> Result<Vec<Video>, String> {
    crate::youtube_router::search(&query, music_only.unwrap_or(true)).await
}

#[tauri::command]
pub async fn youtube_home(seed: Option<String>) -> Result<Vec<Video>, String> {
    crate::youtube_router::home(seed).await
}

#[tauri::command]
pub async fn youtube_related(
    video_id: String,
    title: String,
    artist: String,
) -> Result<Vec<Video>, String> {
    crate::youtube_router::related(&video_id, &title, &artist).await
}

#[tauri::command]
pub fn youtube_route_status() -> crate::youtube_router::RouteStatus {
    crate::youtube_router::status()
}

#[tauri::command]
pub async fn youtube_route_check() -> crate::youtube_router::RouteStatus {
    crate::youtube_router::check().await
}

#[tauri::command]
pub async fn youtube_play(
    player: State<'_, PlayerHandle>,
    visualizer: State<'_, Visualizer>,
    id: String,
    title: Option<String>,
    artist: Option<String>,
    duration_ms: Option<u64>,
) -> Result<(), String> {
    let stream = crate::youtube_router::stream(
        &id,
        title.as_deref().unwrap_or(""),
        artist.as_deref().unwrap_or(""),
    )
    .await?;
    player.load_with_proxy(&stream.url, stream.proxy.as_deref())?;
    player.set_metadata(
        title.as_deref().unwrap_or(""),
        artist.as_deref().unwrap_or(""),
        "",
        duration_ms.unwrap_or(stream.duration_ms),
    );
    let _ = visualizer.prepare(&stream.url, stream.proxy.as_deref());
    Ok(())
}

#[tauri::command]
pub fn favorites_load(favorites: State<'_, Arc<Favorites>>) -> Vec<Favorite> {
    favorites.list()
}

#[tauri::command]
pub fn favorites_toggle(
    favorites: State<'_, Arc<Favorites>>,
    track: Favorite,
) -> Vec<Favorite> {
    favorites.toggle(track)
}

#[tauri::command]
pub fn playlist_add_track(
    store: State<'_, Arc<PlaylistStore>>,
    id: String,
    track: PlaylistTrackInput,
) -> Vec<Playlist> {
    store.add_track(&id, track)
}

#[tauri::command]
pub fn visualizer_prepare(
    visualizer: State<'_, Visualizer>,
    path: String,
    proxy: Option<String>,
) -> Result<(), String> {
    visualizer.prepare(&path, proxy.as_deref())
}

#[tauri::command]
pub fn visualizer_stop(visualizer: State<'_, Visualizer>) {
    visualizer.stop();
}

#[tauri::command]
pub async fn visualizer_bands(
    visualizer: State<'_, Visualizer>,
    position_ms: u64,
    playing: bool,
) -> Result<Vec<f32>, String> {
    let visualizer = visualizer.inner().clone();
    tauri::async_runtime::spawn_blocking(move || visualizer.bands(position_ms, playing))
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn audio_devices() -> Vec<AudioDevice> {
    tauri::async_runtime::spawn_blocking(crate::player::audio_devices)
        .await
        .unwrap_or_default()
}

#[tauri::command]
pub fn player_set_audio_device(
    player: State<'_, PlayerHandle>,
    device: String,
) -> Result<(), String> {
    player.set_audio_device(&device)
}

#[tauri::command]
pub fn player_set_eq(
    player: State<'_, PlayerHandle>,
    enabled: bool,
    bands: Vec<f32>,
) -> Result<(), String> {
    player.set_eq(enabled, &bands)
}

#[tauri::command]
pub fn discord_status() -> bool {
    crate::discord::is_connected()
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscordState {
    pub connected: bool,
    pub error: String,
}

#[tauri::command]
pub fn discord_state() -> DiscordState {
    DiscordState {
        connected: crate::discord::is_connected(),
        error: crate::discord::last_error(),
    }
}

#[tauri::command]
pub async fn discord_test() -> bool {
    crate::discord::is_connected()
}

#[tauri::command]
pub fn export_data(
    path: String,
    playlists: State<'_, Arc<PlaylistStore>>,
    favorites: State<'_, Arc<Favorites>>,
) -> Result<(), String> {
    let data = json!({
        "app": "onyx",
        "version": env!("CARGO_PKG_VERSION"),
        "settings": settings::load(),
        "playlists": playlists.list(),
        "favorites": favorites.list(),
    });
    let text = serde_json::to_string_pretty(&data).map_err(|error| error.to_string())?;
    std::fs::write(path, text).map_err(|error| error.to_string())
}

#[tauri::command]
pub fn import_data(
    path: String,
    playlists: State<'_, Arc<PlaylistStore>>,
    favorites: State<'_, Arc<Favorites>>,
) -> Result<String, String> {
    let text = std::fs::read_to_string(path).map_err(|error| error.to_string())?;
    let value: Value = serde_json::from_str(&text).map_err(|error| error.to_string())?;
    let mut summary = Vec::new();

    if let Some(settings_value) = value.get("settings") {
        if let Ok(next) = serde_json::from_value::<Settings>(settings_value.clone()) {
            settings::save(&next)?;
            summary.push("настройки".to_string());
        }
    }

    if let Some(items) = value.get("playlists").and_then(Value::as_array) {
        let mut incoming = Vec::new();
        for item in items {
            let Ok(playlist) = serde_json::from_value::<Playlist>(item.clone()) else {
                continue;
            };
            let tracks = playlist
                .track_ids
                .iter()
                .map(|id| MergeTrack {
                    id: id.clone(),
                    stream: playlist
                        .streams
                        .iter()
                        .find(|stream| stream.id == *id)
                        .cloned(),
                })
                .collect();
            incoming.push(MergePlaylist {
                id: playlist.id,
                name: playlist.name,
                description: playlist.description,
                pinned: playlist.pinned,
                tracks,
            });
        }
        let (added_playlists, added_tracks) = playlists.merge(incoming);
        summary.push(format!("+{added_playlists} плейлистов, +{added_tracks} треков"));
    }

    if let Some(items) = value.get("favorites").and_then(Value::as_array) {
        let mut incoming = Vec::new();
        for item in items {
            if let Ok(favorite) = serde_json::from_value::<Favorite>(item.clone()) {
                incoming.push(favorite);
            }
        }
        let added = favorites.merge(incoming);
        summary.push(format!("+{added} любимых"));
    }

    if summary.is_empty() {
        Ok("В файле нет данных ONYX".into())
    } else {
        Ok(summary.join(", "))
    }
}

#[tauri::command]
pub fn open_sound_settings() -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("control")
            .arg("mmsys.cpl")
            .spawn()
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

#[tauri::command]
pub async fn cover_lookup(
    title: String,
    artist: String,
    album: String,
    path: Option<String>,
) -> Option<String> {
    tauri::async_runtime::spawn_blocking(move || {
        crate::covers::resolve(&title, &artist, &album, path.as_deref())
    })
    .await
    .ok()
    .flatten()
}

#[tauri::command]
pub fn open_downloads_folder() -> Result<(), String> {
    let settings = settings::load();
    let folder = settings
        .music_folders
        .iter()
        .find(|folder| !folder.trim().is_empty())
        .cloned()
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| {
            let base = std::env::var("APPDATA").unwrap_or_else(|_| ".".into());
            std::path::PathBuf::from(base).join("ONYX").join("downloads")
        });
    std::fs::create_dir_all(&folder).map_err(|error| error.to_string())?;
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer")
            .arg(&folder)
            .spawn()
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

#[tauri::command]
pub fn hub_status(hub: State<'_, Hub>) -> HubStatus {
    hub.status()
}

#[tauri::command]
pub fn hub_start(
    hub: State<'_, Hub>,
    port: u16,
    name: String,
) -> Result<HubStatus, String> {
    let name = if name.trim().is_empty() {
        hub.status().name
    } else {
        name.trim().to_string()
    };
    hub.start(port, name)?;
    Ok(hub.status())
}

#[tauri::command]
pub fn hub_stop(hub: State<'_, Hub>) -> HubStatus {
    hub.stop();
    hub.status()
}

#[tauri::command]
pub fn hub_push_to_phone(hub: State<'_, Hub>) -> Result<HubStatus, String> {
    hub.push_to_phone()?;
    Ok(hub.status())
}

#[tauri::command]
pub fn hub_request_phone(hub: State<'_, Hub>) -> HubStatus {
    hub.request_phone();
    hub.status()
}

#[tauri::command]
pub fn hub_request_index(hub: State<'_, Hub>) -> HubStatus {
    hub.request_library_index();
    hub.status()
}

#[tauri::command]
pub fn hub_request_files(hub: State<'_, Hub>, ids: Vec<String>) -> HubStatus {
    hub.request_library_files(ids);
    hub.status()
}

#[tauri::command]
pub fn hub_forget_devices(hub: State<'_, Hub>) -> HubStatus {
    hub.forget_devices();
    hub.status()
}

#[tauri::command]
pub fn hub_regenerate_code(hub: State<'_, Hub>) -> HubStatus {
    hub.regenerate_code();
    hub.status()
}

#[tauri::command]
pub fn hub_sync_state(hub: State<'_, Hub>) -> SyncState {
    hub.sync_state()
}

#[tauri::command]
pub async fn youtube_playlist(url: String) -> Result<Vec<Video>, String> {
    crate::youtube::playlist(&url).await
}

#[tauri::command]
pub async fn youtube_proxy_status() -> Option<String> {
    tauri::async_runtime::spawn_blocking(|| {
        crate::youtube::invalidate_proxy();
        crate::youtube::active_proxy()
    })
    .await
    .ok()
    .flatten()
}

#[tauri::command]
pub async fn youtube_proxy_check(proxy: String) -> bool {
    crate::youtube::proxy_check(&proxy).await
}

#[tauri::command]
pub fn youtube_download_add(
    queue: State<'_, Arc<DownloadQueue>>,
    video_id: String,
    title: String,
    artist: String,
    sections: Vec<DownloadSection>,
) -> Vec<DownloadJob> {
    queue.add(video_id, title, artist, sections)
}

#[tauri::command]
pub fn youtube_downloads(queue: State<'_, Arc<DownloadQueue>>) -> Vec<DownloadJob> {
    queue.list()
}

#[tauri::command]
pub fn youtube_download_cancel(
    queue: State<'_, Arc<DownloadQueue>>,
    id: String,
) -> Vec<DownloadJob> {
    queue.cancel(&id)
}

#[tauri::command]
pub fn youtube_download_remove(
    queue: State<'_, Arc<DownloadQueue>>,
    id: String,
) -> Vec<DownloadJob> {
    queue.remove(&id)
}

#[tauri::command]
pub fn youtube_download_clear(queue: State<'_, Arc<DownloadQueue>>) -> Vec<DownloadJob> {
    queue.clear_finished()
}
