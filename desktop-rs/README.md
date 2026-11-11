# ONYX Desktop (Rust + Tauri + Svelte)

Новый ПК-клиент на Rust + Tauri + Svelte 5. Android-клиент остаётся в `../app/`.

## Что уже есть

- Окно Tauri 2 с тёмной темой ONYX (палитра из `Theme.kt`).
- Svelte 5 + TypeScript + Vite, **нижний стеклянный док** по умолчанию.
  Положение панели (снизу / сверху / слева / справа) меняется в
  «Настройках» и сохраняется в `settings.json`.
- Плеер на mpv: выбор файла, play/pause, перемотка, громкость, прогресс.
- Локальная библиотека: несколько папок, теги и обложки через `lofty`,
  список треков и запуск по клику. Папки берутся из `musicFolders` в
  `settings.json` (в Kotlin-версии это `folders`).
- `mpv.exe` встроен как Tauri sidecar (`externalBin`), поэтому установщик
  работает без отдельной установки mpv.
- Настройки читаются и пишутся в тот же `%APPDATA%\ONYX\settings.json`,
  что и Kotlin-версия.
- Сборка MSI и NSIS через `tauri build`.

## Требования

- Rust (stable) и `cargo`.
- Node.js 20.19+ / 22.12+ и npm.
- WebView2 Runtime (на Windows 10/11 обычно уже установлен).
- `mpv.exe` для сборки: положить в
  `src-tauri/binaries/mpv-x86_64-pc-windows-msvc.exe`. Бинарник большой,
  поэтому в git не входит; `tauri build` без него упадёт.
- Позже: `yt-dlp.exe` как sidecar для YouTube.

## Команды

```powershell
cd desktop-rs
npm install
# один раз: скопировать mpv.exe в sidecar
New-Item -ItemType Directory -Force src-tauri/binaries
Copy-Item "C:\Program Files\MPV Player\mpv.exe" `
  "src-tauri/binaries/mpv-x86_64-pc-windows-msvc.exe"
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
    App.svelte             оболочка, нижний док и переключение экранов
    app.css                палитра, шрифты, glass, общие кнопки
    lib/components/        Glass, Icon, NavDock
    lib/screens/           PlayerScreen, SettingsScreen, PlaceholderScreen
    lib/stores/            player и settings
  src-tauri/
    src/main.rs            точка входа
    src/lib.rs             Tauri builder и IPC
    src/commands.rs        команды для фронтенда
    src/settings.rs        %APPDATA%\ONYX\settings.json
    src/player.rs          mpv sidecar + JSON IPC
    src/hub.rs             TODO: TCP 8787 + UDP 8788
    src/discord.rs         TODO: Rich Presence
    src/update.rs          TODO: Tauri updater
    resources/             mpv-NOTICE.txt (лицензия sidecar)
    tauri.conf.json        окно, бандлы, externalBin
    capabilities/          разрешения Tauri
```

## Дальше по этапам

1. ~~**mpv**: sidecar, `--input-ipc-server`, play/pause, seek.~~ Готово.
2. ~~**Библиотека**: `walkdir` + `lofty`, теги, обложки, экран «Музыка».~~ Готово.
3. **Экраны**: перенести 7 экранов из Android-клиента (`../app/src/main/java/app/onyx/ui/`),
   адаптировав под ПК и сверив скриншоты.
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
