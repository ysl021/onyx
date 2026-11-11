# ONYX Desktop (Rust + Tauri + Svelte)

Новый ПК-клиент. Старая Kotlin/Compose-версия остаётся в `../desktop/` как
эталон функционала и дизайна, пока паритет не достигнут.

## Что уже есть

- Окно Tauri 2 с тёмной темой ONYX (палитра из `Theme.kt`).
- Svelte 5 + TypeScript + Vite, боковая навигация по 7 разделам.
- Каркас плеера (плейсхолдер), IPC-команды:
  - `app_version`
  - `load_settings` / `save_settings`
  - `player_state` / `player_toggle` / `player_load`
- Настройки читаются и пишутся в тот же `%APPDATA%\ONYX\settings.json`,
  что и Kotlin-версия.
- Сборка MSI и NSIS через `tauri build`.

## Требования

- Rust (stable) и `cargo`.
- Node.js 20.19+ / 22.12+ и npm.
- WebView2 Runtime (на Windows 10/11 обычно уже установлен).
- Для плеера позже: `mpv.exe` и `yt-dlp.exe` как sidecar-бинарники.

## Команды

```powershell
cd desktop-rs
npm install
npm run tauri dev      # dev-режим с горячей перезагрузкой
npm run tauri build    # MSI + NSIS в src-tauri/target/release/bundle/
```

Готовые установщики:

- `src-tauri/target/release/bundle/msi/ONYX_0.9.0_x64_ru-RU.msi`
- `src-tauri/target/release/bundle/nsis/ONYX_0.9.0_x64-setup.exe`

## Структура

```
desktop-rs/
  src/                     Svelte-фронтенд
    App.svelte             оболочка и навигация
    app.css                палитра, шрифты, glass
    lib/components/        Glass и другие компоненты
    lib/stores/            состояние плеера
  src-tauri/
    src/main.rs            точка входа
    src/lib.rs             Tauri builder и IPC
    src/commands.rs        команды для фронтенда
    src/settings.rs        %APPDATA%\ONYX\settings.json
    src/player.rs          каркас mpv sidecar + IPC
    src/hub.rs             TODO: TCP 8787 + UDP 8788
    src/discord.rs         TODO: Rich Presence
    src/update.rs          TODO: Tauri updater
    tauri.conf.json        окно, бандлы, идентификатор
    capabilities/          разрешения Tauri
```

## Дальше по этапам

1. **mpv**: положить `mpv.exe` в `src-tauri/binaries/` (Tauri externalBin),
   запускать с `--input-ipc-server=\\.\pipe\onyx-mpv-<pid>`, подключить
   `player_load`/`player_state`/`player_toggle` к JSON IPC.
2. **Библиотека**: `walkdir` + `lofty`, кэш в `%APPDATA%\ONYX\library.json`.
3. **Экраны**: перенести 7 экранов из `../desktop/src/main/kotlin/.../ui/`,
   сверяя скриншоты.
4. **YouTube**: `yt-dlp.exe` sidecar, innerTube, локальный прокси.
5. **Discord**: IPC-канал, тот же payload (artist в state, album в `large_text`).
6. **Hub**: протокол должен совпадать с Android-клиентом (`SyncModels.kt`).
7. **Автообновление**: `tauri-plugin-updater`, minisign-ключи, `latest.json`
   на GitHub Releases.
8. **MSI**: Authenticode-подпись (опционально), иконка, версия из
   `../version.properties`.

## Важно

- Android-клиент остаётся на Kotlin/Compose и не входит в этот проект.
- Hub-протокол и `%APPDATA%\ONYX` нельзя ломать: иначе телефон и старые
  настройки перестанут работать.
