//! Auto-update is configured in `tauri.conf.json` under `plugins.updater`.
//! The plugin is registered in `lib.rs`; `scripts/publish-desktop.ps1` builds,
//! signs and uploads `latest.json` to the `desktop-latest` GitHub release.
