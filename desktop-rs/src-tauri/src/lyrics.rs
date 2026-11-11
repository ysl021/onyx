use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LyricLine {
    pub time_ms: u64,
    pub text: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Lyrics {
    pub synced: bool,
    pub lines: Vec<LyricLine>,
}

#[derive(Debug, Deserialize)]
struct LrclibTrack {
    #[serde(rename = "syncedLyrics")]
    synced_lyrics: Option<String>,
    #[serde(rename = "plainLyrics")]
    plain_lyrics: Option<String>,
}

/// LRCLIB first, with a search fallback when the exact duration does not match.
pub async fn fetch(
    artist: &str,
    title: &str,
    album: &str,
    duration_ms: u64,
) -> Option<Lyrics> {
    if artist.trim().is_empty() || title.trim().is_empty() {
        return None;
    }
    let client = reqwest::Client::builder()
        .user_agent("ONYX/0.9.0")
        .build()
        .ok()?;
    let duration = (duration_ms / 1000).to_string();
    let response = client
        .get("https://lrclib.net/api/get")
        .query(&[
            ("artist_name", artist),
            ("track_name", title),
            ("album_name", album),
            ("duration", duration.as_str()),
        ])
        .send()
        .await
        .ok()?;

    if response.status().is_success() {
        if let Ok(track) = response.json::<LrclibTrack>().await {
            if let Some(lyrics) = parse_track(track) {
                return Some(lyrics);
            }
        }
    }

    let response = client
        .get("https://lrclib.net/api/search")
        .query(&[("artist_name", artist), ("track_name", title)])
        .send()
        .await
        .ok()?;
    let items = response.json::<Vec<LrclibTrack>>().await.ok()?;
    items.into_iter().find_map(parse_track)
}

fn parse_track(track: LrclibTrack) -> Option<Lyrics> {
    if let Some(synced) = track.synced_lyrics.filter(|text| !text.trim().is_empty()) {
        let lines = parse_lrc(&synced);
        if !lines.is_empty() {
            return Some(Lyrics {
                synced: true,
                lines,
            });
        }
    }
    let plain = track.plain_lyrics.filter(|text| !text.trim().is_empty())?;
    let lines = plain
        .lines()
        .map(|text| LyricLine {
            time_ms: 0,
            text: text.trim().to_string(),
        })
        .filter(|line| !line.text.is_empty())
        .collect::<Vec<_>>();
    if lines.is_empty() {
        None
    } else {
        Some(Lyrics {
            synced: false,
            lines,
        })
    }
}

fn parse_lrc(text: &str) -> Vec<LyricLine> {
    let mut lines = Vec::new();
    for raw in text.lines() {
        let mut times = Vec::new();
        let mut rest = raw.trim();
        while rest.starts_with('[') {
            let Some(end) = rest.find(']') else {
                break;
            };
            let tag = &rest[1..end];
            if let Some(milliseconds) = parse_timestamp(tag) {
                times.push(milliseconds);
            }
            rest = rest[end + 1..].trim_start();
        }
        if times.is_empty() || rest.is_empty() {
            continue;
        }
        for time in times {
            lines.push(LyricLine {
                time_ms: time,
                text: rest.to_string(),
            });
        }
    }
    lines.sort_by_key(|line| line.time_ms);
    lines
}

fn parse_timestamp(tag: &str) -> Option<u64> {
    let mut parts = tag.split(':');
    let minutes: u64 = parts.next()?.trim().parse().ok()?;
    let seconds_part = parts.next()?.trim();
    let mut seconds_parts = seconds_part.split('.');
    let seconds: u64 = seconds_parts.next()?.parse().ok()?;
    let fraction = seconds_parts.next().unwrap_or("0");
    let millis = if fraction.is_empty() {
        0
    } else {
        let digits = fraction.chars().take(3).collect::<String>();
        format!("{:0<3}", digits).parse().unwrap_or(0)
    };
    Some(minutes * 60_000 + seconds * 1000 + millis)
}
