use std::ffi::OsStr;
use std::process::Command;

/// Spawns a console program without flashing a console window. ONYX is a GUI
/// app, so yt-dlp/mpv/taskkill must never steal focus or open a terminal.
#[cfg(windows)]
pub fn hidden_command<S: AsRef<OsStr>>(program: S) -> Command {
    use std::os::windows::process::CommandExt;
    let mut command = Command::new(program);
    // CREATE_NO_WINDOW
    command.creation_flags(0x0800_0000);
    command
}

#[cfg(not(windows))]
pub fn hidden_command<S: AsRef<OsStr>>(program: S) -> Command {
    Command::new(program)
}

/// Appends one line to `%APPDATA%\ONYX\desktop.log`. Best effort: logging must
/// never break playback or a command.
pub fn log(message: &str) {
    use std::io::Write;
    let base = std::env::var("APPDATA").unwrap_or_else(|_| ".".into());
    let path = std::path::PathBuf::from(base)
        .join("ONYX")
        .join("desktop.log");
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
    {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|value| value.as_secs())
            .unwrap_or(0);
        let _ = writeln!(file, "[{now}] {message}");
    }
}
