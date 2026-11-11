use serde::Serialize;
use std::io::Write;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerState {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub playing: bool,
    pub position_ms: u64,
    pub duration_ms: u64,
}

pub struct PlayerHandle {
    inner: Mutex<Inner>,
}

struct Inner {
    child: Option<Child>,
    pipe: String,
    playing: bool,
}

impl PlayerHandle {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(Inner {
                child: None,
                pipe: String::new(),
                playing: false,
            }),
        }
    }

    pub fn state(&self) -> PlayerState {
        let inner = self.inner.lock().expect("player mutex poisoned");
        PlayerState {
            title: String::new(),
            artist: String::new(),
            album: String::new(),
            playing: inner.playing,
            position_ms: 0,
            duration_ms: 0,
        }
    }

    pub fn toggle(&self) {
        let mut inner = self.inner.lock().expect("player mutex poisoned");
        inner.playing = !inner.playing;
    }

    pub fn load(&self, path: &str) -> Result<(), String> {
        // TODO: spawn the bundled mpv.exe sidecar with
        // `--input-ipc-server=\\.\pipe\onyx-mpv-<pid>` and send
        // {"command":["loadfile", path]} over the pipe.
        let _ = path;
        Err("mpv sidecar is not wired yet".into())
    }

    /// Spawns `mpv.exe` as a sidecar and remembers its IPC pipe.
    #[allow(dead_code)]
    fn spawn_mpv(&self, mpv: &str) -> Result<(), String> {
        let mut inner = self.inner.lock().expect("player mutex poisoned");
        if inner.child.is_some() {
            return Ok(());
        }
        let pipe = format!(r"\\.\pipe\onyx-mpv-{}", std::process::id());
        let child = Command::new(mpv)
            .args([
                "--no-video",
                "--idle=yes",
                "--force-window=no",
                "--input-ipc-server",
                &pipe,
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| e.to_string())?;
        inner.child = Some(child);
        inner.pipe = pipe;
        Ok(())
    }

    /// Sends one JSON line to the mpv IPC pipe.
    #[allow(dead_code)]
    fn command(&self, json: &str) -> Result<(), String> {
        let inner = self.inner.lock().expect("player mutex poisoned");
        if inner.pipe.is_empty() {
            return Err("mpv is not running".into());
        }
        let mut pipe = std::fs::OpenOptions::new()
            .write(true)
            .open(&inner.pipe)
            .map_err(|e| e.to_string())?;
        pipe.write_all(json.as_bytes()).map_err(|e| e.to_string())?;
        pipe.write_all(b"\n").map_err(|e| e.to_string())?;
        Ok(())
    }
}
