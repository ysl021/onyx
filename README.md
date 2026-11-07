# ONYX

Личный пульт управления музыкой для Android: медиаплеер, переключение вывода звука,
Galaxy Buds 3 Pro (шумодав, ANC на одно ухо, батареи), другие Bluetooth-наушники
и тексты Spicy Lyrics / Musixmatch / Genius / LRCLIB.

Чёрно-белый интерфейс, стекло, крупная типографика, OLED-фон.

## Лицензия

ONYX распространяется под **GNU GPLv3** (см. `LICENSE`), потому что код SPP-протокола
Buds является производным от [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) (GPLv3).
При распространении APK исходники должны быть доступны.

## Секреты

Ключ Spicy Lyrics не хранится в репозитории. Для локальной сборки:

```powershell
Copy-Item secrets.properties.example secrets.properties
# вписать SPICY_KEY=sl_sk_...
```

`secrets.properties` добавлен в `.gitignore` и подставляется в `BuildConfig.SPICY_KEY`.

## Что уже умеет

- **Медиа**: play/pause, следующий/предыдущий, перемотка, прогресс, обложка, трек/артист/альбом.
  Работает с любым плеером через `MediaSessionManager` (Spotify, YouTube Music, локальные плееры).
- **Вывод звука**: переключение активного маршрута через `MediaRouter2.transferTo`
  (телефон ↔ Bluetooth-наушники/колонка). Без отключения Bluetooth и без root.
- **Наушники**: Galaxy Buds (Off / ANC / Ambient / Adaptive, ANC на одно ухо, максимальный
  уровень ANC, заряд L/R/кейса, датчики ношения) и любые другие Bluetooth-наушники как
  обычный аудиовыход (плеер, громкость, переключение маршрута, заряд если система его отдаёт).
  Автоподключения к SPP нет: ONYX не пытается подключиться сам, только по кнопке.
  SPP-протокол Samsung портирован из GalaxyBudsClient.
- **Spotify**: OAuth PKCE со своим client_id, очередь «дальше», режим повтора (off/context/track),
  название активного устройства.
- **Тексты**: Spicy Lyrics (community TTML, пословная синхронизация) → Musixmatch richsync →
  Spotify color-lyrics → LRCLIB (включая enhanced LRC) → plain text.
  Русский перевод: Spicy/Musixmatch, иначе Google Translate.
  Кэш в памяти и на диске, полноэкранный режим (кнопка ⛶, выход — ✕ или Back),
  смещение и размер шрифта, размытая обложка трека.
- **Quick Settings**: плитки «Шумодав» и «ANC на одно ухо».

## Сборка

Нужны JDK 17 и Android SDK (platform 35, build-tools 35). Путь к SDK задан в `local.properties`.

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
.\gradlew.bat assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

Установка:

```powershell
& "D:\projekt ai\onyx\tools\android-sdk\platform-tools\adb.exe" install -r "app\build\outputs\apk\debug\app-debug.apk"
```

## Первый запуск

1. **Доступ к уведомлениям** — Настройки → Доступы → «Открыть». Без него приложение не видит,
   что играет. На некоторых прошивках Samsung пункт называется «Доступ к уведомлениям».
2. **Bluetooth-разрешения** — выдаются по кнопке в настройках (Android 12+).
3. **Батарея** — отключи оптимизацию, иначе система может убивать foreground-сервис наушников.
4. **Buds** — наушники должны быть сопряжены. Автоподключения нет: открой карточку
   «Наушники» и нажми «Подключить Buds». Если SPP-канал перехватывает Samsung-менеджер,
   выключи пакет (см. ниже) и нажми ↻.

### Galaxy Wearable / Samsung SPP

На Samsung SPP-канал постоянно занимает `com.samsung.accessory.paranmgr`. Пока он включён,
ONYX и Galaxy Wearable будут по очереди отбирать канал, и управление ANC будет мигать.
Для стабильной работы ONYX (однократно, через adb):

```powershell
adb shell pm disable-user --user 0 com.samsung.accessory.paranmgr
```

Вернуть обратно:

```powershell
adb shell pm enable com.samsung.accessory.paranmgr
```

Пока пакет выключен, Galaxy Wearable не сможет обновлять прошивку и менять настройки Buds;
аудио, сопряжение и ONYX работают.

## Spotify

1. Открой [Spotify Developer Dashboard](https://developer.spotify.com/dashboard), создай приложение.
2. В **Redirect URIs** добавь ровно:
   `http://127.0.0.1:8899/callback`
3. В настройках ONYX вставь **Client ID**, сохрани и нажми «Войти».
4. Откроется браузер; после подтверждения Spotify вернёт тебя в приложение.
   Очередь и повтор требуют **Premium** и активного устройства воспроизведения.
   Вход выполняется в фоне (не в экране настроек), поэтому сворачивание приложения
   и пересоздание Activity не обрывают ожидание callback. Если Custom Tab недоступен,
   ONYX откроет обычный браузер. Порт `8899` должен быть свободен.

Скоупы: `user-read-playback-state user-modify-playback-state user-read-currently-playing`.

## Тексты Spicy Lyrics

Основной источник — публичный API Spicy Lyrics (community TTML). Ключ хранится в
DataStore (Настройки → «Spicy Lyrics API key»). В этой персональной сборке ключ
подставлен по умолчанию; не распространяй APK с ним.

Получить/заменить ключ: [developers.spicylyrics.org](https://developers.spicylyrics.org).
Если ключ пустой или отклонён, ONYX автоматически уходит на Musixmatch → Spotify → LRCLIB.
Для треков не из Spotify поиск идёт через `/v1/tracks/search` по названию/артисту/длительности.

## Структура

```
app/src/main/java/app/onyx/
  AppController.kt         — связывает медиа, маршруты, Spotify, тексты, Buds
  MainActivity.kt          — edge-to-edge, разрешения, запуск
  OnyxApp.kt               — навигация и нижняя панель
  core/                    — DataStore-настройки, OkHttp
  media/                   — MediaSession, NotificationListener, MediaRouter2, заряд BT-наушников
  buds/                    — SPP-протокол, RFCOMM-клиент, foreground-сервис
  spotify/                 — OAuth PKCE (app-scoped), токены, очередь, повтор
  lyrics/                  — Spicy Lyrics, Musixmatch, Spotify, LRCLIB, перевод, кэш
  ui/                      — тема, стеклянные компоненты, экраны
```

## Ограничения

- Переключение вывода — только смена маршрута. Полного disconnect/подключения Bluetooth нет.
- Управление ANC/батареями — только для Galaxy Buds. Остальные наушники — стандартный
  Bluetooth-аудиовыход; заряд показывается, если система транслирует его в BATTERY_LEVEL_CHANGED.
- Протокол Buds неофициальный: прошивки могут менять смещения полей. Если батареи/режим
  показываются неверно — пришли лог, поправим offsets в `BudsClient.parseStatus`.
- Spicy Lyrics — сторонний catalogue; при недоступности API остаются Musixmatch/Spotify/LRCLIB.
- Spotify color-lyrics может требовать client-token веб-плеера; при отказе используется LRCLIB.

## Благодарности и лицензия

- SPP-протокол и структура сообщений: [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) (GPLv3).
  Поскольку код протокола производный, при распространении ONYX должен распространяться под **GPLv3**.
- Идеи провайдеров и караоке-разметки: Spicetify **Lyrics Plus**.
- Данные текстов: Musixmatch, LRCLIB, Spotify.
