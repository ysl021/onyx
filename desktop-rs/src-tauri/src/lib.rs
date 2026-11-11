mod commands;
mod discord;
mod hub;
mod library;
mod player;
mod settings;
mod update;

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_opener::init())
        .manage(player::PlayerHandle::new())
        .manage(std::sync::Arc::new(library::Library::new()))
        .invoke_handler(tauri::generate_handler![
            commands::app_version,
            commands::load_settings,
            commands::save_settings,
            commands::player_state,
            commands::player_toggle,
            commands::player_load,
            commands::player_seek,
            commands::player_volume,
            commands::library_tracks,
            commands::library_scan,
            commands::track_cover,
        ])
        .run(tauri::generate_context!())
        .expect("error while running ONYX");
}
