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
- Очередь, следующий/предыдущий, таймер сна, любимые треки.
- Плейлисты: создание, переименование, удаление, добавление текущего трека,
  запуск плейлиста.
- Текст: LRCLIB, синхронные строки, подсветка, перемотка по строке,
  размер и смещение.
- YouTube: поиск через yt-dlp и стриминг в mpv. Нужен VPN/прокси
  (Happ/Clash/v2rayN); без него вкладка покажет ошибку.
- Настройки: панель, тема (тёмная/светлая), громкость, папки музыки.
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
- `yt-dlp.exe` для YouTube: положить в
  `src-tauri/binaries/yt-dlp-x86_64-pc-windows-msvc.exe`.

## Команды

```powershell
cd desktop-rs
npm install
# один раз: скопировать sidecar-бинарники
New-Item -ItemType Directory -Force src-tauri/binaries
Copy-Item "C:\Program Files\MPV Player\mpv.exe" `
  "src-tauri/binaries/mpv-x86_64-pc-windows-msvc.exe"
Copy-Item "..\desktop\libs\yt-dlp.exe" `
  "src-tauri/binaries/yt-dlp-x86_64-pc-windows-msvc.exe"
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
    lib/screens/           Player, Lyrics, Playlists, Music, YouTube, Settings
    lib/stores/            player, settings, library, queue, favorites, sleep,
                           playlists, lyrics, youtube
  src-tauri/
    src/main.rs            точка входа
    src/lib.rs             Tauri builder и IPC
    src/commands.rs        команды для фронтенда
    src/settings.rs        %APPDATA%\ONYX\settings.json
    src/player.rs          mpv sidecar + JSON IPC
    src/library.rs         walkdir + lofty, теги и обложки
    src/playlists.rs       rust-playlists.json
    src/lyrics.rs          LRCLIB + LRC
    src/youtube.rs         yt-dlp поиск/стриминг, авто-прокси
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
3. ~~**Плеер**: очередь, следующий/предыдущий, таймер сна, любимые.~~ Готово.
4. ~~**Текст**: LRCLIB, синхронные строки, подсветка.~~ Готово.
5. ~~**Плейлисты**: создание, добавление, запуск.~~ Готово.
6. ~~**YouTube**: поиск и стриминг через yt-dlp.~~ Готово (нужен прокси).
7. **Визуал**: FFT-спектр из PCM-потока mpv.
8. **YouTube**: скачивание и нарезка, очередь загрузок.
9. **Discord**: IPC-канал, тот же payload (artist в state, album в `large_text`).
10. **Hub**: протокол должен совпадать с Android-клиентом (`SyncModels.kt`).
11. **Автообновление**: `tauri-plugin-updater`, minisign-ключи, `latest.json`
    на GitHub Releases.
12. **MSI**: Authenticode-подпись (опционально), иконка, версия из
    `../version.properties`.

## Важно

- Android-клиент остаётся на Kotlin/Compose и не входит в этот проект.
- Hub-протокол и `%APPDATA%\ONYX` нельзя ломать: иначе телефон и старые
  настройки перестанут работать.
- YouTube требует локальный прокси (Happ/Clash/v2rayN на
  `127.0.0.1:20808`, `10809` или `7890`). Без него поиск завершится
  ошибкой через 20 секунд.
