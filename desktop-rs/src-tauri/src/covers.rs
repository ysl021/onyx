use lofty::prelude::*;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const POSITIVE_TTL_MS: u64 = 30 * 24 * 60 * 60 * 1000;
const NEGATIVE_TTL_MS: u64 = 6 * 60 * 60 * 1000;

static CACHE_LOCK: Mutex<()> = Mutex::new(());

#[derive(Debug, Clone, Serialize, Deserialize)]
struct CacheEntry {
    url: Option<String>,
    at: u64,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct CacheFile {
    entries: HashMap<String, CacheEntry>,
}

#[derive(Debug, Deserialize)]
struct DeezerTracks {
    data: Vec<DeezerTrack>,
}

#[derive(Debug, Deserialize)]
struct DeezerTrack {
    title: String,
    artist: DeezerArtist,
    album: DeezerAlbum,
}

#[derive(Debug, Deserialize)]
struct DeezerAlbums {
    data: Vec<DeezerAlbumResult>,
}

#[derive(Debug, Deserialize)]
struct DeezerAlbumResult {
    title: String,
    artist: DeezerArtist,
    cover_xl: Option<String>,
    cover_big: Option<String>,
}

#[derive(Debug, Deserialize)]
struct DeezerArtist {
    name: String,
}

#[derive(Debug, Deserialize)]
struct DeezerAlbum {
    cover_xl: Option<String>,
    cover_big: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ItunesResponse {
    results: Vec<ItunesItem>,
}

#[derive(Debug, Deserialize)]
struct ItunesItem {
    #[serde(rename = "trackName")]
    track_name: Option<String>,
    #[serde(rename = "collectionName")]
    collection_name: Option<String>,
    #[serde(rename = "artistName")]
    artist_name: Option<String>,
    #[serde(rename = "artworkUrl100")]
    artwork_url_100: Option<String>,
}

/// Finds a cover for Discord: local artwork first, then track lookup, then
/// album lookup (which catches files whose title search returns nothing).
pub fn resolve(title: &str, artist: &str, album: &str, path: Option<&str>) -> Option<String> {
    if title.trim().is_empty() {
        return None;
    }
    let key = cache_key(title, artist, album);
    if let Some(cached) = cache_get(&key) {
        return cached;
    }

    let mut url = None;
    if let Some(path) = path {
        if !path.starts_with("http") {
            if let Some((data, mime)) = local_cover(path) {
                url = upload_cover(data, mime);
            }
        }
    }

    if url.is_none() {
        url = deezer_track(title, artist)
            .or_else(|| deezer_album(artist, album))
            .or_else(|| deezer_title(title, artist))
            .or_else(|| itunes_track(title, artist))
            .or_else(|| itunes_album(artist, album));
    }

    cache_put(&key, url.clone());
    url
}

fn client() -> reqwest::blocking::Client {
    reqwest::blocking::Client::builder()
        .user_agent("ONYX/0.9.0")
        .timeout(Duration::from_secs(9))
        .build()
        .unwrap_or_else(|_| reqwest::blocking::Client::new())
}

fn deezer_track(title: &str, artist: &str) -> Option<String> {
    if artist.trim().is_empty() {
        return None;
    }
    let response = client()
        .get("https://api.deezer.com/search")
        .query(&[("q", format!("artist:\"{artist}\" track:\"{title}\""))])
        .send()
        .ok()?;
    let data = response.json::<DeezerTracks>().ok()?;
    data.data
        .into_iter()
        .find(|item| same(&item.artist.name, artist) && same(&item.title, title))
        .and_then(|item| item.album.cover_xl.or(item.album.cover_big))
}

fn deezer_title(title: &str, artist: &str) -> Option<String> {
    let response = client()
        .get("https://api.deezer.com/search")
        .query(&[("q", format!("track:\"{title}\""))])
        .send()
        .ok()?;
    let data = response.json::<DeezerTracks>().ok()?;
    data.data
        .into_iter()
        .find(|item| {
            same(&item.title, title)
                && (artist.trim().is_empty() || same(&item.artist.name, artist))
        })
        .and_then(|item| item.album.cover_xl.or(item.album.cover_big))
}

fn deezer_album(artist: &str, album: &str) -> Option<String> {
    if artist.trim().is_empty() || album.trim().is_empty() {
        return None;
    }
    let response = client()
        .get("https://api.deezer.com/search/album")
        .query(&[("q", format!("artist:\"{artist}\" album:\"{album}\""))])
        .send()
        .ok()?;
    let data = response.json::<DeezerAlbums>().ok()?;
    data.data
        .into_iter()
        .find(|item| same(&item.artist.name, artist) && same(&item.title, album))
        .and_then(|item| item.cover_xl.or(item.cover_big))
}

fn itunes_track(title: &str, artist: &str) -> Option<String> {
    let term = format!("{artist} {title}").trim().to_string();
    let response = client()
        .get("https://itunes.apple.com/search")
        .query(&[("term", term), ("entity", "song".into()), ("limit", "5".into())])
        .send()
        .ok()?;
    let data = response.json::<ItunesResponse>().ok()?;
    data.results
        .into_iter()
        .find(|item| {
            item.track_name
                .as_deref()
                .map(|value| same(value, title))
                .unwrap_or(false)
                && item
                    .artist_name
                    .as_deref()
                    .map(|value| same(value, artist))
                    .unwrap_or(false)
        })
        .and_then(|item| item.artwork_url_100)
        .map(upscale_itunes)
}

fn itunes_album(artist: &str, album: &str) -> Option<String> {
    if album.trim().is_empty() {
        return None;
    }
    let term = format!("{artist} {album}").trim().to_string();
    let response = client()
        .get("https://itunes.apple.com/search")
        .query(&[("term", term), ("entity", "album".into()), ("limit", "5".into())])
        .send()
        .ok()?;
    let data = response.json::<ItunesResponse>().ok()?;
    data.results
        .into_iter()
        .find(|item| {
            item.collection_name
                .as_deref()
                .map(|value| same(value, album))
                .unwrap_or(false)
                && item
                    .artist_name
                    .as_deref()
                    .map(|value| same(value, artist))
                    .unwrap_or(false)
        })
        .and_then(|item| item.artwork_url_100)
        .map(upscale_itunes)
}

fn upscale_itunes(url: String) -> String {
    url.replace("100x100", "600x600")
}

fn local_cover(path: &str) -> Option<(Vec<u8>, String)> {
    let tagged = lofty::read_from_path(path).ok()?;
    let tag = tagged.primary_tag().or_else(|| tagged.first_tag())?;
    let picture = tag.pictures().first()?;
    let mime = picture
        .mime_type()
        .map(|value| value.to_string())
        .unwrap_or_else(|| "image/jpeg".into());
    Some((picture.data().to_vec(), mime))
}

fn upload_cover(data: Vec<u8>, mime: String) -> Option<String> {
    let client = client();

    let part = reqwest::blocking::multipart::Part::bytes(data.clone())
        .file_name("cover.jpg")
        .mime_str(&mime)
        .ok()?;
    let form = reqwest::blocking::multipart::Form::new()
        .text("reqtype", "fileupload")
        .part("fileToUpload", part);
    if let Ok(response) = client
        .post("https://catbox.moe/user/api.php")
        .multipart(form)
        .send()
    {
        if let Ok(text) = response.text() {
            let text = text.trim();
            if text.starts_with("https://") {
                return Some(text.to_string());
            }
        }
    }

    let part = reqwest::blocking::multipart::Part::bytes(data)
        .file_name("cover.jpg")
        .mime_str(&mime)
        .ok()?;
    let form = reqwest::blocking::multipart::Form::new().part("files[]", part);
    let response = client
        .post("https://pomf2.lain.la/upload.php")
        .multipart(form)
        .send()
        .ok()?;
    let value: Value = response.json().ok()?;
    value
        .get("files")?
        .as_array()?
        .first()?
        .get("url")?
        .as_str()
        .map(str::to_string)
}

fn cache_key(title: &str, artist: &str, album: &str) -> String {
    format!(
        "{}|{}|{}",
        normalize(artist),
        normalize(title),
        normalize(album)
    )
}

fn normalize(value: &str) -> String {
    value
        .to_lowercase()
        .replace('\u{a0}', " ")
        .replace('\u{200b}', "")
        .chars()
        .filter(|character| !character.is_whitespace() && !character.is_ascii_punctuation())
        .collect()
}

fn same(left: &str, right: &str) -> bool {
    let left = normalize(left);
    let right = normalize(right);
    if left.is_empty() || right.is_empty() {
        return false;
    }
    left == right || left.contains(&right) || right.contains(&left)
}

fn cache_path() -> PathBuf {
    let base = std::env::var("APPDATA").unwrap_or_else(|_| ".".into());
    PathBuf::from(base).join("ONYX").join("cover-cache.json")
}

fn load_cache() -> CacheFile {
    fs::read_to_string(cache_path())
        .ok()
        .map(|text| text.trim_start_matches('\u{feff}').to_string())
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

fn save_cache(cache: &CacheFile) {
    let file = cache_path();
    if let Some(parent) = file.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(text) = serde_json::to_string_pretty(cache) {
        let _ = fs::write(file, text);
    }
}

fn cache_get(key: &str) -> Option<Option<String>> {
    let _guard = CACHE_LOCK.lock().ok()?;
    let cache = load_cache();
    let entry = cache.entries.get(key)?;
    let ttl = if entry.url.is_some() {
        POSITIVE_TTL_MS
    } else {
        NEGATIVE_TTL_MS
    };
    if now_ms().saturating_sub(entry.at) < ttl {
        Some(entry.url.clone())
    } else {
        None
    }
}

fn cache_put(key: &str, url: Option<String>) {
    let Ok(_guard) = CACHE_LOCK.lock() else {
        return;
    };
    let mut cache = load_cache();
    cache.entries.insert(
        key.to_string(),
        CacheEntry {
            url,
            at: now_ms(),
        },
    );
    save_cache(&cache);
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}
