use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Favorite {
    /// Local file path, `yt:<videoId>` or a phone-only id.
    pub id: String,
    pub video_id: Option<String>,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    pub artwork: Option<String>,
    pub at: u64,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(default)]
struct FavoritesFile {
    tracks: Vec<Favorite>,
}

/// Liked tracks. Kept in `rust-favorites.json` so the Kotlin build and the
/// phone sync files are never touched.
pub struct Favorites {
    file: PathBuf,
    state: Mutex<Vec<Favorite>>,
}

impl Favorites {
    pub fn new() -> Self {
        let file = path();
        let state = load(&file);
        Self {
            file,
            state: Mutex::new(state),
        }
    }

    pub fn list(&self) -> Vec<Favorite> {
        self.state
            .lock()
            .map(|state| state.clone())
            .unwrap_or_default()
    }

    /// Adds or removes a track. The newest like goes to the top.
    pub fn toggle(&self, track: Favorite) -> Vec<Favorite> {
        if track.id.trim().is_empty() {
            return self.list();
        }
        self.mutate(|state| {
            if let Some(index) = state.iter().position(|item| item.id == track.id) {
                state.remove(index);
            } else {
                state.insert(
                    0,
                    Favorite {
                        at: now_ms(),
                        ..track
                    },
                );
            }
        })
    }

    /// Additive merge used by the LAN hub. Returns the number of new tracks.
    pub fn merge(&self, incoming: Vec<Favorite>) -> usize {
        let mut added = 0usize;
        self.mutate(|state| {
            for track in incoming {
                if track.id.trim().is_empty() {
                    continue;
                }
                let duplicate = state.iter().any(|item| {
                    item.id == track.id
                        || (!track.title.trim().is_empty()
                            && item.title.eq_ignore_ascii_case(&track.title)
                            && item.artist.eq_ignore_ascii_case(&track.artist)
                            && item.duration_ms / 1000 == track.duration_ms / 1000)
                });
                if !duplicate {
                    state.push(track);
                    added += 1;
                }
            }
            state.sort_by(|a, b| b.at.cmp(&a.at));
        });
        added
    }

    fn mutate(&self, change: impl FnOnce(&mut Vec<Favorite>)) -> Vec<Favorite> {
        let mut state = self.state.lock().expect("favorites mutex poisoned");
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
        .join("rust-favorites.json")
}

fn load(file: &PathBuf) -> Vec<Favorite> {
    fs::read_to_string(file)
        .ok()
        .and_then(|text| serde_json::from_str::<FavoritesFile>(&text).ok())
        .map(|data| data.tracks)
        .unwrap_or_default()
}

fn save(file: &PathBuf, tracks: &[Favorite]) {
    if let Some(parent) = file.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let data = FavoritesFile {
        tracks: tracks.to_vec(),
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
