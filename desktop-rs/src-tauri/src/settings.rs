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
        .and_then(|text| serde_json::from_str::<Settings>(&text).ok())
        .unwrap_or_default();
    settings.eq_bands = (0..5)
        .map(|index| settings.eq_bands.get(index).copied().unwrap_or(0.0))
        .collect();
    settings
}

pub fn save(settings: &Settings) -> Result<(), String> {
    let file = path();
    if let Some(parent) = file.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let text = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    fs::write(file, text).map_err(|e| e.to_string())
}
