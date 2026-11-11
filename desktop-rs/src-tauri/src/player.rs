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
    pub playing: bool,
    pub position_ms: u64,
    pub duration_ms: u64,
}

/// Owns the mpv sidecar and talks to it over the Windows named pipe that mpv
/// exposes with `--input-ipc-server`.
pub struct PlayerHandle {
    inner: Arc<Mutex<Inner>>,
}

struct Inner {
    child: Option<Child>,
    pipe: Option<File>,
    state: PlayerState,
}

impl PlayerHandle {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(Inner {
                child: None,
                pipe: None,
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
        self.ensure_mpv()?;
        self.send(&serde_json::json!({ "command": ["loadfile", path] }))?;
        if let Ok(mut inner) = self.inner.lock() {
            inner.state = PlayerState {
                playing: true,
                ..PlayerState::default()
            };
        }
        Ok(())
    }

    pub fn toggle(&self) -> Result<(), String> {
        self.ensure_mpv()?;
        self.send(&serde_json::json!({ "command": ["cycle", "pause"] }))
    }

    pub fn seek(&self, seconds: f64) -> Result<(), String> {
        self.send(&serde_json::json!({ "command": ["seek", seconds, "absolute"] }))
    }

    pub fn set_volume(&self, volume: u8) -> Result<(), String> {
        self.send(&serde_json::json!({
            "command": ["set_property", "volume", volume.min(100)]
        }))
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
        let child = Command::new(&mpv)
            .args(["--no-video", "--idle=yes", "--force-window=no", ipc.as_str()])
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
                        inner.state.title = meta(&data, "title");
                        inner.state.artist = meta(&data, "artist");
                        inner.state.album = meta(&data, "album");
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

fn find_mpv() -> Option<PathBuf> {
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
