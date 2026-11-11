use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Playlist {
    pub id: String,
    pub name: String,
    pub track_ids: Vec<String>,
    pub created_at: u64,
}

impl Default for Playlist {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            track_ids: Vec::new(),
            created_at: 0,
        }
    }
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(default)]
struct PlaylistsFile {
    playlists: Vec<Playlist>,
}

/// Local playlists for the Rust client. Kept in a separate file so the
/// Kotlin build and the phone sync file are never overwritten.
pub struct PlaylistStore {
    file: PathBuf,
    state: Mutex<Vec<Playlist>>,
}

impl PlaylistStore {
    pub fn new() -> Self {
        let file = path();
        let state = load(&file);
        Self {
            file,
            state: Mutex::new(state),
        }
    }

    pub fn list(&self) -> Vec<Playlist> {
        self.state
            .lock()
            .map(|state| state.clone())
            .unwrap_or_default()
    }

    pub fn create(&self, name: &str) -> Vec<Playlist> {
        let playlist = Playlist {
            id: format!("{:x}", now_nanos()),
            name: if name.trim().is_empty() {
                "Без названия".into()
            } else {
                name.trim().to_string()
            },
            track_ids: Vec::new(),
            created_at: now_ms(),
        };
        self.mutate(|playlists| playlists.push(playlist))
    }

    pub fn rename(&self, id: &str, name: &str) -> Vec<Playlist> {
        let name = name.trim();
        if name.is_empty() {
            return self.list();
        }
        self.mutate(|playlists| {
            if let Some(playlist) = playlists.iter_mut().find(|item| item.id == id) {
                playlist.name = name.to_string();
            }
        })
    }

    pub fn delete(&self, id: &str) -> Vec<Playlist> {
        self.mutate(|playlists| playlists.retain(|item| item.id != id))
    }

    pub fn add_tracks(&self, id: &str, track_ids: &[String]) -> Vec<Playlist> {
        self.mutate(|playlists| {
            if let Some(playlist) = playlists.iter_mut().find(|item| item.id == id) {
                for track_id in track_ids {
                    if !playlist.track_ids.contains(track_id) {
                        playlist.track_ids.push(track_id.clone());
                    }
                }
            }
        })
    }

    pub fn remove_track(&self, id: &str, track_id: &str) -> Vec<Playlist> {
        self.mutate(|playlists| {
            if let Some(playlist) = playlists.iter_mut().find(|item| item.id == id) {
                playlist.track_ids.retain(|item| item != track_id);
            }
        })
    }

    fn mutate(&self, change: impl FnOnce(&mut Vec<Playlist>)) -> Vec<Playlist> {
        let mut state = self.state.lock().expect("playlist mutex poisoned");
        change(&mut state);
        let snapshot = state.clone();
        drop(state);
        save(&self.file, &snapshot);
        snapshot
    }
}

fn path() -> PathBuf {
    let base = std::env::var("APPDATA").unwrap_or_else(|_| ".".into());
    PathBuf::from(base)
        .join("ONYX")
        .join("rust-playlists.json")
}

fn load(file: &PathBuf) -> Vec<Playlist> {
    fs::read_to_string(file)
        .ok()
        .and_then(|text| serde_json::from_str::<PlaylistsFile>(&text).ok())
        .map(|data| data.playlists)
        .unwrap_or_default()
}

fn save(file: &PathBuf, playlists: &[Playlist]) {
    if let Some(parent) = file.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let data = PlaylistsFile {
        playlists: playlists.to_vec(),
    };
    if let Ok(text) = serde_json::to_string_pretty(&data) {
        let _ = fs::write(file, text);
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

fn now_nanos() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0)
}
