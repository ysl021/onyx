mod commands;
mod discord;
mod hub;
mod library;
mod lyrics;
mod player;
mod playlists;
mod settings;
mod update;
mod youtube;

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_opener::init())
        .manage(player::PlayerHandle::new())
        .manage(std::sync::Arc::new(library::Library::new()))
        .manage(std::sync::Arc::new(playlists::PlaylistStore::new()))
        .invoke_handler(tauri::generate_handler![
            commands::app_version,
            commands::load_settings,
            commands::save_settings,
            commands::player_state,
            commands::player_toggle,
            commands::player_load,
            commands::player_seek,
            commands::player_volume,
            commands::player_pause,
            commands::library_tracks,
            commands::library_scan,
            commands::track_cover,
            commands::playlists_load,
            commands::playlist_create,
            commands::playlist_rename,
            commands::playlist_delete,
            commands::playlist_add_tracks,
            commands::playlist_remove_track,
            commands::lyrics_fetch,
            commands::youtube_search,
            commands::youtube_play,
        ])
        .run(tauri::generate_context!())
        .expect("error while running ONYX");
}
