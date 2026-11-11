use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// The PC settings live in the same place as the Kotlin build
/// (`%APPDATA%\ONYX\settings.json`) so the new client can reuse them.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub volume: u8,
    pub discord_enabled: bool,
    pub hub_port: u16,
    pub theme: String,
    pub language: String,
    pub music_folders: Vec<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            volume: 80,
            discord_enabled: false,
            hub_port: 8787,
            theme: "dark".into(),
            language: "ru".into(),
            music_folders: Vec::new(),
        }
    }
}

pub fn path() -> PathBuf {
    let base = std::env::var("APPDATA").unwrap_or_else(|_| ".".into());
    PathBuf::from(base).join("ONYX").join("settings.json")
}

pub fn load() -> Settings {
    fs::read_to_string(path())
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

pub fn save(settings: &Settings) -> Result<(), String> {
    let file = path();
    if let Some(parent) = file.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let text = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    fs::write(file, text).map_err(|e| e.to_string())
}
