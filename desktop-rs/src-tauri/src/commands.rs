use crate::player::{PlayerHandle, PlayerState};
use crate::settings::{self, Settings};
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
pub fn player_toggle(player: State<'_, PlayerHandle>) -> PlayerState {
    player.toggle();
    player.state()
}

#[tauri::command]
pub fn player_load(player: State<'_, PlayerHandle>, path: String) -> Result<(), String> {
    player.load(&path)
}
