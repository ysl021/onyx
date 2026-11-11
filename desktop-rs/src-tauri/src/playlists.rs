use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// Metadata for a streaming (YouTube) track saved without downloading it.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct StreamTrack {
    /// `yt:<videoId>`
    pub id: String,
    pub video_id: String,
    pub title: String,
    pub artist: String,
    pub duration_ms: u64,
    pub thumbnail: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Playlist {
    pub id: String,
    pub name: String,
    pub description: String,
    pub pinned: bool,
    /// Local file paths and `yt:<videoId>` ids, in playlist order.
    pub track_ids: Vec<String>,
    /// Metadata for the `yt:` entries.
    pub streams: Vec<StreamTrack>,
    pub created_at: u64,
}

impl Default for Playlist {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            description: String::new(),
            pinned: false,
            track_ids: Vec::new(),
            streams: Vec::new(),
            created_at: 0,
        }
    }
}

/// A track coming from the phone through the LAN hub.
#[derive(Debug, Clone)]
pub struct MergeTrack {
    pub id: String,
    pub stream: Option<StreamTrack>,
}

#[derive(Debug, Clone)]
pub struct MergePlaylist {
    pub id: String,
    pub name: String,
    pub description: String,
    pub pinned: bool,
    pub tracks: Vec<MergeTrack>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PlaylistTrackInput {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    pub thumbnail: Option<String>,
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
            name: clean_name(name),
            description: String::new(),
            pinned: false,
            track_ids: Vec::new(),
            streams: Vec::new(),
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

    pub fn update(
        &self,
        id: &str,
        name: &str,
        description: &str,
        pinned: bool,
    ) -> Vec<Playlist> {
        self.mutate(|playlists| {
            if let Some(playlist) = playlists.iter_mut().find(|item| item.id == id) {
                if !name.trim().is_empty() {
                    playlist.name = name.trim().to_string();
                }
                playlist.description = description.trim().to_string();
                playlist.pinned = pinned;
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

    /// Adds the current track, keeping YouTube metadata next to its id.
    pub fn add_track(&self, id: &str, track: PlaylistTrackInput) -> Vec<Playlist> {
        self.mutate(|playlists| {
            if let Some(playlist) = playlists.iter_mut().find(|item| item.id == id) {
                if playlist.track_ids.contains(&track.id) {
                    return;
                }
                let video_id = track.id.strip_prefix("yt:").map(str::to_string);
                playlist.track_ids.push(track.id.clone());
                if let Some(video_id) = video_id {
                    playlist.streams.retain(|stream| stream.id != track.id);
                    playlist.streams.push(StreamTrack {
                        id: track.id,
                        video_id,
                        title: track.title,
                        artist: track.artist,
                        duration_ms: track.duration_ms,
                        thumbnail: track.thumbnail,
                    });
                }
            }
        })
    }

    pub fn remove_track(&self, id: &str, track_id: &str) -> Vec<Playlist> {
        self.mutate(|playlists| {
            if let Some(playlist) = playlists.iter_mut().find(|item| item.id == id) {
                playlist.track_ids.retain(|item| item != track_id);
                playlist.streams.retain(|stream| stream.id != track_id);
            }
        })
    }

    /// Additive merge used by the LAN hub: playlists are matched by name and
    /// only receive the tracks they are missing. Returns (playlists, tracks).
    pub fn merge(&self, incoming: Vec<MergePlaylist>) -> (usize, usize) {
        let mut playlists_added = 0usize;
        let mut tracks_added = 0usize;
        self.mutate(|playlists| {
            for entry in incoming {
                if entry.name.trim().is_empty() {
                    continue;
                }
                let position = playlists
                    .iter()
                    .position(|item| item.name.eq_ignore_ascii_case(entry.name.trim()));
                let index = match position {
                    Some(index) => index,
                    None => {
                        let mut id = entry.id.clone();
                        if id.trim().is_empty()
                            || playlists.iter().any(|item| item.id == id)
                        {
                            id = format!("{:x}", now_nanos());
                        }
                        playlists.push(Playlist {
                            id,
                            name: entry.name.trim().to_string(),
                            description: entry.description.clone(),
                            pinned: entry.pinned,
                            track_ids: Vec::new(),
                            streams: Vec::new(),
                            created_at: now_ms(),
                        });
                        playlists_added += 1;
                        playlists.len() - 1
                    }
                };
                let playlist = &mut playlists[index];
                if !entry.description.trim().is_empty() {
                    playlist.description = entry.description.trim().to_string();
                }
                if entry.pinned {
                    playlist.pinned = true;
                }
                for track in entry.tracks {
                    if track.id.trim().is_empty() || playlist.track_ids.contains(&track.id) {
                        continue;
                    }
                    playlist.track_ids.push(track.id.clone());
                    if let Some(stream) = track.stream {
                        playlist.streams.retain(|item| item.id != stream.id);
                        playlist.streams.push(stream);
                    }
                    tracks_added += 1;
                }
            }
        });
        (playlists_added, tracks_added)
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

fn clean_name(name: &str) -> String {
    if name.trim().is_empty() {
        "Без названия".into()
    } else {
        name.trim().to_string()
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
