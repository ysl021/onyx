use base64::Engine;
use lofty::prelude::*;
use serde::Serialize;
use std::path::Path;
use std::sync::Mutex;
use walkdir::WalkDir;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Track {
    pub id: String,
    pub path: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    pub has_cover: bool,
}

/// Scans the configured music folders with `walkdir` and reads tags/artwork
/// with `lofty`. The track list is kept in memory for the UI.
pub struct Library {
    tracks: Mutex<Vec<Track>>,
}

impl Library {
    pub fn new() -> Self {
        Self {
            tracks: Mutex::new(Vec::new()),
        }
    }

    pub fn tracks(&self) -> Vec<Track> {
        self.tracks
            .lock()
            .map(|tracks| tracks.clone())
            .unwrap_or_default()
    }

    pub fn scan(&self, folders: &[String]) -> Vec<Track> {
        let mut result = Vec::new();
        for folder in folders {
            let root = Path::new(folder);
            if !root.exists() {
                continue;
            }
            for entry in WalkDir::new(root)
                .follow_links(true)
                .into_iter()
                .filter_map(Result::ok)
            {
                if !entry.file_type().is_file() {
                    continue;
                }
                let path = entry.path();
                if !is_audio(path) {
                    continue;
                }
                if let Some(track) = read_track(path) {
                    result.push(track);
                }
            }
        }
        result.sort_by(|a, b| {
            a.artist
                .to_lowercase()
                .cmp(&b.artist.to_lowercase())
                .then(a.album.to_lowercase().cmp(&b.album.to_lowercase()))
                .then(a.title.to_lowercase().cmp(&b.title.to_lowercase()))
        });
        result.dedup_by(|a, b| a.path == b.path);
        if let Ok(mut tracks) = self.tracks.lock() {
            *tracks = result.clone();
        }
        result
    }

    pub fn cover(&self, path: &str) -> Option<String> {
        read_cover(Path::new(path))
    }
}

fn is_audio(path: &Path) -> bool {
    matches!(
        path.extension()
            .and_then(|extension| extension.to_str())
            .map(|extension| extension.to_ascii_lowercase())
            .as_deref(),
        Some("mp3" | "flac" | "m4a" | "aac" | "ogg" | "opus" | "wav" | "wma")
    )
}

fn read_track(path: &Path) -> Option<Track> {
    let tagged = lofty::read_from_path(path).ok()?;
    let duration_ms = tagged.properties().duration().as_millis() as u64;
    let tag = tagged.primary_tag().or_else(|| tagged.first_tag());
    let fallback = path
        .file_stem()
        .and_then(|stem| stem.to_str())
        .unwrap_or("Unknown")
        .to_string();
    let title = tag
        .and_then(|tag| tag.title().map(|value| value.to_string()))
        .filter(|value| !value.trim().is_empty())
        .unwrap_or(fallback);
    let artist = tag
        .and_then(|tag| tag.artist().map(|value| value.to_string()))
        .unwrap_or_default();
    let album = tag
        .and_then(|tag| tag.album().map(|value| value.to_string()))
        .unwrap_or_default();
    let has_cover = tag
        .map(|tag| !tag.pictures().is_empty())
        .unwrap_or(false);
    let path = path.to_string_lossy().to_string();
    Some(Track {
        id: path.clone(),
        path,
        title,
        artist,
        album,
        duration_ms,
        has_cover,
    })
}

fn read_cover(path: &Path) -> Option<String> {
    let tagged = lofty::read_from_path(path).ok()?;
    let tag = tagged.primary_tag().or_else(|| tagged.first_tag())?;
    let picture = tag.pictures().first()?;
    let mime = picture
        .mime_type()
        .map(|mime| mime.to_string())
        .unwrap_or_else(|| "image/jpeg".into());
    let encoded = base64::engine::general_purpose::STANDARD.encode(picture.data());
    Some(format!("data:{mime};base64,{encoded}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `ONYX_TEST_FOLDER=... cargo test -- --nocapture`
    #[test]
    fn scans_a_folder() {
        let Ok(folder) = std::env::var("ONYX_TEST_FOLDER") else {
            return;
        };
        let library = Library::new();
        let tracks = library.scan(&[folder]);
        assert!(!tracks.is_empty(), "no audio tracks found");
        println!("first track: {:?}", tracks.first());
    }
}
