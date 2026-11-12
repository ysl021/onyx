use crate::settings;
use crate::youtube::Video;
use serde::Serialize;
use serde_json::Value;
use std::sync::Mutex;
use std::time::Duration;

/// A playable stream plus the proxy mpv should use. Mirrors usually return a
/// URL that is already proxied by the instance, so `proxy` is `None` there.
#[derive(Debug, Clone)]
pub struct StreamHandle {
    pub url: String,
    pub duration_ms: u64,
    pub proxy: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteStatus {
    pub route: String,
    pub label: String,
    pub detail: String,
    pub relay: String,
    pub mirrors: String,
}

#[derive(Debug, Clone)]
struct LastRoute {
    route: String,
    detail: String,
}

static LAST_ROUTE: Mutex<Option<LastRoute>> = Mutex::new(None);

fn remember(route: &str, detail: impl Into<String>) {
    if let Ok(mut guard) = LAST_ROUTE.lock() {
        *guard = Some(LastRoute {
            route: route.to_string(),
            detail: detail.into(),
        });
    }
}

pub fn status() -> RouteStatus {
    let settings = settings::load();
    let (route, detail) = LAST_ROUTE
        .lock()
        .ok()
        .and_then(|guard| guard.clone())
        .map(|last| (last.route, last.detail))
        .unwrap_or_else(|| ("none".into(), "Маршрут ещё не проверялся".into()));
    RouteStatus {
        route,
        label: route_label(&settings.youtube_route),
        detail,
        relay: settings.youtube_relay_url,
        mirrors: settings.youtube_mirrors,
    }
}

fn route_label(route: &str) -> String {
    match route {
        "direct" => "Напрямую / VPN".into(),
        "relay" => "Ретранслятор".into(),
        "mirror" => "Зеркало".into(),
        _ => "Авто".into(),
    }
}

fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(15))
        .user_agent("ONYX/0.9.2")
        .build()
        .unwrap_or_else(|_| reqwest::Client::new())
}

fn mirror_bases(settings: &settings::Settings) -> Vec<String> {
    let mut bases = Vec::new();
    for raw in settings
        .youtube_mirrors
        .split([',', '\n', ';'])
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        let base = raw.trim_end_matches('/').to_string();
        if base.starts_with("http") && !bases.contains(&base) {
            bases.push(base);
        }
    }
    // This Piped instance answers search requests on the current network.
    // Others are kept as fallbacks and can be overridden in Settings.
    for base in [
        "https://api.piped.private.coffee",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.orangenet.cc",
        "https://pipedapi.ducks.party",
        "https://pipedapi.adminforge.de",
    ] {
        let base = base.to_string();
        if !bases.contains(&base) {
            bases.push(base);
        }
    }
    bases
}

fn video_from_piped(item: &Value) -> Option<Video> {
    let raw_url = item.get("url")?.as_str()?;
    let id = raw_url
        .rsplit("v=")
        .next()
        .unwrap_or(raw_url)
        .trim_start_matches('/')
        .trim();
    if id.len() != 11 {
        return None;
    }
    let duration_ms = item
        .get("duration")
        .and_then(|value| value.as_u64())
        .unwrap_or(0)
        * 1000;
    Some(Video {
        id: id.to_string(),
        title: item
            .get("title")
            .and_then(|value| value.as_str())
            .unwrap_or("")
            .to_string(),
        uploader: item
            .get("uploaderName")
            .and_then(|value| value.as_str())
            .or_else(|| item.get("uploader").and_then(|value| value.as_str()))
            .unwrap_or("")
            .to_string(),
        duration_ms,
        thumbnail: item
            .get("thumbnail")
            .and_then(|value| value.as_str())
            .unwrap_or("")
            .to_string(),
    })
}

async fn piped_search_at(base: &str, query: &str) -> Result<Vec<Video>, String> {
    let mut url = reqwest::Url::parse(&format!("{base}/search"))
        .map_err(|error| error.to_string())?;
    url.query_pairs_mut()
        .append_pair("q", query)
        .append_pair("filter", "videos");
    let response = client()
        .get(url)
        .send()
        .await
        .map_err(|error| error.to_string())?;
    if !response.status().is_success() {
        return Err(format!("HTTP {}", response.status()));
    }
    let root: Value = response.json().await.map_err(|error| error.to_string())?;
    let items = root
        .get("items")
        .and_then(|value| value.as_array())
        .cloned()
        .unwrap_or_default();
    let videos: Vec<Video> = items.iter().filter_map(video_from_piped).collect();
    if videos.is_empty() {
        return Err("Зеркало не вернуло результаты".into());
    }
    Ok(videos)
}

pub async fn piped_search(query: &str) -> Result<(Vec<Video>, String), String> {
    let settings = settings::load();
    let mut last_error = String::from("нет доступных зеркал");
    for base in mirror_bases(&settings) {
        match piped_search_at(&base, query).await {
            Ok(videos) => return Ok((videos, base)),
            Err(error) => {
                last_error = format!("{base}: {error}");
                crate::process::log(&format!("[mirror] search {last_error}"));
            }
        }
    }
    Err(last_error)
}

async fn piped_stream_at(base: &str, id: &str) -> Result<StreamHandle, String> {
    let response = client()
        .get(format!("{base}/streams/{id}"))
        .send()
        .await
        .map_err(|error| error.to_string())?;
    if !response.status().is_success() {
        return Err(format!("HTTP {}", response.status()));
    }
    let root: Value = response.json().await.map_err(|error| error.to_string())?;
    let audio = root
        .get("audioStreams")
        .and_then(|value| value.as_array())
        .cloned()
        .unwrap_or_default();
    let best = audio
        .iter()
        .filter_map(|item| {
            let url = item
                .get("proxyUrl")
                .and_then(|value| value.as_str())
                .or_else(|| item.get("url").and_then(|value| value.as_str()))?;
            let bitrate = item
                .get("bitrate")
                .and_then(|value| value.as_u64())
                .unwrap_or(0);
            Some((bitrate, url.to_string()))
        })
        .max_by_key(|(bitrate, _)| *bitrate);
    let (_, url) = best.ok_or("Зеркало не вернуло аудиопоток")?;
    let duration_ms = root
        .get("duration")
        .and_then(|value| value.as_u64())
        .unwrap_or(0)
        * 1000;
    Ok(StreamHandle {
        url,
        duration_ms,
        proxy: None,
    })
}

pub async fn piped_stream(id: &str) -> Result<(StreamHandle, String), String> {
    let settings = settings::load();
    let mut last_error = String::from("нет доступных зеркал");
    for base in mirror_bases(&settings) {
        match piped_stream_at(&base, id).await {
            Ok(stream) => return Ok((stream, base)),
            Err(error) => {
                last_error = format!("{base}: {error}");
                crate::process::log(&format!("[mirror] stream {last_error}"));
            }
        }
    }
    Err(format!(
        "Зеркала не отдают аудиопоток. Нужен VPN или ретранслятор. ({last_error})"
    ))
}

pub async fn piped_related(id: &str, title: &str, artist: &str) -> Result<Vec<Video>, String> {
    let settings = settings::load();
    let mut last_error = String::from("нет доступных зеркал");
    for base in mirror_bases(&settings) {
        let response = client()
            .get(format!("{base}/streams/{id}"))
            .send()
            .await;
        if let Ok(response) = response {
            if response.status().is_success() {
                if let Ok(root) = response.json::<Value>().await {
                    let items = root
                        .get("relatedStreams")
                        .and_then(|value| value.as_array())
                        .cloned()
                        .unwrap_or_default();
                    let videos: Vec<Video> =
                        items.iter().filter_map(video_from_piped).collect();
                    if !videos.is_empty() {
                        return Ok(videos);
                    }
                }
            }
        }
        // The stream endpoint is often disabled on public instances, while
        // search still works. A search by artist/title is a useful fallback.
        let query = format!("{artist} {title}").trim().to_string();
        if query.is_empty() {
            continue;
        }
        match piped_search_at(&base, &query).await {
            Ok(videos) => {
                let filtered: Vec<Video> = videos
                    .into_iter()
                    .filter(|video| video.id != id)
                    .take(20)
                    .collect();
                if !filtered.is_empty() {
                    return Ok(filtered);
                }
            }
            Err(error) => last_error = format!("{base}: {error}"),
        }
    }
    Err(last_error)
}

/// The user's own relay: a tiny JSON API.
///   GET {base}/search?q=...  -> { "items": [Video...] }
///   GET {base}/stream?id=... -> { "url": "...", "duration": 123 }
///   GET {base}/related?id=...-> { "items": [Video...] }
async fn relay_search_at(base: &str, query: &str) -> Result<Vec<Video>, String> {
    let mut url = reqwest::Url::parse(&format!("{base}/search"))
        .map_err(|error| error.to_string())?;
    url.query_pairs_mut().append_pair("q", query);
    let response = client()
        .get(url)
        .send()
        .await
        .map_err(|error| error.to_string())?;
    if !response.status().is_success() {
        return Err(format!("HTTP {}", response.status()));
    }
    let root: Value = response.json().await.map_err(|error| error.to_string())?;
    let items = root
        .get("items")
        .or_else(|| root.get("videos"))
        .and_then(|value| value.as_array())
        .cloned()
        .unwrap_or_default();
    let videos: Vec<Video> = items.iter().filter_map(video_from_piped).collect();
    if videos.is_empty() {
        return Err("Ретранслятор не вернул результаты".into());
    }
    Ok(videos)
}

async fn relay_stream_at(base: &str, id: &str) -> Result<StreamHandle, String> {
    let mut url = reqwest::Url::parse(&format!("{base}/stream"))
        .map_err(|error| error.to_string())?;
    url.query_pairs_mut().append_pair("id", id);
    let response = client()
        .get(url)
        .send()
        .await
        .map_err(|error| error.to_string())?;
    if !response.status().is_success() {
        return Err(format!("HTTP {}", response.status()));
    }
    let root: Value = response.json().await.map_err(|error| error.to_string())?;
    let stream_url = root
        .get("url")
        .and_then(|value| value.as_str())
        .ok_or("Ретранслятор не вернул URL")?
        .to_string();
    let duration_ms = root
        .get("duration")
        .and_then(|value| value.as_u64())
        .unwrap_or(0)
        * 1000;
    Ok(StreamHandle {
        url: stream_url,
        duration_ms,
        proxy: None,
    })
}

async fn relay_related_at(base: &str, id: &str) -> Result<Vec<Video>, String> {
    let mut url = reqwest::Url::parse(&format!("{base}/related"))
        .map_err(|error| error.to_string())?;
    url.query_pairs_mut().append_pair("id", id);
    let response = client()
        .get(url)
        .send()
        .await
        .map_err(|error| error.to_string())?;
    if !response.status().is_success() {
        return Err(format!("HTTP {}", response.status()));
    }
    let root: Value = response.json().await.map_err(|error| error.to_string())?;
    let items = root
        .get("items")
        .and_then(|value| value.as_array())
        .cloned()
        .unwrap_or_default();
    Ok(items.iter().filter_map(video_from_piped).collect())
}

fn relay_url(settings: &settings::Settings) -> Option<String> {
    let url = settings.youtube_relay_url.trim().trim_end_matches('/');
    if url.starts_with("http") {
        Some(url.to_string())
    } else {
        None
    }
}

async fn direct_search(query: &str, music_only: bool) -> Result<Vec<Video>, String> {
    crate::youtube::search(query, music_only).await
}

async fn direct_stream(id: &str) -> Result<StreamHandle, String> {
    let proxy = tauri::async_runtime::spawn_blocking(crate::youtube::active_proxy)
        .await
        .map_err(|error| error.to_string())?
        .ok_or("Нет рабочего VPN/прокси для прямого маршрута")?;
    let url = crate::youtube::resolve(id, &proxy).await?;
    Ok(StreamHandle {
        url,
        duration_ms: 0,
        proxy: Some(proxy),
    })
}

pub async fn search(query: &str, music_only: bool) -> Result<Vec<Video>, String> {
    let settings = settings::load();
    let mode = settings.youtube_route.as_str();
    let mut errors = Vec::new();

    if mode == "relay" {
        if let Some(base) = relay_url(&settings) {
            match relay_search_at(&base, query).await {
                Ok(videos) => {
                    remember("relay", &base);
                    return Ok(videos);
                }
                Err(error) => errors.push(format!("релей: {error}")),
            }
        } else {
            errors.push("релей: URL не указан".into());
        }
    }

    if mode == "mirror" || mode == "auto" || mode == "relay" {
        match piped_search(query).await {
            Ok((videos, base)) => {
                remember("mirror", &base);
                return Ok(videos);
            }
            Err(error) => errors.push(format!("зеркала: {error}")),
        }
    }

    if mode == "direct" {
        match direct_search(query, music_only).await {
            Ok(videos) => {
                remember("direct", "yt-dlp");
                return Ok(videos);
            }
            Err(error) => errors.push(format!("напрямую: {error}")),
        }
    }

    if mode == "direct" {
        // An explicit direct route may still fall back to a mirror.
        match piped_search(query).await {
            Ok((videos, base)) => {
                remember("mirror", &base);
                return Ok(videos);
            }
            Err(error) => errors.push(format!("зеркала: {error}")),
        }
    }

    let error = errors.join(" · ");
    crate::process::log(&format!("[youtube] search failed: {error}"));
    Err(error)
}

pub async fn stream(id: &str, title: &str, artist: &str) -> Result<StreamHandle, String> {
    let settings = settings::load();
    let mode = settings.youtube_route.as_str();
    let last = LAST_ROUTE
        .lock()
        .ok()
        .and_then(|guard| guard.clone())
        .map(|value| value.route)
        .unwrap_or_default();
    let mut order: Vec<&str> = Vec::new();
    if mode == "mirror" {
        order.extend(["mirror", "relay"]);
    } else if mode == "relay" {
        order.extend(["relay", "mirror"]);
    } else if mode == "direct" {
        order.extend(["direct", "mirror", "relay"]);
    } else {
        if last == "relay" {
            order.extend(["relay", "mirror"]);
        } else {
            order.extend(["mirror", "relay"]);
        }
    }

    let mut errors = Vec::new();
    for route in order {
        match route {
            "mirror" => match piped_stream(id).await {
                Ok((stream, base)) => {
                    remember("mirror", &base);
                    return Ok(stream);
                }
                Err(error) => errors.push(format!("зеркала: {error}")),
            },
            "relay" => {
                if let Some(base) = relay_url(&settings) {
                    match relay_stream_at(&base, id).await {
                        Ok(stream) => {
                            remember("relay", &base);
                            return Ok(stream);
                        }
                        Err(error) => errors.push(format!("релей: {error}")),
                    }
                }
            }
            "direct" => match direct_stream(id).await {
                Ok(stream) => {
                    remember("direct", "yt-dlp");
                    return Ok(stream);
                }
                Err(error) => errors.push(format!("напрямую: {error}")),
            },
            _ => {}
        }
    }
    let _ = (title, artist);
    let error = errors.join(" · ");
    crate::process::log(&format!("[youtube] stream {id} failed: {error}"));
    Err(format!("Не удалось получить поток YouTube. {error}"))
}

pub async fn related(id: &str, title: &str, artist: &str) -> Result<Vec<Video>, String> {
    let settings = settings::load();
    let mode = settings.youtube_route.as_str();
    let mut errors = Vec::new();

    if mode == "mirror" || mode == "auto" || mode == "relay" {
        match piped_related(id, title, artist).await {
            Ok(videos) => {
                remember("mirror", "рекомендации зеркала");
                return Ok(videos);
            }
            Err(error) => errors.push(format!("зеркала: {error}")),
        }
    }
    if mode == "relay" || mode == "auto" {
        if let Some(base) = relay_url(&settings) {
            match relay_related_at(&base, id).await {
                Ok(videos) if !videos.is_empty() => {
                    remember("relay", &base);
                    return Ok(videos);
                }
                Ok(_) => errors.push("релей: пусто".into()),
                Err(error) => errors.push(format!("релей: {error}")),
            }
        }
    }
    if mode == "direct" {
        let query = format!("{artist} {title}").trim().to_string();
        if !query.is_empty() {
            match direct_search(&query, true).await {
                Ok(videos) => {
                    let filtered: Vec<Video> = videos
                        .into_iter()
                        .filter(|video| video.id != id)
                        .take(20)
                        .collect();
                    if !filtered.is_empty() {
                        remember("direct", "поиск по треку");
                        return Ok(filtered);
                    }
                }
                Err(error) => errors.push(format!("напрямую: {error}")),
            }
        }
    }
    let error = errors.join(" · ");
    crate::process::log(&format!("[youtube] related {id} failed: {error}"));
    Err(error)
}

/// A small home feed for the empty YouTube tab. Piped search works without a
/// VPN, so a few rotating music queries are enough to make the tab useful.
pub async fn home(seed: Option<String>) -> Result<Vec<Video>, String> {
    let queries = [
        "trending music",
        "top hits",
        "new music",
        "chill mix",
        "russian pop",
        "phonk",
    ];
    let mut all: Vec<Video> = Vec::new();
    let mut last_error = String::from("нет доступных зеркал");
    if let Some(seed) = seed.as_deref().map(str::trim).filter(|value| !value.is_empty()) {
        if let Ok((videos, base)) = piped_search(seed).await {
            remember("mirror", &base);
            all.extend(videos);
            // A seed search already gives a useful feed. Do not wait for
            // several generic queries when the first result set is enough.
            if all.len() >= 12 {
                let mut seen = std::collections::HashSet::new();
                all.retain(|video| seen.insert(video.id.clone()));
                all.truncate(24);
                return Ok(all);
            }
        }
    }
    for query in queries {
        if all.len() >= 24 {
            break;
        }
        match piped_search(query).await {
            Ok((videos, base)) => {
                remember("mirror", &base);
                all.extend(videos);
            }
            Err(error) => last_error = error,
        }
    }
    let mut seen = std::collections::HashSet::new();
    all.retain(|video| seen.insert(video.id.clone()));
    all.truncate(24);
    if all.is_empty() {
        Err(last_error)
    } else {
        Ok(all)
    }
}

/// A quick route check used by Settings → Integrations.
pub async fn check() -> RouteStatus {
    let settings = settings::load();
    let mode = settings.youtube_route.as_str();
    let mut errors = Vec::new();

    if mode == "direct" {
        match direct_search("test music", true).await {
            Ok(_) => {
                remember("direct", "yt-dlp через прокси");
                return status();
            }
            Err(error) => errors.push(format!("напрямую: {error}")),
        }
    } else {
        if mode == "relay" || mode == "auto" {
            if let Some(base) = relay_url(&settings) {
                match relay_search_at(&base, "test music").await {
                    Ok(_) => {
                        remember("relay", format!("{base} отвечает"));
                        return status();
                    }
                    Err(error) => errors.push(format!("релей: {error}")),
                }
            }
        }
        match piped_search("test music").await {
            Ok((_, base)) => {
                remember("mirror", format!("{base} отвечает"));
                return status();
            }
            Err(error) => errors.push(format!("зеркала: {error}")),
        }
    }

    let detail = errors.join(" · ");
    crate::process::log(&format!("[youtube] route check failed: {detail}"));
    RouteStatus {
        route: "none".into(),
        label: route_label(&settings.youtube_route),
        detail,
        relay: settings.youtube_relay_url,
        mirrors: settings.youtube_mirrors,
    }
}
