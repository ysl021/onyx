use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// The PC settings live in the same place as the Kotlin build
/// (`%APPDATA%\ONYX\settings.json`) so the new client can reuse them.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Settings {
    pub volume: u8,
    pub discord_enabled: bool,
    pub discord_client_id: String,
    pub hub_port: u16,
    pub hub_name: String,
    pub theme: String,
    pub language: String,
    /// bottom | top | left | right
    pub panel_position: String,
    pub audio_device: String,
    pub cover_backdrop: bool,
    pub youtube_proxy: String,
    /// auto | direct | relay | mirror
    pub youtube_route: String,
    pub youtube_relay_url: String,
    /// Extra Piped/Invidious API bases, one per line or comma separated.
    pub youtube_mirrors: String,
    pub volume_step: u8,
    pub eq_enabled: bool,
    pub eq_bands: Vec<f32>,
    pub music_folders: Vec<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            volume: 80,
            discord_enabled: false,
            discord_client_id: crate::discord::DEFAULT_CLIENT_ID.into(),
            hub_port: 8787,
            hub_name: String::new(),
            theme: "dark".into(),
            language: "ru".into(),
            panel_position: "bottom".into(),
            audio_device: "auto".into(),
            cover_backdrop: false,
            youtube_proxy: String::new(),
            youtube_route: "auto".into(),
            youtube_relay_url: String::new(),
            youtube_mirrors: String::new(),
            volume_step: 5,
            eq_enabled: false,
            eq_bands: vec![0.0; 5],
            music_folders: Vec::new(),
        }
    }
}

pub fn path() -> PathBuf {
    let base = std::env::var("APPDATA").unwrap_or_else(|_| ".".into());
    PathBuf::from(base).join("ONYX").join("settings.json")
}

pub fn load() -> Settings {
    let mut settings = fs::read_to_string(path())
        .ok()
        .and_then(|text| {
            let mut value: serde_json::Value =
                serde_json::from_str(text.trim_start_matches('\u{feff}')).ok()?;
            if let Some(object) = value.as_object_mut() {
                // The Kotlin build calls the same list `folders`; keep the two
                // clients able to read each other's file.
                let music_empty = object
                    .get("musicFolders")
                    .and_then(|value| value.as_array())
                    .map(|items| items.is_empty())
                    .unwrap_or(true);
                if music_empty {
                    if let Some(folders) = object.get("folders") {
                        object.insert("musicFolders".into(), folders.clone());
                    }
                }
                let proxy_empty = object
                    .get("youtubeProxy")
                    .and_then(|value| value.as_str())
                    .map(|value| value.trim().is_empty())
                    .unwrap_or(true);
                if proxy_empty {
                    if let Some(proxy) = object.get("ytdlpProxy") {
                        object.insert("youtubeProxy".into(), proxy.clone());
                    }
                }
                if let Some(theme) = object
                    .get("theme")
                    .and_then(|value| value.as_str())
                    .map(str::to_lowercase)
                {
                    object.insert("theme".into(), serde_json::Value::String(theme));
                }
                if let Some(panel) = object
                    .get("panelPosition")
                    .and_then(|value| value.as_str())
                    .map(str::to_lowercase)
                {
                    object.insert("panelPosition".into(), serde_json::Value::String(panel));
                }
            }
            serde_json::from_value::<Settings>(value).ok()
        })
        .unwrap_or_default();
    settings.eq_bands = (0..5)
        .map(|index| settings.eq_bands.get(index).copied().unwrap_or(0.0))
        .collect();
    let device = settings.audio_device.trim_matches('\'').trim_matches('"').trim();
    if device.eq_ignore_ascii_case("auto") || device.is_empty() {
        settings.audio_device = "auto".into();
    } else if device != settings.audio_device {
        settings.audio_device = device.to_string();
    }
    let route = settings.youtube_route.trim().to_lowercase();
    settings.youtube_route = match route.as_str() {
        "direct" | "relay" | "mirror" => route,
        _ => "auto".into(),
    };
    settings
}

pub fn save(settings: &Settings) -> Result<(), String> {
    let file = path();
    if let Some(parent) = file.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    // Merge into the existing object instead of replacing it: the Kotlin
    // client stores lyrics/theme details in the same file that this build does
    // not know about, and they must survive a save from either side.
    let mut root = fs::read_to_string(&file)
        .ok()
        .and_then(|text| {
            serde_json::from_str::<serde_json::Value>(text.trim_start_matches('\u{feff}'))
                .ok()
                .and_then(|value| value.as_object().cloned())
        })
        .unwrap_or_default();
    let own = serde_json::to_value(settings).map_err(|e| e.to_string())?;
    if let Some(object) = own.as_object() {
        for (key, value) in object {
            root.insert(key.clone(), value.clone());
        }
    }
    root.insert(
        "folders".into(),
        root.get("musicFolders")
            .cloned()
            .unwrap_or_else(|| serde_json::Value::Array(Vec::new())),
    );
    root.insert(
        "ytdlpProxy".into(),
        root.get("youtubeProxy")
            .cloned()
            .unwrap_or_else(|| serde_json::Value::String(String::new())),
    );
    let text = serde_json::to_string_pretty(&serde_json::Value::Object(root))
        .map_err(|e| e.to_string())?;
    fs::write(file, text).map_err(|e| e.to_string())
}
