use crate::favorites::{Favorite, Favorites};
use crate::library::{Library, Track};
use crate::playlists::{MergePlaylist, MergeTrack, PlaylistStore, StreamTrack};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::net::UdpSocket;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tiny_http::{Header, Method, Request, Response};

const DISCOVERY_PORT: u16 = 8788;
const IDLE_CLEAR_MS: u64 = 90_000;

#[derive(Debug, Clone, Default)]
pub struct RemotePresence {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub playing: bool,
    pub duration_ms: u64,
    pub position_ms: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct HubDevice {
    pub name: String,
    pub token: String,
    pub added_at: u64,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(default)]
struct HubFile {
    name: String,
    devices: Vec<HubDevice>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SyncEntry {
    pub kind: String,
    pub video_id: String,
    pub key: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    pub thumbnail: Option<String>,
    pub file_name: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SyncPlaylist {
    pub id: String,
    pub name: String,
    pub description: String,
    pub pinned: bool,
    pub folder_id: Option<String>,
    pub created_at: u64,
    pub updated_at: u64,
    pub deleted: bool,
    pub entries: Vec<SyncEntry>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SyncLikedTrack {
    pub id: String,
    pub video_id: Option<String>,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    pub artwork: Option<String>,
    pub at: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SyncLikedArtist {
    pub name: String,
    pub thumbnail: Option<String>,
    pub browse_id: Option<String>,
    pub at: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SyncLikes {
    pub tracks: Vec<SyncLikedTrack>,
    pub artists: Vec<SyncLikedArtist>,
    pub updated_at: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PhoneTrack {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    pub size: u64,
    pub mime: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SyncState {
    pub rev: u64,
    pub playlists: Vec<SyncPlaylist>,
    pub likes: SyncLikes,
    pub updated_at: u64,
    pub source: String,
    pub pending_for: String,
    pub request_from: String,
    pub library_request: String,
    pub library_index: Vec<PhoneTrack>,
    pub library_status: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HubStatus {
    pub running: bool,
    pub port: u16,
    pub name: String,
    pub addresses: Vec<String>,
    pub pair_code: String,
    pub devices: Vec<HubDevice>,
    pub error: Option<String>,
    pub sync_status: String,
    pub library_index_len: usize,
    pub paired: bool,
    pub sync_rev: u64,
}

struct Inner {
    server: Option<Arc<tiny_http::Server>>,
    running: bool,
    port: u16,
    name: String,
    pair_code: String,
    devices: Vec<HubDevice>,
    error: Option<String>,
    sync: SyncState,
    sync_status: String,
    last_presence: Option<Instant>,
    last_phone_state: Option<SyncState>,
}

struct Shared {
    inner: Mutex<Inner>,
    library: Arc<Library>,
    playlists: Arc<PlaylistStore>,
    favorites: Arc<Favorites>,
    remote: Arc<Mutex<Option<RemotePresence>>>,
    discovery: AtomicBool,
}

#[derive(Clone)]
pub struct Hub {
    shared: Arc<Shared>,
}

impl Hub {
    pub fn new(
        library: Arc<Library>,
        playlists: Arc<PlaylistStore>,
        favorites: Arc<Favorites>,
        remote: Arc<Mutex<Option<RemotePresence>>>,
    ) -> Self {
        let hub_file = load_hub();
        let name = if hub_file.name.trim().is_empty() {
            default_name()
        } else {
            hub_file.name.clone()
        };
        Self {
            shared: Arc::new(Shared {
                inner: Mutex::new(Inner {
                    server: None,
                    running: false,
                    port: 8787,
                    name,
                    pair_code: new_code(),
                    devices: hub_file.devices,
                    error: None,
                    sync: load_sync(),
                    sync_status: "Готов к синхронизации".into(),
                    last_presence: None,
                    last_phone_state: None,
                }),
                library,
                playlists,
                favorites,
                remote,
                discovery: AtomicBool::new(false),
            }),
        }
    }

    pub fn start(&self, port: u16, name: String) -> Result<(), String> {
        let mut inner = self.shared.inner.lock().map_err(|error| error.to_string())?;
        if inner.running {
            return Ok(());
        }
        let port = port.clamp(1024, 65535);
        let server = tiny_http::Server::http(("0.0.0.0", port))
            .map_err(|error| format!("Порт {port} занят — возможно, запущен другой ONYX: {error}"))?;
        inner.server = Some(Arc::new(server));
        inner.running = true;
        inner.port = port;
        inner.name = name;
        inner.error = None;
        inner.pair_code = new_code();
        drop(inner);

        let hub = self.clone();
        thread::spawn(move || {
            let server = {
                match hub.shared.inner.lock() {
                    Ok(inner) => inner.server.clone(),
                    Err(_) => return,
                }
            };
            let Some(server) = server else {
                return;
            };
            loop {
                if !hub.is_running() {
                    break;
                }
                match server.recv_timeout(Duration::from_millis(400)) {
                    Ok(Some(request)) => hub.handle(request),
                    Ok(None) => {}
                    Err(_) => break,
                }
            }
        });

        self.start_discovery();
        self.start_watchdog();
        Ok(())
    }

    pub fn stop(&self) {
        if let Ok(mut inner) = self.shared.inner.lock() {
            inner.running = false;
            inner.sync_status = "Хаб остановлен".into();
            if let Some(server) = inner.server.take() {
                server.unblock();
            }
        }
    }

    pub fn is_running(&self) -> bool {
        self.shared
            .inner
            .lock()
            .map(|inner| inner.running)
            .unwrap_or(false)
    }

    pub fn status(&self) -> HubStatus {
        let inner = self.shared.inner.lock().ok();
        let (running, port, name, pair_code, devices, error, sync_status, library_index_len, rev) =
            inner
                .as_ref()
                .map(|inner| {
                    (
                        inner.running,
                        inner.port,
                        inner.name.clone(),
                        inner.pair_code.clone(),
                        inner.devices.clone(),
                        inner.error.clone(),
                        inner.sync_status.clone(),
                        inner.sync.library_index.len(),
                        inner.sync.rev,
                    )
                })
                .unwrap_or((false, 8787, default_name(), String::new(), vec![], None, String::new(), 0, 0));
        HubStatus {
            running,
            port,
            name,
            addresses: lan_addresses(),
            pair_code,
            paired: !devices.is_empty(),
            devices,
            error,
            sync_status,
            library_index_len,
            sync_rev: rev,
        }
    }

    pub fn sync_state(&self) -> SyncState {
        self.shared
            .inner
            .lock()
            .map(|inner| inner.sync.clone())
            .unwrap_or_default()
    }

    pub fn push_to_phone(&self) -> Result<(), String> {
        let state = self.build_state();
        let count = state.playlists.len();
        let mut inner = self.shared.inner.lock().map_err(|error| error.to_string())?;
        let stored = replace_state(&mut inner.sync, state, "pc", "phone");
        inner.sync_status = format!("Отправлено {count} плейлистов — телефон применит при опросе");
        save_sync(&inner.sync);
        let _ = stored;
        Ok(())
    }

    pub fn request_phone(&self) {
        if let Ok(mut inner) = self.shared.inner.lock() {
            inner.sync.rev += 1;
            inner.sync.request_from = "phone".into();
            inner.sync.pending_for.clear();
            inner.sync_status = "Жду плейлисты и любимые с телефона…".into();
            save_sync(&inner.sync);
        }
    }

    pub fn request_library_index(&self) {
        if let Ok(mut inner) = self.shared.inner.lock() {
            inner.sync.rev += 1;
            inner.sync.library_request = "index".into();
            inner.sync.library_status = "Жду список треков с телефона…".into();
            inner.sync_status = "Запрашиваю список треков с телефона…".into();
            save_sync(&inner.sync);
        }
    }

    pub fn request_library_files(&self, ids: Vec<String>) {
        if ids.is_empty() {
            return;
        }
        if let Ok(mut inner) = self.shared.inner.lock() {
            inner.sync.rev += 1;
            inner.sync.library_request = format!("files:{}", ids.join(","));
            inner.sync.library_status = format!("Скачиваю {} трек(ов) с телефона…", ids.len());
            inner.sync_status = inner.sync.library_status.clone();
            save_sync(&inner.sync);
        }
    }

    pub fn forget_devices(&self) {
        if let Ok(mut inner) = self.shared.inner.lock() {
            inner.devices.clear();
            inner.sync_status = "Устройства отвязаны".into();
            save_hub(&inner.name, &inner.devices);
        }
    }

    pub fn regenerate_code(&self) {
        if let Ok(mut inner) = self.shared.inner.lock() {
            inner.pair_code = new_code();
        }
    }

    // ------------------------------------------------------------- HTTP

    fn handle(&self, request: Request) {
        let url = request.url().to_string();
        let path = url.split('?').next().unwrap_or("/").to_string();
        let query = parse_query(&url);
        let method = request.method().clone();

        match (method, path.as_str()) {
            (Method::Get, "/health") => {
                let inner = self.shared.inner.lock().ok();
                let (name, paired) = inner
                    .as_ref()
                    .map(|inner| (inner.name.clone(), !inner.devices.is_empty()))
                    .unwrap_or((default_name(), false));
                respond_json(
                    request,
                    200,
                    json!({
                        "ok": true,
                        "app": "onyx",
                        "name": name,
                        "version": env!("CARGO_PKG_VERSION"),
                        "paired": paired,
                        "discord": true
                    })
                    .to_string(),
                );
            }
            (Method::Post, "/pair") => self.pair(request),
            (Method::Post, "/presence") => self.presence(request),
            (Method::Get, "/v1/ping") => respond_json(request, 200, json!({"ok": true}).to_string()),
            (Method::Get, "/v1/sync") => self.sync_get(request, query),
            (Method::Post, "/v1/sync") => self.sync_post(request),
            (Method::Post, "/v1/sync/pull") => self.sync_pull(request),
            (Method::Post, "/v1/sync/ack") => self.sync_ack(request),
            (Method::Post, "/v1/library/index") => self.library_index(request),
            (Method::Post, "/v1/library/file") => self.library_file(request, query),
            (Method::Get, "/v1/library/track") => self.library_track(request, query),
            (Method::Post, "/v1/library/done") => self.library_done(request),
            (Method::Post, "/v1/unpair") => self.unpair(request),
            _ => respond_text(request, 404, "not found"),
        }
    }

    fn authorized(&self, request: &Request) -> bool {
        let devices = self
            .shared
            .inner
            .lock()
            .map(|inner| inner.devices.clone())
            .unwrap_or_default();
        if devices.is_empty() {
            return true;
        }
        let token = bearer(request);
        !token.is_empty() && devices.iter().any(|device| device.token == token)
    }

    fn pair(&self, mut request: Request) {
        let body = read_body(&mut request).and_then(|text| serde_json::from_str::<Value>(&text).ok());
        let code = body
            .as_ref()
            .and_then(|value| value.get("code"))
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim()
            .to_string();
        let device_name = body
            .as_ref()
            .and_then(|value| value.get("device"))
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim()
            .chars()
            .take(40)
            .collect::<String>();
        let device_name = if device_name.is_empty() {
            "Телефон".to_string()
        } else {
            device_name
        };

        let mut inner = match self.shared.inner.lock() {
            Ok(inner) => inner,
            Err(_) => {
                respond_text(request, 500, "error");
                return;
            }
        };
        if code != inner.pair_code {
            respond_json(request, 403, json!({"error": "wrong code"}).to_string());
            return;
        }
        let device = HubDevice {
            name: device_name,
            token: new_token(),
            added_at: now_ms(),
        };
        inner.devices.push(device.clone());
        inner.pair_code = new_code();
        inner.sync_status = format!("Подключено устройство: {}", device.name);
        save_hub(&inner.name, &inner.devices);
        let name = inner.name.clone();
        let port = inner.port;
        drop(inner);
        respond_json(
            request,
            200,
            json!({"token": device.token, "name": name, "port": port}).to_string(),
        );
    }

    fn presence(&self, mut request: Request) {
        if !self.authorized(&request) {
            respond_text(request, 401, "unauthorized");
            return;
        }
        let body = read_body(&mut request).and_then(|text| serde_json::from_str::<Value>(&text).ok());
        let Some(body) = body else {
            respond_text(request, 400, "bad json");
            return;
        };
        if body.get("clear").and_then(Value::as_bool).unwrap_or(false) {
            if let Ok(mut inner) = self.shared.inner.lock() {
                inner.last_presence = None;
            }
            if let Ok(mut remote) = self.shared.remote.lock() {
                *remote = None;
            }
            respond_empty(request, 204);
            return;
        }
        let title = body
            .get("title")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim()
            .to_string();
        if title.is_empty() {
            if let Ok(mut inner) = self.shared.inner.lock() {
                inner.last_presence = None;
            }
            if let Ok(mut remote) = self.shared.remote.lock() {
                *remote = None;
            }
            respond_empty(request, 204);
            return;
        }
        let presence = RemotePresence {
            title,
            artist: string_field(&body, "artist"),
            album: string_field(&body, "album"),
            playing: body.get("playing").and_then(Value::as_bool).unwrap_or(true),
            duration_ms: body.get("durationMs").and_then(Value::as_u64).unwrap_or(0),
            position_ms: body.get("positionMs").and_then(Value::as_u64).unwrap_or(0),
        };
        if let Ok(mut inner) = self.shared.inner.lock() {
            inner.last_presence = Some(Instant::now());
        }
        if let Ok(mut remote) = self.shared.remote.lock() {
            *remote = Some(presence);
        }
        respond_empty(request, 204);
    }

    fn sync_get(&self, request: Request, query: HashMap<String, String>) {
        if !self.authorized(&request) {
            respond_text(request, 401, "unauthorized");
            return;
        }
        let since = query
            .get("since")
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(0);
        let state = self.sync_state();
        let body = json!({
            "rev": state.rev,
            "unchanged": since >= state.rev,
            "state": state
        });
        respond_json(request, 200, body.to_string());
    }

    fn sync_post(&self, mut request: Request) {
        if !self.authorized(&request) {
            respond_text(request, 401, "unauthorized");
            return;
        }
        let body = read_body(&mut request).and_then(|text| serde_json::from_str::<Value>(&text).ok());
        let incoming = body
            .as_ref()
            .and_then(|value| value.get("state"))
            .and_then(|value| serde_json::from_value::<SyncState>(value.clone()).ok());
        let Some(incoming) = incoming else {
            respond_text(request, 400, "bad state");
            return;
        };
        let (playlists_added, tracks_added, likes_added, missing) = self.apply_state(&incoming);
        let mut inner = match self.shared.inner.lock() {
            Ok(inner) => inner,
            Err(_) => {
                respond_text(request, 500, "error");
                return;
            }
        };
        let stored = replace_state(&mut inner.sync, incoming.clone(), "phone", "");
        inner.last_phone_state = Some(incoming);
        inner.sync_status = format!(
            "С телефона: +{playlists_added} плейлистов, +{tracks_added} треков, +{likes_added} любимых"
        );
        if !missing.is_empty() {
            inner.sync.library_status =
                format!("Не хватает {} файлов — включи копирование на телефоне", missing.len());
        }
        save_sync(&inner.sync);
        let rev = stored.rev;
        drop(inner);
        respond_json(
            request,
            200,
            json!({
                "rev": rev,
                "playlistsAdded": playlists_added,
                "tracksAdded": tracks_added,
                "likesAdded": likes_added,
                "missing": missing
            })
            .to_string(),
        );
    }

    fn sync_pull(&self, request: Request) {
        if !self.authorized(&request) {
            respond_text(request, 401, "unauthorized");
            return;
        }
        let state = self.build_state();
        let mut inner = match self.shared.inner.lock() {
            Ok(inner) => inner,
            Err(_) => {
                respond_text(request, 500, "error");
                return;
            }
        };
        let stored = replace_state(&mut inner.sync, state.clone(), "pc", "");
        inner.sync_status = "Отправлено состояние ПК".into();
        save_sync(&inner.sync);
        let rev = stored.rev;
        drop(inner);
        respond_json(request, 200, json!({"rev": rev, "state": state}).to_string());
    }

    fn sync_ack(&self, request: Request) {
        if !self.authorized(&request) {
            respond_text(request, 401, "unauthorized");
            return;
        }
        if let Ok(mut inner) = self.shared.inner.lock() {
            inner.sync.pending_for.clear();
            inner.sync_status = "Телефон применил состояние ПК".into();
            save_sync(&inner.sync);
        }
        respond_empty(request, 204);
    }

    fn library_index(&self, mut request: Request) {
        if !self.authorized(&request) {
            respond_text(request, 401, "unauthorized");
            return;
        }
        let body = read_body(&mut request).and_then(|text| serde_json::from_str::<Value>(&text).ok());
        let tracks = body
            .as_ref()
            .and_then(|value| value.get("tracks"))
            .and_then(|value| serde_json::from_value::<Vec<PhoneTrack>>(value.clone()).ok())
            .unwrap_or_default();
        let count = tracks.len();
        if let Ok(mut inner) = self.shared.inner.lock() {
            inner.sync.rev += 1;
            inner.sync.library_index = tracks;
            inner.sync.library_request.clear();
            inner.sync.library_status = format!("Список получен: {count} трек(ов)");
            inner.sync_status = inner.sync.library_status.clone();
            save_sync(&inner.sync);
        }
        respond_json(request, 200, json!({"count": count}).to_string());
    }

    fn library_file(&self, mut request: Request, query: HashMap<String, String>) {
        if !self.authorized(&request) {
            respond_text(request, 401, "unauthorized");
            return;
        }
        let raw_name = query
            .get("name")
            .map(String::as_str)
            .unwrap_or("")
            .trim();
        let name = safe_file_name(if raw_name.is_empty() { "track.bin" } else { raw_name });
        let dir = phone_music_dir();
        if fs::create_dir_all(&dir).is_err() {
            respond_text(request, 500, "cannot create folder");
            return;
        }
        let destination = dir.join(name);
        let mut data = Vec::new();
        if request.as_reader().read_to_end(&mut data).is_err() {
            respond_text(request, 400, "bad body");
            return;
        }
        if fs::write(&destination, &data).is_err() {
            respond_text(request, 500, "cannot write file");
            return;
        }
        tag_file(
            &destination,
            query.get("title").map(String::as_str).unwrap_or(""),
            query.get("artist").map(String::as_str).unwrap_or(""),
            query.get("album").map(String::as_str).unwrap_or(""),
        );
        if let Ok(mut inner) = self.shared.inner.lock() {
            inner.sync.library_status =
                format!("Получен файл: {}", destination.file_name().unwrap_or_default().to_string_lossy());
        }
        respond_empty(request, 204);
    }

    fn library_track(&self, request: Request, query: HashMap<String, String>) {
        if !self.authorized(&request) {
            respond_text(request, 401, "unauthorized");
            return;
        }
        let key = query.get("key").map(String::as_str).unwrap_or("");
        let track = self
            .shared
            .library
            .tracks()
            .into_iter()
            .find(|track| sync_key(&track.title, &track.artist, track.duration_ms) == key);
        let Some(track) = track else {
            respond_text(request, 404, "not found");
            return;
        };
        let Ok(file) = fs::File::open(&track.path) else {
            respond_text(request, 404, "not found");
            return;
        };
        let header = Header::from_bytes(&b"Content-Type"[..], &b"application/octet-stream"[..])
            .expect("static header");
        let response = Response::from_file(file).with_header(header);
        let _ = request.respond(response);
    }

    fn library_done(&self, mut request: Request) {
        if !self.authorized(&request) {
            respond_text(request, 401, "unauthorized");
            return;
        }
        let body = read_body(&mut request).and_then(|text| serde_json::from_str::<Value>(&text).ok());
        let count = body
            .as_ref()
            .and_then(|value| value.get("count"))
            .and_then(Value::as_u64)
            .unwrap_or(0);
        let pending = if let Ok(mut inner) = self.shared.inner.lock() {
            inner.sync.rev += 1;
            inner.sync.library_request.clear();
            inner.sync.library_status = format!("Готово: {count} трек(ов) с телефона");
            inner.sync_status = inner.sync.library_status.clone();
            save_sync(&inner.sync);
            inner.last_phone_state.clone()
        } else {
            None
        };
        respond_empty(request, 204);

        // Rescan first, then apply the last phone snapshot again so playlists
        // whose files were just uploaded receive their tracks.
        let library = Arc::clone(&self.shared.library);
        let hub = self.clone();
        thread::spawn(move || {
            let folders = crate::settings::load().music_folders;
            library.scan(&folders);
            if let Some(state) = pending {
                let (playlists_added, tracks_added, likes_added, missing) = hub.apply_state(&state);
                if let Ok(mut inner) = hub.shared.inner.lock() {
                    inner.sync_status = format!(
                        "После копирования: +{playlists_added} плейлистов, +{tracks_added} треков, +{likes_added} любимых"
                    );
                    if missing.is_empty() {
                        inner.sync.library_status = "Все файлы с телефона на месте".into();
                    }
                    save_sync(&inner.sync);
                }
            }
        });
    }

    fn unpair(&self, mut request: Request) {
        let token = bearer(&request);
        let body = read_body(&mut request).and_then(|text| serde_json::from_str::<Value>(&text).ok());
        let device_name = body
            .as_ref()
            .and_then(|value| value.get("device"))
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim()
            .to_string();
        if let Ok(mut inner) = self.shared.inner.lock() {
            let before = inner.devices.len();
            inner.devices.retain(|device| {
                if !token.is_empty() && device.token == token {
                    return false;
                }
                !(!device_name.is_empty() && device.name.eq_ignore_ascii_case(&device_name))
            });
            if inner.devices.len() != before {
                inner.sync_status = "Устройство отвязано".into();
                save_hub(&inner.name, &inner.devices);
            }
        }
        respond_empty(request, 204);
    }

    // ------------------------------------------------------------- state

    fn build_state(&self) -> SyncState {
        let tracks = self.shared.library.tracks();
        let playlists = self
            .shared
            .playlists
            .list()
            .into_iter()
            .map(|playlist| {
                let entries = playlist
                    .track_ids
                    .iter()
                    .filter_map(|id| {
                        if let Some(video_id) = id.strip_prefix("yt:") {
                            let stream = playlist.streams.iter().find(|item| item.id == *id);
                            Some(SyncEntry {
                                kind: "yt".into(),
                                video_id: video_id.to_string(),
                                key: String::new(),
                                title: stream.map(|item| item.title.clone()).unwrap_or_default(),
                                artist: stream.map(|item| item.artist.clone()).unwrap_or_default(),
                                album: String::new(),
                                duration_ms: stream.map(|item| item.duration_ms).unwrap_or(0),
                                thumbnail: stream.and_then(|item| item.thumbnail.clone()),
                                file_name: String::new(),
                            })
                        } else {
                            let track = tracks.iter().find(|track| track.path == *id)?;
                            Some(local_entry(track))
                        }
                    })
                    .collect();
                SyncPlaylist {
                    id: playlist.id,
                    name: playlist.name,
                    description: playlist.description,
                    pinned: playlist.pinned,
                    folder_id: None,
                    created_at: playlist.created_at,
                    updated_at: playlist.created_at,
                    deleted: false,
                    entries,
                }
            })
            .collect();
        let favorites = self.shared.favorites.list();
        let updated_at = favorites.iter().map(|item| item.at).max().unwrap_or(0);
        SyncState {
            playlists,
            likes: SyncLikes {
                tracks: favorites
                    .iter()
                    .map(|item| SyncLikedTrack {
                        id: item.id.clone(),
                        video_id: item.video_id.clone(),
                        title: item.title.clone(),
                        artist: item.artist.clone(),
                        album: item.album.clone(),
                        duration_ms: item.duration_ms,
                        artwork: item.artwork.clone(),
                        at: item.at,
                    })
                    .collect(),
                artists: Vec::new(),
                updated_at,
            },
            updated_at: now_ms(),
            source: "pc".into(),
            ..SyncState::default()
        }
    }

    fn apply_state(&self, state: &SyncState) -> (usize, usize, usize, Vec<SyncEntry>) {
        let tracks = self.shared.library.tracks();
        let mut incoming = Vec::new();
        let mut missing = Vec::new();
        for playlist in &state.playlists {
            if playlist.deleted || playlist.name.trim().is_empty() {
                continue;
            }
            let mut list = Vec::new();
            for entry in &playlist.entries {
                if entry.kind == "yt" {
                    if entry.video_id.trim().is_empty() {
                        continue;
                    }
                    let id = format!("yt:{}", entry.video_id);
                    list.push(MergeTrack {
                        id: id.clone(),
                        stream: Some(StreamTrack {
                            id,
                            video_id: entry.video_id.clone(),
                            title: entry.title.clone(),
                            artist: entry.artist.clone(),
                            duration_ms: entry.duration_ms,
                            thumbnail: entry.thumbnail.clone(),
                        }),
                    });
                } else if !entry.key.trim().is_empty() {
                    match tracks
                        .iter()
                        .find(|track| sync_key(&track.title, &track.artist, track.duration_ms) == entry.key)
                    {
                        Some(track) => list.push(MergeTrack {
                            id: track.path.clone(),
                            stream: None,
                        }),
                        None => missing.push(entry.clone()),
                    }
                }
            }
            incoming.push(MergePlaylist {
                id: playlist.id.clone(),
                name: playlist.name.clone(),
                description: playlist.description.clone(),
                pinned: playlist.pinned,
                tracks: list,
            });
        }
        let (playlists_added, tracks_added) = self.shared.playlists.merge(incoming);

        let likes: Vec<Favorite> = state
            .likes
            .tracks
            .iter()
            .map(|liked| {
                let matched = tracks.iter().find(|track| {
                    sync_key(&track.title, &track.artist, track.duration_ms)
                        == sync_key(&liked.title, &liked.artist, liked.duration_ms)
                });
                let id = matched.map(|track| track.path.clone()).unwrap_or_else(|| {
                    if liked.id.trim().is_empty() {
                        format!("phone:{}|{}", liked.title, liked.artist)
                    } else {
                        liked.id.clone()
                    }
                });
                Favorite {
                    id,
                    video_id: liked.video_id.clone(),
                    title: liked.title.clone(),
                    artist: liked.artist.clone(),
                    album: liked.album.clone(),
                    duration_ms: liked.duration_ms,
                    artwork: liked.artwork.clone(),
                    at: liked.at,
                }
            })
            .collect();
        let likes_added = self.shared.favorites.merge(likes);
        (playlists_added, tracks_added, likes_added, missing)
    }

    // ---------------------------------------------------------- discovery

    fn start_discovery(&self) {
        if self.shared.discovery.swap(true, Ordering::SeqCst) {
            return;
        }
        let hub = self.clone();
        thread::spawn(move || {
            let socket = match UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)) {
                Ok(socket) => socket,
                Err(_) => {
                    hub.shared.discovery.store(false, Ordering::SeqCst);
                    return;
                }
            };
            let _ = socket.set_read_timeout(Some(Duration::from_millis(1000)));
            let mut buffer = [0u8; 2048];
            while hub.is_running() {
                let (size, from) = match socket.recv_from(&mut buffer) {
                    Ok(packet) => packet,
                    Err(_) => continue,
                };
                let text = String::from_utf8_lossy(&buffer[..size]);
                if !text.trim().starts_with("ONYX_DISCOVER") {
                    continue;
                }
                let (name, port, paired) = match hub.shared.inner.lock() {
                    Ok(inner) => (inner.name.clone(), inner.port, !inner.devices.is_empty()),
                    Err(_) => continue,
                };
                let reply = json!({
                    "app": "onyx",
                    "name": name,
                    "port": port,
                    "version": env!("CARGO_PKG_VERSION"),
                    "paired": paired
                })
                .to_string();
                let _ = socket.send_to(reply.as_bytes(), from);
            }
            hub.shared.discovery.store(false, Ordering::SeqCst);
        });
    }

    fn start_watchdog(&self) {
        let hub = self.clone();
        thread::spawn(move || {
            while hub.is_running() {
                thread::sleep(Duration::from_secs(5));
                let expired = hub
                    .shared
                    .inner
                    .lock()
                    .map(|inner| {
                        inner
                            .last_presence
                            .map(|at| at.elapsed().as_millis() as u64 > IDLE_CLEAR_MS)
                            .unwrap_or(false)
                    })
                    .unwrap_or(false);
                if expired {
                    if let Ok(mut inner) = hub.shared.inner.lock() {
                        inner.last_presence = None;
                    }
                    if let Ok(mut remote) = hub.shared.remote.lock() {
                        *remote = None;
                    }
                }
            }
        });
    }
}

fn local_entry(track: &Track) -> SyncEntry {
    SyncEntry {
        kind: "local".into(),
        video_id: String::new(),
        key: sync_key(&track.title, &track.artist, track.duration_ms),
        title: track.title.clone(),
        artist: track.artist.clone(),
        album: track.album.clone(),
        duration_ms: track.duration_ms,
        thumbnail: None,
        file_name: Path::new(&track.path)
            .file_name()
            .map(|name| name.to_string_lossy().to_string())
            .unwrap_or_default(),
    }
}

fn replace_state(
    current: &mut SyncState,
    mut incoming: SyncState,
    source: &str,
    pending_for: &str,
) -> SyncState {
    incoming.rev = current.rev + 1;
    incoming.updated_at = now_ms();
    incoming.source = source.to_string();
    incoming.pending_for = pending_for.to_string();
    incoming.request_from.clear();
    *current = incoming.clone();
    incoming
}

pub fn sync_key(title: &str, artist: &str, duration_ms: u64) -> String {
    format!(
        "{}|{}|{}",
        normalize(artist),
        normalize(title),
        duration_ms / 1000
    )
}

fn normalize(value: &str) -> String {
    value
        .to_lowercase()
        .replace('\u{a0}', " ")
        .chars()
        .filter(|character| !character.is_whitespace() && !character.is_ascii_punctuation())
        .collect()
}

fn parse_query(url: &str) -> HashMap<String, String> {
    let mut result = HashMap::new();
    let Some(query) = url.split('?').nth(1) else {
        return result;
    };
    for pair in query.split('&') {
        let mut parts = pair.splitn(2, '=');
        let key = parts.next().unwrap_or("");
        let value = parts.next().unwrap_or("");
        if key.is_empty() {
            continue;
        }
        result.insert(percent_decode(key), percent_decode(value));
    }
    result
}

fn percent_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut output = Vec::with_capacity(bytes.len());
    let mut index = 0usize;
    while index < bytes.len() {
        match bytes[index] {
            b'+' => {
                output.push(b' ');
                index += 1;
            }
            b'%' if index + 2 < bytes.len() => {
                let hex = &value[index + 1..index + 3];
                match u8::from_str_radix(hex, 16) {
                    Ok(byte) => {
                        output.push(byte);
                        index += 3;
                    }
                    Err(_) => {
                        output.push(bytes[index]);
                        index += 1;
                    }
                }
            }
            byte => {
                output.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8_lossy(&output).to_string()
}

fn read_body(request: &mut Request) -> Option<String> {
    let mut text = String::new();
    request.as_reader().read_to_string(&mut text).ok()?;
    Some(text)
}

fn respond_json(request: Request, code: u16, body: String) {
    let header = Header::from_bytes(&b"Content-Type"[..], &b"application/json; charset=utf-8"[..])
        .expect("static header");
    let response = Response::from_string(body)
        .with_status_code(code)
        .with_header(header);
    let _ = request.respond(response);
}

fn respond_text(request: Request, code: u16, text: &str) {
    let header = Header::from_bytes(&b"Content-Type"[..], &b"text/plain; charset=utf-8"[..])
        .expect("static header");
    let response = Response::from_string(text.to_string())
        .with_status_code(code)
        .with_header(header);
    let _ = request.respond(response);
}

fn respond_empty(request: Request, code: u16) {
    let _ = request.respond(Response::empty(code));
}

fn bearer(request: &Request) -> String {
    request
        .headers()
        .iter()
        .find(|header| header.field.equiv("Authorization"))
        .map(|header| header.value.as_str().trim().to_string())
        .unwrap_or_default()
        .strip_prefix("Bearer ")
        .unwrap_or("")
        .trim()
        .to_string()
}

fn string_field(body: &Value, key: &str) -> String {
    body.get(key)
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string()
}

fn safe_file_name(raw: &str) -> String {
    let cleaned: String = raw
        .chars()
        .map(|character| match character {
            '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            other => other,
        })
        .take(140)
        .collect();
    if cleaned.trim().is_empty() {
        "track.bin".into()
    } else {
        cleaned
    }
}

fn tag_file(path: &Path, title: &str, artist: &str, album: &str) {
    if title.trim().is_empty() && artist.trim().is_empty() && album.trim().is_empty() {
        return;
    }
    use lofty::config::WriteOptions;
    use lofty::prelude::*;
    let Ok(mut tagged) = lofty::read_from_path(path) else {
        return;
    };
    let tag_type = tagged.primary_tag_type();
    if tagged.primary_tag().is_none() {
        tagged.insert_tag(lofty::tag::Tag::new(tag_type));
    }
    if let Some(tag) = tagged.primary_tag_mut() {
        if !title.trim().is_empty() {
            tag.set_title(title.trim().to_string());
        }
        if !artist.trim().is_empty() {
            tag.set_artist(artist.trim().to_string());
        }
        if !album.trim().is_empty() {
            tag.set_album(album.trim().to_string());
        }
    }
    let _ = tagged.save_to_path(path, WriteOptions::default());
}

fn app_data() -> PathBuf {
    let base = std::env::var("APPDATA").unwrap_or_else(|_| ".".into());
    PathBuf::from(base).join("ONYX")
}

fn phone_music_dir() -> PathBuf {
    app_data().join("phone-music")
}

fn hub_file() -> PathBuf {
    app_data().join("hub.json")
}

fn sync_file() -> PathBuf {
    app_data().join("sync.json")
}

fn load_hub() -> HubFile {
    fs::read_to_string(hub_file())
        .ok()
        .map(|text| text.trim_start_matches('\u{feff}').to_string())
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

fn save_hub(name: &str, devices: &[HubDevice]) {
    let file = hub_file();
    if let Some(parent) = file.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let data = HubFile {
        name: name.to_string(),
        devices: devices.to_vec(),
    };
    if let Ok(text) = serde_json::to_string_pretty(&data) {
        let _ = fs::write(file, text);
    }
}

fn load_sync() -> SyncState {
    fs::read_to_string(sync_file())
        .ok()
        .map(|text| text.trim_start_matches('\u{feff}').to_string())
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

fn save_sync(state: &SyncState) {
    let file = sync_file();
    if let Some(parent) = file.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(text) = serde_json::to_string_pretty(state) {
        let _ = fs::write(file, text);
    }
}

fn new_code() -> String {
    format!("{:06}", now_nanos() % 1_000_000)
}

fn new_token() -> String {
    format!("{:x}-{:x}", now_nanos(), std::process::id())
}

fn default_name() -> String {
    std::env::var("COMPUTERNAME")
        .ok()
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| "ONYX-PC".into())
}

/// LAN IPv4 addresses. VPN/TUN adapters (198.18.0.0/15 and similar) are
/// skipped so the phone gets an address it can actually reach.
fn lan_addresses() -> Vec<String> {
    let mut result: Vec<String> = Vec::new();
    if let Ok(interfaces) = if_addrs::get_if_addrs() {
        for interface in interfaces {
            if let if_addrs::IfAddr::V4(address) = interface.addr {
                let ip = address.ip;
                if ip.is_private() && !ip.is_loopback() {
                    let text = ip.to_string();
                    if !result.contains(&text) {
                        result.push(text);
                    }
                }
            }
        }
    }
    if result.is_empty() {
        if let Ok(socket) = UdpSocket::bind(("0.0.0.0", 0)) {
            if socket.connect(("8.8.8.8", 80)).is_ok() {
                if let Ok(address) = socket.local_addr() {
                    if !address.ip().is_loopback() {
                        result.push(address.ip().to_string());
                    }
                }
            }
        }
    }
    result.sort_by_key(|address| {
        if address.starts_with("192.168.") {
            0
        } else if address.starts_with("10.") {
            1
        } else {
            2
        }
    });
    result
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
