# ONYX Desktop (Rust + Tauri + Svelte)

ПК-клиент ONYX на Rust + Tauri 2 + Svelte 5. Android-клиент остаётся в
`../app/` на Kotlin/Compose.

## Что уже есть

**Дизайн**
- Телефонная раскладка: одна колонка, тёмные карточки, белые активные
  пилюли, uppercase-лейблы, стеклянный док.
- Док снизу по умолчанию; положение (снизу / сверху / слева / справа)
  меняется в «Настройках».
- Активная вкладка — белая пилюля, как на телефоне.
- Фон под обложку текущего трека — отдельный переключатель в
  «Настройки → Внешний вид».
- Тёмная и светлая темы.

**Плеер**
- mpv sidecar, play/pause, seek, громкость с настраиваемым шагом.
- Реальный список устройств вывода (`mpv --audio-device=help`), выбор
  сохраняется в `settings.json`.
- Очередь, следующий/предыдущий, повтор трека, перемешивание, удаление
  из очереди.
- Таймер сна, любимые треки.
- Обложка, Ken Burns, размытый фон (опционально).

**Библиотека**
- Несколько папок, `walkdir` + `lofty`, теги и обложки.
- Поиск, фильтр по папкам, вкладки «Треки / Любимые».
- Редактирование тегов (title/artist/album) прямо в приложении.
- Экспорт и импорт плейлистов, любимых и настроек в JSON.

**Плейлисты**
- Создание, переименование, описание, удаление.
- Закреплённые плейлисты, сортировка (как добавлял / название /
  длительность / исполнитель), фильтр треков.
- Локальные треки и YouTube-треки в одном плейлисте.
- «Скачать все» в шапке плейлиста ставит все `yt:`-треки в очередь
  загрузок.
- Обложка плейлиста берётся у первого трека или YouTube-превью.

**Текст**
- LRCLIB, синхронные строки, подсветка, перемотка по строке.
- Размер шрифта и смещение, обновление текста.
- Плавающий тулбар над доком.

**YouTube**
- Поиск через yt-dlp, «Только музыка», недавние запросы, «Микс по
  ссылке».
- Стриминг в mpv, очередь, лайк, переход к исполнителю.
- Скачивание в очередь, нарезка фрагментов через mpv, прогресс,
  отмена, открытие папки.
- Прокси: авто-определение (Happ 20808, v2rayN 10809, Clash 7890,
  SOCKS 10808/1080) с реальной проверкой CONNECT/TLS; ручной прокси в
  «Настройки → Интеграции».

**Визуал**
- Настоящий PCM из второго mpv (`--ao=pcm`) + FFT на 64 полосы.
- Кольцо спектра, обложка в центре, транспорт.

**Интеграции**
- Хаб ONYX: HTTP 8787 + UDP 8788, сопряжение по коду, presence,
  плейлисты/лайки/файлы, протокол совместим с Android (`SyncModels.kt`).
- Discord Rich Presence: трек, исполнитель, альбом, прогресс.
  Обложка ищется сначала локально, потом по треку, потом по альбому
  (Deezer / iTunes), кэшируется в `cover-cache.json`. Локальная
  обложка загружается на pomf/catbox.
- Автообновление: `tauri-plugin-updater`, minisign-подпись,
  `latest.json` на GitHub Releases.

## Требования

- Rust (stable) и `cargo`.
- Node.js 20.19+ / 22.12+ и npm.
- WebView2 Runtime (на Windows 10/11 обычно уже установлен).
- `mpv.exe` для сборки: положить в
  `src-tauri/binaries/mpv-x86_64-pc-windows-msvc.exe`.
- `yt-dlp.exe` для YouTube: положить в
  `src-tauri/binaries/yt-dlp-x86_64-pc-windows-msvc.exe`.

## Команды

```powershell
cd desktop-rs
npm install

# один раз: sidecar-бинарники
New-Item -ItemType Directory -Force src-tauri/binaries
Copy-Item "C:\Program Files\MPV Player\mpv.exe" `
  "src-tauri/binaries/mpv-x86_64-pc-windows-msvc.exe"
Copy-Item "..\desktop\libs\yt-dlp.exe" `
  "src-tauri/binaries/yt-dlp-x86_64-pc-windows-msvc.exe"

npm run tauri dev      # dev-режим
npm run tauri build    # MSI + NSIS + .sig в src-tauri/target/release/bundle/
```

Для подписанной сборки:

```powershell
$env:TAURI_SIGNING_PRIVATE_KEY = "$env:USERPROFILE\.onyx\onyx-updater.key"
$env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD = Get-Content "$env:USERPROFILE\.onyx\onyx-updater.password" -Raw
npm run tauri build
```

## Автообновление

- Публичный ключ лежит в `src-tauri/tauri.conf.json`.
- Приватный ключ и пароль — в `%USERPROFILE%\.onyx\` (не в git).
- Endpoint: `https://github.com/ysl021/onyx/releases/download/desktop-latest/latest.json`.
- Публикация одной командой:

```powershell
.\scripts\publish-desktop.ps1
```

Скрипт собирает MSI/NSIS, создаёт `latest.json` с подписью и
загружает всё в релиз `desktop-latest` через `gh`.

## Структура

```
desktop-rs/
  src/                     Svelte-фронтенд
    App.svelte             оболочка, док, фон, переключение экранов
    app.css                палитра, шрифты, glass, общие компоненты
    lib/components/        Glass, Icon, NavDock, Cover
    lib/screens/           Player, Lyrics, Playlists, Music, YouTube,
                           Visualizer, Settings
    lib/stores/            player, settings, library, queue, favorites,
                           sleep, playlists, lyrics, youtube
  src-tauri/
    src/main.rs            точка входа
    src/lib.rs             Tauri builder, IPC, setup
    src/commands.rs        команды для фронтенда
    src/settings.rs        %APPDATA%\ONYX\settings.json
    src/player.rs          mpv sidecar + JSON IPC
    src/library.rs         walkdir + lofty, теги, обложки, запись тегов
    src/playlists.rs       rust-playlists.json
    src/favorites.rs       rust-favorites.json
    src/lyrics.rs          LRCLIB + LRC
    src/youtube.rs         yt-dlp, прокси, очередь загрузок
    src/visualizer.rs      PCM + FFT
    src/hub.rs             HTTP 8787 + UDP 8788
    src/discord.rs         Discord IPC + Rich Presence
    src/covers.rs          поиск обложек, кэш, загрузка на pomf/catbox
    src/update.rs          заметки по автообновлению
    resources/             mpv-NOTICE.txt
    tauri.conf.json        окно, бандлы, externalBin, updater
    capabilities/          разрешения Tauri
  scripts/
    publish-desktop.ps1    сборка + latest.json + GitHub release
```

## Важно

- Android-клиент остаётся на Kotlin/Compose и не входит в этот проект.
- Hub-протокол и `%APPDATA%\ONYX` нельзя ломать: иначе телефон и старые
  настройки перестанут работать.
- YouTube требует локальный прокси. Если авто-определение не находит
  рабочий, укажи его вручную в «Настройки → Интеграции»:
  `http://127.0.0.1:20808` или `socks5://127.0.0.1:10808`.
- Поиск YouTube завершается ошибкой за ~10 секунд, если прокси не
  отвечает; неудачный результат кэшируется на 30 секунд, чтобы не
  дёргать сеть на каждый запрос.
