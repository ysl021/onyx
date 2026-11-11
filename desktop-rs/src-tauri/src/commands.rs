use crate::library::{Library, Track};
use crate::player::{PlayerHandle, PlayerState};
use crate::settings::{self, Settings};
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
