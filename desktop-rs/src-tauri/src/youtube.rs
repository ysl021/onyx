use serde::{Deserialize, Serialize};
use std::net::{SocketAddr, TcpStream};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::Duration;
use wait_timeout::ChildExt;

const PROXY_CANDIDATES: [&str; 5] = [
    "http://127.0.0.1:20808", // Happ
    "http://127.0.0.1:10809", // v2rayN / xray HTTP
    "http://127.0.0.1:7890",  // Clash
    "socks5://127.0.0.1:10808",
    "socks5://127.0.0.1:1080",
];

/// The VPN client exposes a local proxy but often leaves the Windows system
/// proxy untouched. yt-dlp has no route to YouTube without it.
pub fn active_proxy() -> Option<String> {
    for candidate in PROXY_CANDIDATES {
        let Some(host_port) = candidate.split("://").nth(1) else {
            continue;
        };
        let Ok(address) = host_port.parse::<SocketAddr>() else {
            continue;
        };
        if TcpStream::connect_timeout(&address, Duration::from_millis(200)).is_ok() {
            return Some(candidate.to_string());
        }
    }
    None
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Video {
    pub id: String,
    pub title: String,
    pub uploader: String,
    pub duration_ms: u64,
    pub thumbnail: String,
}

pub async fn search(query: &str) -> Result<Vec<Video>, String> {
    let query = query.trim().to_string();
    if query.is_empty() {
        return Ok(Vec::new());
    }
    let proxy = active_proxy().ok_or(
        "Включи VPN/прокси для YouTube (Happ/Clash на 127.0.0.1:20808 или 7890).",
    )?;
    let ytdlp = find_ytdlp().ok_or("yt-dlp.exe не найден")?;
    let target = format!("ytsearch12:{query}");
    let args = vec![
        target,
        "--dump-single-json".into(),
        "--flat-playlist".into(),
        "--no-warnings".into(),
        "--skip-download".into(),
        "--proxy".into(),
        proxy,
    ];
    let mut command = Command::new(&ytdlp);
    command.args(args);
    let output = tauri::async_runtime::spawn_blocking(move || {
        run_with_timeout(command, Duration::from_secs(20))
    })
    .await
    .map_err(|error| error.to_string())??;

    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    let root: serde_json::Value =
        serde_json::from_slice(&output.stdout).map_err(|error| error.to_string())?;
    let entries = root
        .get("entries")
        .and_then(|value| value.as_array())
        .cloned()
        .unwrap_or_default();
    Ok(entries
        .into_iter()
        .map(|entry| Video {
            id: entry
                .get("id")
                .and_then(|value| value.as_str())
                .unwrap_or("")
                .to_string(),
            title: entry
                .get("title")
                .and_then(|value| value.as_str())
                .unwrap_or("")
                .to_string(),
            uploader: entry
                .get("uploader")
                .and_then(|value| value.as_str())
                .or_else(|| entry.get("channel").and_then(|value| value.as_str()))
                .unwrap_or("")
                .to_string(),
            duration_ms: entry
                .get("duration")
                .and_then(|value| value.as_f64())
                .map(|seconds| (seconds * 1000.0) as u64)
                .unwrap_or(0),
            thumbnail: entry
                .get("thumbnails")
                .and_then(|value| value.as_array())
                .and_then(|list| list.last())
                .and_then(|item| item.get("url"))
                .and_then(|value| value.as_str())
                .unwrap_or("")
                .to_string(),
        })
        .filter(|video| !video.id.is_empty())
        .collect())
}

pub async fn resolve(id: &str, proxy: &str) -> Result<String, String> {
    let ytdlp = find_ytdlp().ok_or("yt-dlp.exe не найден")?;
    let url = format!("https://www.youtube.com/watch?v={id}");
    let proxy = proxy.to_string();
    let mut command = Command::new(&ytdlp);
    command.args([
        "-g",
        "-f",
        "bestaudio/best",
        "--no-warnings",
        "--proxy",
        proxy.as_str(),
        url.as_str(),
    ]);
    let output = tauri::async_runtime::spawn_blocking(move || {
        run_with_timeout(command, Duration::from_secs(30))
    })
    .await
    .map_err(|error| error.to_string())??;

    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    String::from_utf8_lossy(&output.stdout)
        .lines()
        .next()
        .map(|line| line.trim().to_string())
        .filter(|line| !line.is_empty())
        .ok_or_else(|| "yt-dlp не вернул ссылку".to_string())
}

fn find_ytdlp() -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("yt-dlp.exe"));
            candidates.push(dir.join("resources").join("yt-dlp.exe"));
        }
    }
    if let Ok(path) = std::env::var("PATH") {
        for dir in std::env::split_paths(&path) {
            candidates.push(dir.join("yt-dlp.exe"));
        }
    }
    candidates.into_iter().find(|path| path.exists())
}

fn run_with_timeout(
    mut command: Command,
    timeout: Duration,
) -> Result<std::process::Output, String> {
    let mut child = command
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| error.to_string())?;
    match child
        .wait_timeout(timeout)
        .map_err(|error| error.to_string())?
    {
        Some(_) => child.wait_with_output().map_err(|error| error.to_string()),
        None => {
            let _ = child.kill();
            let _ = child.wait();
            Err("yt-dlp не ответил вовремя. Проверь VPN/прокси.".into())
        }
    }
}
