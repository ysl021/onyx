use crate::library::Library;
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use wait_timeout::ChildExt;

static PROXY_CACHE: Mutex<Option<(String, Instant)>> = Mutex::new(None);
const PROXY_CACHE_MS: u64 = 60_000;

const PROXY_CANDIDATES: [&str; 5] = [
    "http://127.0.0.1:20808", // Happ
    "http://127.0.0.1:10809", // v2rayN / xray HTTP
    "http://127.0.0.1:7890",  // Clash
    "socks5://127.0.0.1:10808",
    "socks5://127.0.0.1:1080",
];

/// The VPN client exposes a local proxy but often leaves the Windows system
/// proxy untouched. yt-dlp has no route to YouTube without it. An open port is
/// not enough: the proxy must actually tunnel a CONNECT to YouTube.
pub fn active_proxy() -> Option<String> {
    {
        let cache = proxy_cache();
        if let Some((proxy, at)) = cache.as_ref() {
            let ttl = if proxy.is_empty() {
                30_000
            } else {
                PROXY_CACHE_MS as u128
            };
            if at.elapsed().as_millis() < ttl {
                return if proxy.is_empty() {
                    None
                } else {
                    Some(proxy.clone())
                };
            }
        }
    }

    let manual = crate::settings::load().youtube_proxy;
    let manual = manual.trim();
    if !manual.is_empty() {
        log_proxy(&format!("manual {manual}"));
        if proxy_works(manual) {
            remember_proxy(manual);
            return Some(manual.to_string());
        }
    }

    for candidate in PROXY_CANDIDATES {
        log_proxy(&format!("try {candidate}"));
        if proxy_works(candidate) {
            log_proxy(&format!("{candidate} ok"));
            remember_proxy(candidate);
            return Some(candidate.to_string());
        }
    }
    log_proxy("none");
    remember_proxy("");
    None
}

/// Checks a proxy end-to-end: HTTP CONNECT + TLS, or SOCKS5 + TLS.
pub fn proxy_works(candidate: &str) -> bool {
    let Some(host_port) = candidate.split("://").nth(1) else {
        return false;
    };
    let Ok(address) = host_port.parse::<SocketAddr>() else {
        return false;
    };
    let Ok(mut stream) = TcpStream::connect_timeout(&address, Duration::from_millis(500))
    else {
        return false;
    };
    if candidate.starts_with("socks") {
        return socks5_works(&mut stream);
    }
    let _ = stream.set_read_timeout(Some(Duration::from_millis(2_000)));
    let _ = stream.set_write_timeout(Some(Duration::from_millis(1_000)));
    let request = "CONNECT www.youtube.com:443 HTTP/1.1\r\nHost: www.youtube.com:443\r\n\r\n";
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let mut buffer = [0u8; 128];
    let read = stream.read(&mut buffer).unwrap_or(0);
    let response = String::from_utf8_lossy(&buffer[..read]);
    response.starts_with("HTTP/") && response.contains(" 200") && tls_works(&mut stream)
}

fn log_proxy(message: &str) {
    use std::io::Write;
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(std::env::temp_dir().join("onyx-proxy.log"))
    {
        let _ = writeln!(file, "{message}");
    }
}

fn remember_proxy(proxy: &str) {
    let mut cache = proxy_cache();
    *cache = Some((proxy.to_string(), Instant::now()));
}

/// Drops the cached proxy after a real request failed, so the next attempt
/// verifies the candidates again.
pub fn invalidate_proxy() {
    let mut cache = proxy_cache();
    *cache = None;
}

fn proxy_cache() -> std::sync::MutexGuard<'static, Option<(String, Instant)>> {
    PROXY_CACHE
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
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

pub async fn search(query: &str, music_only: bool) -> Result<Vec<Video>, String> {
    let query = query.trim().to_string();
    if query.is_empty() {
        return Ok(Vec::new());
    }
    let query = if music_only {
        format!("{query} music")
    } else {
        query
    };
    let proxy = active_proxy().ok_or(
        "Включи VPN/прокси для YouTube (Happ/Clash) или укажи прокси в настройках.",
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
        run_with_timeout(command, Duration::from_secs(10))
    })
    .await
    .map_err(|error| error.to_string())??;

    if !output.status.success() {
        invalidate_proxy();
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    let root: serde_json::Value =
        serde_json::from_slice(&output.stdout).map_err(|error| error.to_string())?;
    Ok(parse_videos(&root))
}

fn parse_videos(root: &serde_json::Value) -> Vec<Video> {
    let entries = root
        .get("entries")
        .and_then(|value| value.as_array())
        .cloned()
        .unwrap_or_default();
    entries
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
        .collect()
}

/// Resolves a YouTube mix/playlist URL into its tracks.
pub async fn playlist(url: &str) -> Result<Vec<Video>, String> {
    let url = url.trim().to_string();
    if url.is_empty() {
        return Ok(Vec::new());
    }
    let proxy = active_proxy().ok_or(
        "Включи VPN/прокси для YouTube (Happ/Clash) или укажи прокси в настройках.",
    )?;
    let ytdlp = find_ytdlp().ok_or("yt-dlp.exe не найден")?;
    let args = vec![
        url,
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
        invalidate_proxy();
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    let root: serde_json::Value =
        serde_json::from_slice(&output.stdout).map_err(|error| error.to_string())?;
    Ok(parse_videos(&root))
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
        invalidate_proxy();
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    String::from_utf8_lossy(&output.stdout)
        .lines()
        .next()
        .map(|line| line.trim().to_string())
        .filter(|line| !line.is_empty())
        .ok_or_else(|| "yt-dlp не вернул ссылку".to_string())
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DownloadSection {
    pub start_ms: u64,
    pub end_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadJob {
    pub id: String,
    pub video_id: String,
    pub title: String,
    pub artist: String,
    /// queued | downloading | converting | done | error | cancelled
    pub status: String,
    pub progress: f32,
    pub message: String,
    pub outputs: Vec<String>,
    pub created_at: u64,
}

struct QueueState {
    jobs: Vec<DownloadJob>,
    sections: std::collections::HashMap<String, Vec<DownloadSection>>,
    running: bool,
    cancel: bool,
    timed_out: bool,
    pid: Option<u32>,
}

/// Sequential YouTube download queue. yt-dlp fetches the audio and the bundled
/// mpv encoder trims it (`--start/--end --of=mp3`), so no external ffmpeg is
/// required.
pub struct DownloadQueue {
    library: Arc<Library>,
    state: Arc<Mutex<QueueState>>,
}

impl DownloadQueue {
    pub fn new(library: Arc<Library>) -> Self {
        Self {
            library,
            state: Arc::new(Mutex::new(QueueState {
                jobs: Vec::new(),
                sections: std::collections::HashMap::new(),
                running: false,
                cancel: false,
                timed_out: false,
                pid: None,
            })),
        }
    }

    pub fn list(&self) -> Vec<DownloadJob> {
        self.state
            .lock()
            .map(|state| state.jobs.clone())
            .unwrap_or_default()
    }

    pub fn add(
        self: &Arc<Self>,
        video_id: String,
        title: String,
        artist: String,
        sections: Vec<DownloadSection>,
    ) -> Vec<DownloadJob> {
        if video_id.trim().is_empty() {
            return self.list();
        }
        let job = DownloadJob {
            id: format!("{:x}", now_nanos()),
            video_id: video_id.trim().to_string(),
            title: if title.trim().is_empty() {
                "YouTube".into()
            } else {
                title.trim().to_string()
            },
            artist: artist.trim().to_string(),
            status: "queued".into(),
            progress: 0.0,
            message: if sections.is_empty() {
                "В очереди".into()
            } else {
                format!("В очереди · {} фрагмент(ов)", sections.len())
            },
            outputs: Vec::new(),
            created_at: now_ms(),
        };
        let job_id = job.id.clone();
        let (jobs, spawn) = {
            let mut state = match self.state.lock() {
                Ok(state) => state,
                Err(_) => return self.list(),
            };
            state.jobs.push(job);
            state.sections.insert(job_id, sections);
            state.jobs.retain(|job| {
                job.status != "done"
                    || job.created_at + 1000 * 60 * 60 * 24 * 3 > now_ms()
            });
            let spawn = !state.running;
            if spawn {
                state.running = true;
            }
            (state.jobs.clone(), spawn)
        };
        if spawn {
            let queue = Arc::clone(self);
            thread::spawn(move || queue.run());
        }
        jobs
    }

    pub fn cancel(&self, id: &str) -> Vec<DownloadJob> {
        if let Ok(mut state) = self.state.lock() {
            let status = state
                .jobs
                .iter()
                .find(|job| job.id == id)
                .map(|job| job.status.clone());
            match status.as_deref() {
                Some("queued") => {
                    if let Some(job) = state.jobs.iter_mut().find(|job| job.id == id) {
                        job.status = "cancelled".into();
                        job.message = "Отменено".into();
                    }
                }
                Some("downloading") | Some("converting") => {
                    let pid = state.pid;
                    state.cancel = true;
                    if let Some(pid) = pid {
                        let _ = Command::new("taskkill")
                            .args(["/PID", &pid.to_string(), "/F"])
                            .output();
                    }
                    if let Some(job) = state.jobs.iter_mut().find(|job| job.id == id) {
                        job.message = "Отмена…".into();
                    }
                }
                _ => {}
            }
            state.jobs.clone()
        } else {
            Vec::new()
        }
    }

    pub fn remove(&self, id: &str) -> Vec<DownloadJob> {
        if let Ok(mut state) = self.state.lock() {
            state.jobs.retain(|job| {
                job.id != id || matches!(job.status.as_str(), "downloading" | "converting" | "queued")
            });
            state.jobs.clone()
        } else {
            Vec::new()
        }
    }

    pub fn clear_finished(&self) -> Vec<DownloadJob> {
        if let Ok(mut state) = self.state.lock() {
            state.jobs.retain(|job| {
                !matches!(job.status.as_str(), "done" | "error" | "cancelled")
            });
            state.jobs.clone()
        } else {
            Vec::new()
        }
    }

    fn run(self: Arc<Self>) {
        loop {
            let (job, sections) = {
                let mut state = match self.state.lock() {
                    Ok(state) => state,
                    Err(_) => return,
                };
                let Some(index) = state
                    .jobs
                    .iter()
                    .position(|job| job.status == "queued")
                else {
                    state.running = false;
                    return;
                };
                state.cancel = false;
                state.timed_out = false;
                state.jobs[index].status = "downloading".into();
                state.jobs[index].message = "Скачиваю аудио…".into();
                state.jobs[index].progress = 0.0;
                let job = state.jobs[index].clone();
                let sections = state.sections.get(&job.id).cloned().unwrap_or_default();
                (job, sections)
            };

            let result = self.process(&job, &sections);

            if let Ok(mut state) = self.state.lock() {
                state.pid = None;
                let cancelled = state.cancel;
                if let Some(entry) = state.jobs.iter_mut().find(|item| item.id == job.id) {
                    match result {
                        Ok(outputs) => {
                            entry.status = "done".into();
                            entry.progress = 100.0;
                            entry.outputs = outputs;
                            entry.message = "Готово".into();
                        }
                        Err(message) => {
                            if cancelled || message == "Отменено" {
                                entry.status = "cancelled".into();
                                entry.message = "Отменено".into();
                            } else {
                                entry.status = "error".into();
                                entry.message = message;
                            }
                        }
                    }
                }
                state.cancel = false;
                state.timed_out = false;
            }
        }
    }

    fn process(self: &Arc<Self>, job: &DownloadJob, sections: &[DownloadSection]) -> Result<Vec<String>, String> {
        let proxy = active_proxy().ok_or(
            "Включи VPN/прокси для YouTube (Happ/Clash) или укажи прокси в настройках.",
        )?;
        let ytdlp = find_ytdlp().ok_or("yt-dlp.exe не найден")?;
        let temp = std::env::temp_dir().join(format!("onyx-yt-{}", job.id));
        let _ = fs::remove_dir_all(&temp);
        fs::create_dir_all(&temp).map_err(|error| error.to_string())?;

        let template = temp.join(format!("{}.%(ext)s", job.video_id));
        let url = format!("https://www.youtube.com/watch?v={}", job.video_id);
        let mut command = Command::new(&ytdlp);
        command.args([
            "-f",
            "bestaudio[ext=m4a]/bestaudio",
            "--no-playlist",
            "--no-warnings",
            "--newline",
            "--proxy",
            proxy.as_str(),
            "-o",
            template.to_string_lossy().as_ref(),
            url.as_str(),
        ]);
        command
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .stdin(Stdio::null());
        let mut child = command.spawn().map_err(|error| error.to_string())?;
        let pid = child.id();
        if let Ok(mut state) = self.state.lock() {
            state.pid = Some(pid);
        }
        // yt-dlp can wait forever on a broken proxy. Kill it after 45 seconds
        // and report a timeout instead of leaving the queue stuck.
        let watchdog_state = Arc::clone(&self.state);
        thread::spawn(move || {
            thread::sleep(Duration::from_secs(45));
            if let Ok(mut state) = watchdog_state.lock() {
                if state.pid == Some(pid) {
                    state.timed_out = true;
                    let _ = Command::new("taskkill")
                        .args(["/PID", &pid.to_string(), "/F"])
                        .output();
                }
            }
        });
        let stderr = child.stderr.take();
        let stderr_thread = stderr.map(|mut stderr| {
            thread::spawn(move || {
                let mut text = String::new();
                let _ = stderr.read_to_string(&mut text);
                text
            })
        });
        if let Some(stdout) = child.stdout.take() {
            let reader = BufReader::new(stdout);
            for line in reader.lines().map_while(Result::ok) {
                if let Some(percent) = parse_progress(&line) {
                    self.update(&job.id, |entry| {
                        entry.progress = percent.min(88.0);
                        entry.message = format!("Скачиваю… {:.0}%", percent);
                    });
                }
            }
        }
        let status = child.wait().map_err(|error| error.to_string())?;
        if let Ok(mut state) = self.state.lock() {
            if state.pid == Some(pid) {
                state.pid = None;
            }
        }
        let errors = stderr_thread
            .and_then(|handle| handle.join().ok())
            .unwrap_or_default();
        if self.is_cancelled() {
            let _ = fs::remove_dir_all(&temp);
            return Err("Отменено".into());
        }
        if self.timed_out() {
            invalidate_proxy();
            let _ = fs::remove_dir_all(&temp);
            return Err("yt-dlp не ответил вовремя. Проверь VPN/прокси.".into());
        }
        if !status.success() {
            invalidate_proxy();
            let _ = fs::remove_dir_all(&temp);
            return Err(trim_error(&errors));
        }

        let source = find_downloaded(&temp, &job.video_id)
            .ok_or("yt-dlp не сохранил файл")?;
        let settings = crate::settings::load();
        let (output_dir, add_folder) = output_folder(&settings);
        fs::create_dir_all(&output_dir).map_err(|error| error.to_string())?;
        if add_folder {
            let mut next = settings.clone();
            next.music_folders.push(output_dir.to_string_lossy().to_string());
            let _ = crate::settings::save(&next);
        }

        let base = sanitize_file_name(&format!(
            "{}{}{}",
            job.artist,
            if job.artist.is_empty() { "" } else { " - " },
            job.title
        ));
        let base = if base.trim().is_empty() {
            sanitize_file_name(&job.video_id)
        } else {
            base
        };

        let mut outputs = Vec::new();
        if sections.is_empty() {
            let extension = source
                .extension()
                .and_then(|value| value.to_str())
                .unwrap_or("m4a")
                .to_ascii_lowercase();
            if matches!(extension.as_str(), "mp3" | "m4a" | "flac" | "ogg" | "opus" | "wav") {
                let destination = output_dir.join(format!("{base}.{extension}"));
                fs::copy(&source, &destination).map_err(|error| error.to_string())?;
                outputs.push(destination.to_string_lossy().to_string());
            } else {
                let destination = output_dir.join(format!("{base}.mp3"));
                self.update(&job.id, |entry| {
                    entry.status = "converting".into();
                    entry.progress = 90.0;
                    entry.message = "Конвертирую в MP3…".into();
                });
                encode(&source, &destination, 0.0, 0.0)?;
                outputs.push(destination.to_string_lossy().to_string());
            }
        } else {
            let mpv = crate::player::find_mpv().ok_or("mpv.exe не найден")?;
            for (index, section) in sections.iter().enumerate() {
                let start = section.start_ms as f64 / 1000.0;
                let end = section.end_ms as f64 / 1000.0;
                if end <= start {
                    continue;
                }
                let destination = if sections.len() == 1 {
                    output_dir.join(format!("{base}.mp3"))
                } else {
                    output_dir.join(format!("{base} (part {}).mp3", index + 1))
                };
                self.update(&job.id, |entry| {
                    entry.status = "converting".into();
                    entry.progress = 90.0 + 10.0 * index as f32 / sections.len() as f32;
                    entry.message = format!("Нарезаю {}/{}…", index + 1, sections.len());
                });
                encode_with(&mpv, &source, &destination, start, end)?;
                outputs.push(destination.to_string_lossy().to_string());
            }
        }

        let _ = fs::remove_dir_all(&temp);
        if outputs.is_empty() {
            return Err("Не удалось сохранить ни одного фрагмента".into());
        }
        self.update(&job.id, |entry| {
            entry.progress = 100.0;
            entry.message = "Обновляю библиотеку…".into();
        });
        let folders = crate::settings::load().music_folders;
        self.library.scan(&folders);
        Ok(outputs)
    }

    fn update(&self, id: &str, change: impl FnOnce(&mut DownloadJob)) {
        if let Ok(mut state) = self.state.lock() {
            if let Some(job) = state.jobs.iter_mut().find(|job| job.id == id) {
                change(job);
            }
        }
    }

    fn is_cancelled(&self) -> bool {
        self.state
            .lock()
            .map(|state| state.cancel)
            .unwrap_or(false)
    }

    fn timed_out(&self) -> bool {
        self.state
            .lock()
            .map(|state| state.timed_out)
            .unwrap_or(false)
    }
}

fn parse_progress(line: &str) -> Option<f32> {
    let marker = line.find('%')?;
    let prefix = &line[..marker];
    let token = prefix.split_whitespace().last()?;
    token.parse::<f32>().ok()
}

fn trim_error(stderr: &str) -> String {
    let text = stderr.trim();
    if text.is_empty() {
        return "yt-dlp завершился с ошибкой".into();
    }
    text.lines()
        .rev()
        .find(|line| line.contains("ERROR") || !line.trim().is_empty())
        .unwrap_or(text)
        .chars()
        .take(300)
        .collect()
}

fn find_downloaded(dir: &Path, video_id: &str) -> Option<PathBuf> {
    fs::read_dir(dir)
        .ok()?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .find(|path| {
            path.is_file()
                && path
                    .file_name()
                    .and_then(|name| name.to_str())
                    .map(|name| name.starts_with(video_id))
                    .unwrap_or(false)
        })
}

fn output_folder(settings: &crate::settings::Settings) -> (PathBuf, bool) {
    if let Some(folder) = settings
        .music_folders
        .iter()
        .find(|folder| !folder.trim().is_empty())
    {
        return (PathBuf::from(folder), false);
    }
    let base = std::env::var("APPDATA").unwrap_or_else(|_| ".".into());
    (PathBuf::from(base).join("ONYX").join("downloads"), true)
}

fn sanitize_file_name(raw: &str) -> String {
    raw.chars()
        .map(|character| match character {
            '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            other => other,
        })
        .take(140)
        .collect::<String>()
        .trim()
        .to_string()
}

fn encode(source: &Path, destination: &Path, start: f64, end: f64) -> Result<(), String> {
    let mpv = crate::player::find_mpv().ok_or("mpv.exe не найден")?;
    encode_with(&mpv, source, destination, start, end)
}

fn encode_with(
    mpv: &Path,
    source: &Path,
    destination: &Path,
    start: f64,
    end: f64,
) -> Result<(), String> {
    let mut command = Command::new(mpv);
    command.args([
        "--no-config",
        "--no-video",
        "--really-quiet",
        &format!("--o={}", destination.display()),
        "--of=mp3",
        "--oac=libmp3lame",
    ]);
    if start > 0.0 {
        command.arg(format!("--start={start}"));
    }
    if end > start {
        command.arg(format!("--end={end}"));
    }
    command.arg(source);
    let output = command.output().map_err(|error| error.to_string())?;
    if !output.status.success() || !destination.exists() {
        let text = String::from_utf8_lossy(&output.stderr);
        return Err(format!("mpv не смог сохранить фрагмент: {}", trim_error(&text)));
    }
    Ok(())
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

/// After CONNECT, send a minimal TLS ClientHello. A TLS handshake (or even a
/// TLS alert) proves the proxy really reached YouTube; an HTML 502 from the
/// proxy does not.
fn tls_works(stream: &mut TcpStream) -> bool {
    let host = b"www.youtube.com";
    let mut sni = Vec::new();
    sni.extend_from_slice(&[0x00, 0x00]);
    let mut sni_data = Vec::new();
    sni_data.extend_from_slice(&((host.len() + 3) as u16).to_be_bytes());
    sni_data.push(0x00);
    sni_data.extend_from_slice(&(host.len() as u16).to_be_bytes());
    sni_data.extend_from_slice(host);
    sni.extend_from_slice(&(sni_data.len() as u16).to_be_bytes());
    sni.extend_from_slice(&sni_data);

    let mut extensions = Vec::new();
    extensions.extend_from_slice(&sni);
    // supported_groups: x25519, secp256r1
    extensions.extend_from_slice(&[0x00, 0x0a, 0x00, 0x06, 0x00, 0x04, 0x00, 0x1d, 0x00, 0x17]);
    // ec_point_formats: uncompressed
    extensions.extend_from_slice(&[0x00, 0x0b, 0x00, 0x02, 0x01, 0x00]);
    // signature_algorithms: rsa_pss_rsae_sha256, ecdsa_secp256r1_sha256
    extensions.extend_from_slice(&[0x00, 0x0d, 0x00, 0x06, 0x00, 0x04, 0x08, 0x04, 0x04, 0x03]);
    // supported_versions: TLS 1.2
    extensions.extend_from_slice(&[0x00, 0x2b, 0x00, 0x03, 0x02, 0x03, 0x03]);
    // extended_master_secret
    extensions.extend_from_slice(&[0x00, 0x17, 0x00, 0x00]);

    let mut hello = Vec::new();
    hello.extend_from_slice(&[0x03, 0x03]);
    hello.extend_from_slice(&[0x42; 32]);
    hello.push(0x00);
    hello.extend_from_slice(&[0x00, 0x04, 0xc0, 0x2f, 0xc0, 0x2b]);
    hello.extend_from_slice(&[0x01, 0x00]);
    hello.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
    hello.extend_from_slice(&extensions);

    let mut handshake = Vec::new();
    handshake.push(0x01);
    let length = hello.len();
    handshake.extend_from_slice(&[
        (length >> 16) as u8,
        (length >> 8) as u8,
        length as u8,
    ]);
    handshake.extend_from_slice(&hello);

    let mut record = Vec::new();
    record.extend_from_slice(&[0x16, 0x03, 0x01]);
    record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
    record.extend_from_slice(&handshake);
    if stream.write_all(&record).is_err() {
        return false;
    }
    let mut header = [0u8; 5];
    if stream.read_exact(&mut header).is_err() {
        return false;
    }
    // 0x16 = handshake, 0x15 = alert. Both come from a TLS peer.
    (header[0] == 0x16 || header[0] == 0x15) && header[1] == 0x03
}

/// SOCKS5 greeting + CONNECT to YouTube. Proves the proxy actually reaches
/// the outside world, not just that its port is open.
fn socks5_works(stream: &mut TcpStream) -> bool {
    let _ = stream.set_read_timeout(Some(Duration::from_millis(2_000)));
    let _ = stream.set_write_timeout(Some(Duration::from_millis(2_000)));
    if stream.write_all(&[0x05, 0x01, 0x00]).is_err() {
        return false;
    }
    let mut greeting = [0u8; 2];
    if stream.read_exact(&mut greeting).is_err() || greeting != [0x05, 0x00] {
        return false;
    }
    let host = b"www.youtube.com";
    let mut request = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
    request.extend_from_slice(host);
    request.extend_from_slice(&443u16.to_be_bytes());
    if stream.write_all(&request).is_err() {
        return false;
    }
    let mut reply = [0u8; 4];
    if stream.read_exact(&mut reply).is_err() {
        return false;
    }
    if reply[0] != 0x05 || reply[1] != 0x00 {
        return false;
    }
    // Drain the bound address before speaking TLS through the tunnel.
    match reply[3] {
        0x01 => {
            let mut rest = [0u8; 6];
            if stream.read_exact(&mut rest).is_err() {
                return false;
            }
        }
        0x03 => {
            let mut length = [0u8; 1];
            if stream.read_exact(&mut length).is_err() {
                return false;
            }
            let mut rest = vec![0u8; length[0] as usize + 2];
            if stream.read_exact(&mut rest).is_err() {
                return false;
            }
        }
        0x04 => {
            let mut rest = [0u8; 18];
            if stream.read_exact(&mut rest).is_err() {
                return false;
            }
        }
        _ => return false,
    }
    tls_works(stream)
}

fn find_ytdlp() -> Option<PathBuf> {    let mut candidates = Vec::new();
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
