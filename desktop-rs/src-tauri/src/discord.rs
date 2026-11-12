use crate::hub::RemotePresence;
use crate::player::PlayerHandle;
use serde_json::{json, Value};
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
#[cfg(windows)]
use std::os::windows::io::AsRawHandle;
#[cfg(windows)]
use windows_sys::Win32::Foundation::HANDLE;
#[cfg(windows)]
use windows_sys::Win32::System::Pipes::PeekNamedPipe;

/// ONYX's Discord application id (the same one the Kotlin desktop build uses).
pub const DEFAULT_CLIENT_ID: &str = "1568847197397717073";

/// Shared connection flag so the UI can show a real status dot instead of a
/// hard-coded one. The presence worker is the only writer.
static CONNECTED: AtomicBool = AtomicBool::new(false);
static LAST_ERROR: Mutex<String> = Mutex::new(String::new());

pub fn is_connected() -> bool {
    CONNECTED.load(Ordering::SeqCst)
}

pub fn last_error() -> String {
    LAST_ERROR
        .lock()
        .map(|value| value.clone())
        .unwrap_or_default()
}

fn set_error(message: impl Into<String>) {
    let message = message.into();
    if let Ok(mut guard) = LAST_ERROR.lock() {
        *guard = message.clone();
    }
    crate::process::log(&format!("[discord] {message}"));
}

const OP_HANDSHAKE: u32 = 0;
const OP_FRAME: u32 = 1;
const OP_CLOSE: u32 = 2;
const OP_PING: u32 = 3;
const OP_PONG: u32 = 4;

/// Minimal Discord IPC client for Windows. Reads and writes go through the
/// `\\.\pipe\discord-ipc-N` named pipe; a reader thread keeps the handshake and
/// answers PING frames.
pub struct DiscordRpc {
    pipe: Arc<Mutex<Option<File>>>,
    connected: Arc<AtomicBool>,
}

impl DiscordRpc {
    pub fn connect(client_id: &str) -> Option<Self> {
        if client_id.trim().is_empty() {
            return None;
        }
        for slot in 0..10 {
            let path = format!(r"\\.\pipe\discord-ipc-{slot}");
            let Ok(file) = OpenOptions::new().read(true).write(true).open(&path) else {
                continue;
            };
            let Ok(reader) = file.try_clone() else {
                continue;
            };
            let pipe = Arc::new(Mutex::new(Some(file)));
            let connected = Arc::new(AtomicBool::new(false));
            let ready = Arc::new(AtomicBool::new(false));
            spawn_reader(reader, Arc::clone(&pipe), Arc::clone(&connected), Arc::clone(&ready));
            let rpc = Self {
                pipe,
                connected,
            };
            if !rpc.send(
                OP_HANDSHAKE,
                &json!({"v": 1, "client_id": client_id}).to_string(),
            ) {
                rpc.close();
                continue;
            }
            let deadline = Instant::now() + Duration::from_secs(3);
            while Instant::now() < deadline {
                if ready.load(Ordering::SeqCst) {
                    rpc.connected.store(true, Ordering::SeqCst);
                    crate::process::log(&format!("[discord] connected on pipe {slot}"));
                    return Some(rpc);
                }
                thread::sleep(Duration::from_millis(25));
            }
            rpc.close();
        }
        set_error("Discord не запущен или IPC недоступен");
        None
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::SeqCst)
    }

    pub fn set_activity(&self, activity: Value) -> bool {
        if !self.is_connected() {
            set_error("Discord не подключён");
            return false;
        }
        let payload = json!({
            "cmd": "SET_ACTIVITY",
            "nonce": new_nonce(),
            "args": {
                "pid": std::process::id(),
                "activity": activity
            }
        });
        let ok = self.send(OP_FRAME, &payload.to_string());
        if !ok {
            set_error("Не удалось отправить активность в Discord");
        }
        ok
    }

    pub fn clear(&self) {
        if !self.is_connected() {
            return;
        }
        let payload = json!({
            "cmd": "SET_ACTIVITY",
            "nonce": new_nonce(),
            "args": {
                "pid": std::process::id(),
                "activity": Value::Null
            }
        });
        self.send(OP_FRAME, &payload.to_string());
    }

    pub fn close(&self) {
        self.connected.store(false, Ordering::SeqCst);
        if let Ok(mut pipe) = self.pipe.lock() {
            *pipe = None;
        }
    }

    fn send(&self, op: u32, payload: &str) -> bool {
        let Ok(mut guard) = self.pipe.lock() else {
            return false;
        };
        let Some(file) = guard.as_mut() else {
            return false;
        };
        write_frame(file, op, payload)
    }
}

impl Drop for DiscordRpc {
    fn drop(&mut self) {
        self.close();
    }
}

fn spawn_reader(
    mut reader: File,
    pipe: Arc<Mutex<Option<File>>>,
    connected: Arc<AtomicBool>,
    ready: Arc<AtomicBool>,
) {
    #[cfg(windows)]
    let handle = reader.as_raw_handle() as usize;
    thread::spawn(move || {
        loop {
            // On Windows a pending ReadFile blocks the next WriteFile on the
            // same pipe. Only read when a full frame is actually available.
            #[cfg(windows)]
            if !wait_for_bytes(handle, 8) {
                break;
            }
            let mut header = [0u8; 8];
            if !read_exact(&mut reader, &mut header) {
                break;
            }
            let op = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
            let length =
                u32::from_le_bytes([header[4], header[5], header[6], header[7]]) as usize;
            if length > 4 * 1024 * 1024 {
                break;
            }
            #[cfg(windows)]
            if !wait_for_bytes(handle, length as u32) {
                break;
            }
            let mut data = vec![0u8; length];
            if !read_exact(&mut reader, &mut data) {
                break;
            }
            let text = String::from_utf8_lossy(&data).to_string();
            match op {
                OP_PING => {
                    if let Ok(mut guard) = pipe.lock() {
                        if let Some(file) = guard.as_mut() {
                            write_frame(file, OP_PONG, &text);
                        }
                    }
                }
                OP_CLOSE => {
                    crate::process::log("[discord] close frame");
                    break;
                }
                OP_FRAME => {
                    crate::process::log(&format!("[discord] frame {text}"));
                    if let Ok(value) = serde_json::from_str::<Value>(&text) {
                        match value.get("evt").and_then(Value::as_str) {
                            Some("READY") => {
                                ready.store(true, Ordering::SeqCst);
                                connected.store(true, Ordering::SeqCst);
                                if let Ok(mut guard) = LAST_ERROR.lock() {
                                    guard.clear();
                                }
                                crate::process::log("[discord] READY");
                            }
                            Some("ERROR") => {
                                set_error(format!("Discord: {value}"));
                            }
                            _ => {}
                        }
                    }
                }
                _ => {}
            }
        }
        connected.store(false, Ordering::SeqCst);
    });
}

#[cfg(windows)]
fn wait_for_bytes(handle: usize, needed: u32) -> bool {
    let handle = handle as HANDLE;
    loop {
        let mut available: u32 = 0;
        let ok = unsafe {
            PeekNamedPipe(
                handle,
                std::ptr::null_mut(),
                0,
                std::ptr::null_mut(),
                &mut available,
                std::ptr::null_mut(),
            )
        };
        if ok == 0 {
            return false;
        }
        if available >= needed {
            return true;
        }
        thread::sleep(Duration::from_millis(15));
    }
}

fn read_exact(file: &mut File, target: &mut [u8]) -> bool {
    let mut offset = 0usize;
    while offset < target.len() {
        match file.read(&mut target[offset..]) {
            Ok(0) => return false,
            Ok(read) => offset += read,
            Err(_) => return false,
        }
    }
    true
}

fn write_frame(file: &mut File, op: u32, payload: &str) -> bool {
    let bytes = payload.as_bytes();
    let mut frame = Vec::with_capacity(8 + bytes.len());
    frame.extend_from_slice(&op.to_le_bytes());
    frame.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
    frame.extend_from_slice(bytes);
    file.write_all(&frame).is_ok()
}

fn new_nonce() -> String {
    format!(
        "{:x}{:x}",
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_nanos())
            .unwrap_or(0),
        std::process::id()
    )
}

/// Mirrors the player into Rich Presence. The phone's presence (forwarded by
/// the LAN hub) is shown while the PC itself is not playing.
pub fn spawn_presence_worker(
    player: PlayerHandle,
    remote: Arc<Mutex<Option<RemotePresence>>>,
) {
    thread::spawn(move || {
        let mut rpc: Option<DiscordRpc> = None;
        let mut last_key = String::new();
        let mut last_playing = false;
        let mut last_position = 0u64;
        let mut last_position_at = Instant::now();
        let mut last_sent = Instant::now() - Duration::from_secs(60);
        let mut last_cover: Option<String> = None;
        let cover_state: Arc<Mutex<(String, Option<String>)>> =
            Arc::new(Mutex::new((String::new(), None)));

        loop {
            let settings = crate::settings::load();
            if !settings.discord_enabled {
                // Keep the IPC connection alive: closing it would leave the
                // reader thread holding a handle, and a second connection with
                // the same client id makes Discord drop both.
                CONNECTED.store(false, Ordering::SeqCst);
                if let Some(client) = rpc.as_ref() {
                    client.clear();
                }
                last_key.clear();
                last_cover = None;
                thread::sleep(Duration::from_secs(2));
                continue;
            }

            let client_id = if settings.discord_client_id.trim().is_empty() {
                DEFAULT_CLIENT_ID
            } else {
                settings.discord_client_id.trim()
            };
            if rpc.as_ref().map(|client| !client.is_connected()).unwrap_or(true) {
                CONNECTED.store(false, Ordering::SeqCst);
                if let Some(client) = rpc.take() {
                    client.close();
                }
                rpc = DiscordRpc::connect(client_id);
                CONNECTED.store(rpc.is_some(), Ordering::SeqCst);
                last_key.clear();
                last_cover = None;
            }
            let Some(client) = rpc.as_ref() else {
                CONNECTED.store(false, Ordering::SeqCst);
                thread::sleep(Duration::from_secs(3));
                continue;
            };
            CONNECTED.store(client.is_connected(), Ordering::SeqCst);

            let state = player.state();
            let state_path = state.path.clone();
            let remote_track = remote
                .lock()
                .ok()
                .and_then(|value| value.clone())
                .filter(|presence| presence.playing && !presence.title.trim().is_empty());

            let (title, artist, album, playing, position, duration, key) =
                if state.playing && !state.title.trim().is_empty() {
                    let key = format!("pc:{}|{}", state.title, state.artist);
                    (
                        state.title,
                        state.artist,
                        state.album,
                        true,
                        state.position_ms,
                        state.duration_ms,
                        key,
                    )
                } else if let Some(presence) = remote_track {
                    let key = format!("phone:{}|{}", presence.title, presence.artist);
                    (
                        presence.title,
                        presence.artist,
                        presence.album,
                        presence.playing,
                        presence.position_ms,
                        presence.duration_ms,
                        key,
                    )
                } else if !state.title.trim().is_empty() {
                    let key = format!("pc:{}|{}", state.title, state.artist);
                    (
                        state.title,
                        state.artist,
                        state.album,
                        false,
                        state.position_ms,
                        state.duration_ms,
                        key,
                    )
                } else {
                    if !last_key.is_empty() {
                        client.clear();
                        last_key.clear();
                        last_cover = None;
                    }
                    thread::sleep(Duration::from_secs(2));
                    continue;
                };

            if key != last_key {
                let cover_state = Arc::clone(&cover_state);
                let cover_key = key.clone();
                let cover_title = title.clone();
                let cover_artist = artist.clone();
                let cover_album = album.clone();
                let cover_path = state_path.clone();
                thread::spawn(move || {
                    let url = crate::covers::resolve(
                        &cover_title,
                        &cover_artist,
                        &cover_album,
                        if cover_path.is_empty() {
                            None
                        } else {
                            Some(&cover_path)
                        },
                    );
                    if let Ok(mut guard) = cover_state.lock() {
                        *guard = (cover_key, url);
                    }
                });
            }

            let cover_url = cover_state
                .lock()
                .ok()
                .and_then(|guard| {
                    if guard.0 == key {
                        guard.1.clone()
                    } else {
                        None
                    }
                });

            let now = Instant::now();
            let expected = last_position
                + if last_playing {
                    now.duration_since(last_position_at).as_millis() as u64
                } else {
                    0
                };
            let seeked = key == last_key
                && last_playing == playing
                && position.abs_diff(expected) > 3_000;
            let changed =
                key != last_key || playing != last_playing || cover_url != last_cover;
            let heartbeat = last_sent.elapsed() >= Duration::from_secs(15);

            if changed || seeked || heartbeat {
                let activity = build_activity(
                    &title,
                    &artist,
                    &album,
                    playing,
                    position,
                    duration,
                    cover_url.as_deref(),
                );
                crate::process::log(&format!("[discord] activity {activity}"));
                if client.set_activity(activity) {
                    last_key = key;
                    last_playing = playing;
                    last_position = position;
                    last_position_at = now;
                    last_sent = now;
                    last_cover = cover_url;
                } else {
                    crate::process::log("[discord] set_activity failed");
                }
            }
            thread::sleep(Duration::from_secs(1));
        }
    });
}

fn build_activity(
    title: &str,
    artist: &str,
    album: &str,
    playing: bool,
    position_ms: u64,
    duration_ms: u64,
    cover_url: Option<&str>,
) -> Value {
    let artist = clean_artist(artist);
    let mut activity = json!({
        "type": 2,
        "details": truncate(title.trim(), 128),
        "state": truncate(if artist.is_empty() { "ONYX" } else { &artist }, 128),
        "buttons": [
            { "label": "GitHub", "url": "https://github.com/ysl021/onyx" }
        ]
    });
    if playing && duration_ms > 0 {
        let start = now_secs().saturating_sub(position_ms / 1000);
        activity["timestamps"] = json!({
            "start": start,
            "end": start + duration_ms / 1000
        });
    }
    let album = album.trim();
    let usable_album = !album.is_empty()
        && !album.eq_ignore_ascii_case("YouTube")
        && !album.eq_ignore_ascii_case("YouTube Music")
        && !is_source_label(&normalize(album));
    let mut assets = json!({
        "large_image": cover_url.unwrap_or("onyx_large"),
        "small_image": if playing { "playing" } else { "pause" },
        "small_text": if playing { "Playing" } else { "Paused" }
    });
    if usable_album {
        assets["large_text"] = json!(truncate(album, 128));
    }
    activity["assets"] = assets;
    activity
}

fn clean_artist(value: &str) -> String {
    let mut seen = Vec::new();
    let parts: Vec<&str> = value
        .split([',', ';', '/'])
        .map(str::trim)
        .filter(|part| !part.is_empty())
        .collect();
    for part in parts {
        let normalized = normalize(part);
        if is_source_label(&normalized) || seen.contains(&normalized) {
            continue;
        }
        seen.push(normalized);
    }
    if seen.is_empty() {
        return String::new();
    }
    // Keep the original spelling of the first occurrence of every name.
    let mut names: Vec<&str> = Vec::new();
    for part in value.split([',', ';', '/']).map(str::trim) {
        if part.is_empty() {
            continue;
        }
        let normalized = normalize(part);
        if is_source_label(&normalized) || names.iter().any(|name| normalize(name) == normalized) {
            continue;
        }
        names.push(part);
    }
    names.join(", ")
}

fn is_source_label(normalized: &str) -> bool {
    matches!(
        normalized,
        "telegram"
            | "tg"
            | "телеграм"
            | "exteragram"
            | "telegramx"
            | "spotify"
            | "youtube"
            | "youtubemusic"
            | "soundcloud"
            | "vk"
            | "vkontakte"
            | "вконтакте"
            | "music"
            | "audio"
            | "files"
            | "downloads"
            | "музыка"
            | "аудио"
            | "загрузки"
            | "локальныефайлы"
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

fn truncate(value: &str, limit: usize) -> String {
    value.chars().take(limit).collect()
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0)
}
