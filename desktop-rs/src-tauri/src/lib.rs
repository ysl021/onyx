mod commands;
mod covers;
mod discord;
mod favorites;
mod hub;
mod library;
mod lyrics;
mod player;
mod playlists;
mod settings;
mod update;
mod visualizer;
mod youtube;

use std::sync::{Arc, Mutex};

pub fn run() {
    let library = Arc::new(library::Library::new());
    let playlists = Arc::new(playlists::PlaylistStore::new());
    let favorites = Arc::new(favorites::Favorites::new());
    let remote = Arc::new(Mutex::new(None));
    let player = player::PlayerHandle::new();
    let hub = hub::Hub::new(
        Arc::clone(&library),
        Arc::clone(&playlists),
        Arc::clone(&favorites),
        Arc::clone(&remote),
    );
    let downloads = Arc::new(youtube::DownloadQueue::new(Arc::clone(&library)));

    let setup_player = player.clone();
    let setup_remote = Arc::clone(&remote);
    let setup_library = Arc::clone(&library);
    let setup_hub = hub.clone();

    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .manage(player)
        .manage(Arc::clone(&library))
        .manage(playlists)
        .manage(favorites)
        .manage(visualizer::Visualizer::new())
        .manage(downloads)
        .manage(hub)
        .setup(move |_app| {
            let config = settings::load();
            let name = if config.hub_name.trim().is_empty() {
                setup_hub.status().name
            } else {
                config.hub_name.clone()
            };
            let _ = setup_hub.start(config.hub_port, name);
            discord::spawn_presence_worker(setup_player, setup_remote);
            // Scan the saved folders on launch so the hub has a library even
            // before the user opens the "Музыка" tab.
            std::thread::spawn(move || {
                let folders = settings::load().music_folders;
                setup_library.scan(&folders);
            });
            Ok(())
        })
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
            commands::player_set_audio_device,
            commands::audio_devices,
            commands::library_tracks,
            commands::library_scan,
            commands::track_cover,
            commands::track_update_tags,
            commands::missing_folders,
            commands::playlists_load,
            commands::playlist_create,
            commands::playlist_rename,
            commands::playlist_update,
            commands::playlist_delete,
            commands::playlist_add_tracks,
            commands::playlist_add_track,
            commands::playlist_remove_track,
            commands::favorites_load,
            commands::favorites_toggle,
            commands::lyrics_fetch,
            commands::youtube_search,
            commands::youtube_play,
            commands::youtube_proxy_status,
            commands::youtube_proxy_check,
            commands::youtube_download_add,
            commands::youtube_downloads,
            commands::youtube_download_cancel,
            commands::youtube_download_remove,
            commands::youtube_download_clear,
            commands::visualizer_prepare,
            commands::visualizer_stop,
            commands::visualizer_bands,
            commands::discord_test,
            commands::open_sound_settings,
            commands::export_data,
            commands::import_data,
            commands::open_downloads_folder,
            commands::cover_lookup,
            commands::hub_status,
            commands::hub_start,
            commands::hub_stop,
            commands::hub_push_to_phone,
            commands::hub_request_phone,
            commands::hub_request_index,
            commands::hub_request_files,
            commands::hub_forget_devices,
            commands::hub_regenerate_code,
            commands::hub_sync_state,
        ])
        .run(tauri::generate_context!())
        .expect("error while running ONYX");
}
