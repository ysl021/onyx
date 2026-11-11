use serde::Serialize;
use serde_json::Value;
use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerState {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub path: String,
    pub playing: bool,
    pub position_ms: u64,
    pub duration_ms: u64,
}

/// Owns the mpv sidecar and talks to it over the Windows named pipe that mpv
/// exposes with `--input-ipc-server`.
pub struct PlayerHandle {
    inner: Arc<Mutex<Inner>>,
}

impl Clone for PlayerHandle {
    fn clone(&self) -> Self {
        Self {
            inner: Arc::clone(&self.inner),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioDevice {
    pub name: String,
    pub description: String,
}

struct Inner {
    child: Option<Child>,
    pipe: Option<File>,
    volume: u8,
    state: PlayerState,
}

impl PlayerHandle {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(Inner {
                child: None,
                pipe: None,
                volume: 100,
                state: PlayerState::default(),
            })),
        }
    }

    pub fn state(&self) -> PlayerState {
        self.inner
            .lock()
            .map(|inner| inner.state.clone())
            .unwrap_or_default()
    }

    pub fn load(&self, path: &str) -> Result<(), String> {
        self.load_with_proxy(path, None)
    }

    pub fn load_with_proxy(&self, path: &str, proxy: Option<&str>) -> Result<(), String> {
        self.ensure_mpv()?;
        if let Some(proxy) = proxy {
            self.send(&serde_json::json!({
                "command": ["set_property", "http-proxy", proxy]
            }))?;
        }
        self.send(&serde_json::json!({ "command": ["loadfile", path] }))?;
        let mut state = PlayerState {
            playing: true,
            path: path.to_string(),
            ..PlayerState::default()
        };
        if std::path::Path::new(path).exists() {
            if let Some(track) = crate::library::read_track(std::path::Path::new(path)) {
                state.title = track.title;
                state.artist = track.artist;
                state.album = track.album;
                state.duration_ms = track.duration_ms;
            }
        }
        if let Ok(mut inner) = self.inner.lock() {
            inner.state = state;
        }
        Ok(())
    }

    /// YouTube tracks have no tags; the frontend passes the search metadata.
    pub fn set_metadata(&self, title: &str, artist: &str, album: &str, duration_ms: u64) {
        if let Ok(mut inner) = self.inner.lock() {
            if !title.trim().is_empty() {
                inner.state.title = title.to_string();
            }
            if !artist.trim().is_empty() {
                inner.state.artist = artist.to_string();
            }
            if !album.trim().is_empty() {
                inner.state.album = album.to_string();
            }
            if duration_ms > 0 {
                inner.state.duration_ms = duration_ms;
            }
        }
    }

    pub fn toggle(&self) -> Result<(), String> {
        self.ensure_mpv()?;
        self.send(&serde_json::json!({ "command": ["cycle", "pause"] }))
    }

    pub fn seek(&self, seconds: f64) -> Result<(), String> {
        self.send(&serde_json::json!({ "command": ["seek", seconds, "absolute"] }))
    }

    pub fn set_volume(&self, volume: u8) -> Result<(), String> {
        let volume = volume.min(100);
        let mut inner = self.inner.lock().map_err(|error| error.to_string())?;
        inner.volume = volume;
        if let Some(pipe) = inner.pipe.as_mut() {
            let line = format!(
                "{{\"command\":[\"set_property\",\"volume\",{volume}]}}\n"
            );
            pipe.write_all(line.as_bytes())
                .map_err(|error| error.to_string())?;
        }
        Ok(())
    }

    pub fn pause(&self) -> Result<(), String> {
        self.send(&serde_json::json!({
            "command": ["set_property", "pause", true]
        }))
    }

    pub fn set_audio_device(&self, device: &str) -> Result<(), String> {
        // Do not start mpv just to change a setting; it is applied on load.
        let _ = self.send(&serde_json::json!({
            "command": ["set_property", "audio-device", device]
        }));
        Ok(())
    }

    pub fn set_eq(&self, enabled: bool, bands: &[f32]) -> Result<(), String> {
        let value = if enabled {
            build_equalizer(bands)
        } else {
            String::new()
        };
        // Do not start mpv just to change the EQ; it is applied on load.
        let _ = self.send(&serde_json::json!({
            "command": ["set_property", "af", value]
        }));
        Ok(())
    }

    fn send(&self, value: &Value) -> Result<(), String> {
        let mut inner = self.inner.lock().map_err(|e| e.to_string())?;
        let pipe = inner.pipe.as_mut().ok_or("mpv is not running")?;
        pipe.write_all(format!("{value}\n").as_bytes())
            .map_err(|e| e.to_string())
    }

    fn ensure_mpv(&self) -> Result<(), String> {
        if let Ok(inner) = self.inner.lock() {
            if inner.child.is_some() && inner.pipe.is_some() {
                return Ok(());
            }
        }
        self.spawn_mpv()
    }

    fn spawn_mpv(&self) -> Result<(), String> {
        let mpv = find_mpv().ok_or(
            "mpv.exe не найден. Установите mpv или положите mpv.exe рядом с ONYX.",
        )?;
        let pipe_path = format!(r"\\.\pipe\onyx-mpv-{}", std::process::id());
        let ipc = format!("--input-ipc-server={pipe_path}");
        let volume = self
            .inner
            .lock()
            .map(|inner| inner.volume)
            .unwrap_or(100);
        let volume_arg = format!("--volume={volume}");
        let child = Command::new(&mpv)
            .args([
                "--no-video",
                "--idle=yes",
                "--force-window=no",
                ipc.as_str(),
                volume_arg.as_str(),
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| format!("Не удалось запустить mpv: {e}"))?;

        let deadline = Instant::now() + Duration::from_secs(5);
        let pipe = loop {
            match OpenOptions::new().read(true).write(true).open(&pipe_path) {
                Ok(file) => break file,
                Err(_) if Instant::now() < deadline => thread::sleep(Duration::from_millis(50)),
                Err(e) => return Err(format!("mpv IPC: {e}")),
            }
        };

        let reader = pipe.try_clone().map_err(|e| e.to_string())?;
        {
            let mut inner = self.inner.lock().map_err(|e| e.to_string())?;
            inner.child = Some(child);
            inner.pipe = Some(pipe);
            inner.state = PlayerState::default();
        }

        let inner = Arc::clone(&self.inner);
        thread::spawn(move || {
            let mut reader = BufReader::new(reader);
            let mut line = String::new();
            loop {
                line.clear();
                match reader.read_line(&mut line) {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {}
                }
                let Ok(value) = serde_json::from_str::<Value>(line.trim()) else {
                    continue;
                };
                if value.get("event").and_then(Value::as_str) != Some("property-change") {
                    continue;
                }
                let Some(name) = value.get("name").and_then(Value::as_str) else {
                    continue;
                };
                let data = value.get("data").cloned().unwrap_or(Value::Null);
                let Ok(mut inner) = inner.lock() else {
                    break;
                };
                match name {
                    "time-pos" => {
                        if let Some(sec) = data.as_f64() {
                            inner.state.position_ms = (sec.max(0.0) * 1000.0) as u64;
                        }
                    }
                    "duration" => {
                        if let Some(sec) = data.as_f64() {
                            inner.state.duration_ms = (sec.max(0.0) * 1000.0) as u64;
                        }
                    }
                    "pause" => {
                        inner.state.playing = !data.as_bool().unwrap_or(false);
                    }
                    "metadata" => {
                        let title = meta(&data, "title");
                        let artist = meta(&data, "artist");
                        let album = meta(&data, "album");
                        if !title.is_empty() {
                            inner.state.title = title;
                        }
                        if !artist.is_empty() {
                            inner.state.artist = artist;
                        }
                        if !album.is_empty() {
                            inner.state.album = album;
                        }
                    }
                    _ => {}
                }
            }
        });

        for (id, property) in [
            (1, "time-pos"),
            (2, "duration"),
            (3, "pause"),
            (4, "metadata"),
        ] {
            self.send(&serde_json::json!({
                "command": ["observe_property", id, property]
            }))?;
        }
        Ok(())
    }
}

impl Drop for PlayerHandle {
    fn drop(&mut self) {
        if let Ok(mut inner) = self.inner.lock() {
            if let Some(mut child) = inner.child.take() {
                let _ = child.kill();
            }
        }
    }
}

fn build_equalizer(bands: &[f32]) -> String {
    const FREQS: [f32; 5] = [60.0, 230.0, 910.0, 3600.0, 14000.0];
    let filters: Vec<String> = FREQS
        .iter()
        .enumerate()
        .map(|(index, frequency)| {
            let gain = bands.get(index).copied().unwrap_or(0.0).clamp(-12.0, 12.0);
            format!("equalizer=f={frequency}:t=q:w=1:g={gain:.1}")
        })
        .collect();
    format!("lavfi=[{}]", filters.join(","))
}

fn meta(data: &Value, key: &str) -> String {
    if let Some(object) = data.as_object() {
        for (name, value) in object {
            if name.eq_ignore_ascii_case(key) {
                return value.as_str().unwrap_or("").to_string();
            }
        }
    }
    String::new()
}

/// Lists the audio outputs mpv can use. Runs `mpv --audio-device=help`; the
/// first entry is always `auto`.
pub fn audio_devices() -> Vec<AudioDevice> {
    let Some(mpv) = find_mpv() else {
        return Vec::new();
    };
    let output = Command::new(&mpv)
        .args(["--no-config", "--audio-device=help"])
        .stdin(Stdio::null())
        .output();
    let Ok(output) = output else {
        return Vec::new();
    };
    let text = String::from_utf8_lossy(&output.stdout);
    let mut devices = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with("Available") || line.starts_with("List") {
            continue;
        }
        let (name, description) = match line.rfind('(') {
            Some(index) if line.ends_with(')') => (
                line[..index].trim().to_string(),
                line[index + 1..line.len() - 1].trim().to_string(),
            ),
            _ => (line.to_string(), line.to_string()),
        };
        if name.is_empty() || name == "auto" || name == "no" {
            continue;
        }
        devices.push(AudioDevice { name, description });
    }
    devices
}

pub(crate) fn find_mpv() -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Ok(local) = std::env::var("LOCALAPPDATA") {
        candidates.push(PathBuf::from(local).join("ONYX").join("mpv").join("mpv.exe"));
    }
    candidates.push(PathBuf::from(r"C:\Program Files\MPV Player\mpv.exe"));
    candidates.push(PathBuf::from(r"C:\Program Files\mpv\mpv.exe"));
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("mpv.exe"));
            candidates.push(dir.join("resources").join("mpv.exe"));
        }
    }
    if let Ok(path) = std::env::var("PATH") {
        for dir in std::env::split_paths(&path) {
            candidates.push(dir.join("mpv.exe"));
        }
    }
    candidates.into_iter().find(|path| path.exists())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Manual integration check: `ONYX_TEST_FILE=... cargo test -- --nocapture`.
    #[test]
    fn mpv_loads_a_file_and_reports_duration() {
        let Ok(path) = std::env::var("ONYX_TEST_FILE") else {
            return;
        };
        let player = PlayerHandle::new();
        player.load(&path).expect("mpv load");
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            let state = player.state();
            if state.duration_ms > 0 {
                println!(
                    "mpv ok: duration={}ms title={:?} artist={:?}",
                    state.duration_ms, state.title, state.artist
                );
                return;
            }
            if Instant::now() >= deadline {
                panic!("mpv did not report a duration for {path}");
            }
            thread::sleep(Duration::from_millis(200));
        }
    }
}
