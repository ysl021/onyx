<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { open, save } from "@tauri-apps/plugin-dialog";
  import { onDestroy, onMount } from "svelte";
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import { loadFavorites } from "../stores/favorites";
  import { loadTracks, scanFolder, scanSavedFolders } from "../stores/library";
  import { loadPlaylists } from "../stores/playlists";
  import {
    saveSettings,
    setAudioDevice,
    setEq,
    setPanelPosition,
    setTheme,
    setVolume,
    settings,
  } from "../stores/settings";
  import type { PanelPosition } from "../stores/settings";

  let {
    version,
    onBack,
    initialTab = "main",
  }: {
    version: string;
    onBack: () => void;
    initialTab?: "main" | "look" | "integrations";
  } = $props();

  type HubDevice = { name: string; token: string; addedAt: number };
  type HubStatus = {
    running: boolean;
    port: number;
    name: string;
    addresses: string[];
    pairCode: string;
    devices: HubDevice[];
    error: string | null;
    syncStatus: string;
    libraryIndexLen: number;
    paired: boolean;
    syncRev: number;
  };

  const panels: { key: PanelPosition; label: string }[] = [
    { key: "bottom", label: "Снизу" },
    { key: "top", label: "Сверху" },
    { key: "left", label: "Слева" },
    { key: "right", label: "Справа" },
  ];

  const eqFreqs = ["60", "230", "910", "3.6k", "14k"];
  const eqPresets = [
    { name: "Плоско", bands: [0, 0, 0, 0, 0] },
    { name: "Бас", bands: [6, 4, 1, 0, 0] },
    { name: "Голос", bands: [-2, 0, 3, 4, 1] },
  ];

  let tab = $state<"main" | "look" | "integrations">(initialTab);
  let hub = $state<HubStatus | null>(null);
  let hubBusy = $state(false);
  let hubError = $state("");
  let discordStatus = $state("");
  let updateStatus = $state("");
  let dataStatus = $state("");
  let proxyStatus = $state("");
  let proxyValue = $state("");
  let audioDevices = $state<{ name: string; description: string }[]>([]);
  let polling: ReturnType<typeof setInterval> | null = null;

  onMount(() => {
    proxyValue = $settings.youtubeProxy;
    reloadDevices();
    refreshHub();
    polling = setInterval(refreshHub, 2000);
  });

  onDestroy(() => {
    if (polling !== null) clearInterval(polling);
  });

  async function reloadDevices() {
    try {
      audioDevices = await invoke<{ name: string; description: string }[]>(
        "audio_devices",
      );
    } catch {
      audioDevices = [];
    }
  }

  async function refreshHub() {
    try {
      hub = await invoke<HubStatus>("hub_status");
    } catch {
      // The backend is not ready yet.
    }
  }

  async function startHub() {
    hubBusy = true;
    hubError = "";
    try {
      hub = await invoke<HubStatus>("hub_start", {
        port: $settings.hubPort,
        name: $settings.hubName,
      });
    } catch (e) {
      hubError = String(e);
    } finally {
      hubBusy = false;
    }
  }

  async function stopHub() {
    hub = await invoke<HubStatus>("hub_stop");
  }

  async function pushToPhone() {
    try {
      hub = await invoke<HubStatus>("hub_push_to_phone");
    } catch (e) {
      hubError = String(e);
    }
  }

  async function pullFromPhone() {
    hub = await invoke<HubStatus>("hub_request_phone");
    setTimeout(refreshAfterSync, 3500);
  }

  async function requestIndex() {
    hub = await invoke<HubStatus>("hub_request_index");
    setTimeout(refreshAfterSync, 5000);
  }

  async function forgetDevices() {
    hub = await invoke<HubStatus>("hub_forget_devices");
  }

  async function regenerateCode() {
    hub = await invoke<HubStatus>("hub_regenerate_code");
  }

  async function refreshAfterSync() {
    await Promise.all([loadTracks(), loadPlaylists(), loadFavorites()]);
    await refreshHub();
  }

  async function saveHubPort(value: number) {
    const port = Math.min(65535, Math.max(1024, Math.floor(value) || 8787));
    await saveSettings({ ...$settings, hubPort: port });
    if (hub?.running) {
      await invoke("hub_stop");
      await startHub();
    }
  }

  async function saveHubName(value: string) {
    await saveSettings({ ...$settings, hubName: value.trim() });
    if (hub?.running) {
      await invoke("hub_stop");
      await startHub();
    }
  }

  async function toggleDiscord() {
    await saveSettings({
      ...$settings,
      discordEnabled: !$settings.discordEnabled,
    });
  }

  async function testDiscord() {
    discordStatus = "Проверяю…";
    try {
      const ok = await invoke<boolean>("discord_test");
      discordStatus = ok
        ? "Discord подключён"
        : "Discord не запущен — открой приложение Discord";
    } catch (e) {
      discordStatus = String(e);
    }
  }

  async function saveDiscordId(value: string) {
    await saveSettings({
      ...$settings,
      discordClientId: value.trim() || "1568847197397717073",
    });
  }

  async function saveProxy(value: string) {
    proxyValue = value.trim();
    await saveSettings({ ...$settings, youtubeProxy: proxyValue });
    proxyStatus = "";
  }

  async function checkProxy() {
    proxyStatus = "Проверяю…";
    try {
      const ok = proxyValue
        ? await invoke<boolean>("youtube_proxy_check", { proxy: proxyValue })
        : !!(await invoke<string | null>("youtube_proxy_status"));
      proxyStatus = ok
        ? "Прокси работает — YouTube доступен"
        : "Прокси не отвечает. Включи Happ/Clash или проверь адрес.";
    } catch (e) {
      proxyStatus = String(e);
    }
  }

  async function autoProxy() {
    proxyStatus = "Ищу рабочий прокси…";
    try {
      const found = await invoke<string | null>("youtube_proxy_status");
      if (found) {
        proxyValue = found;
        await saveSettings({ ...$settings, youtubeProxy: found });
        proxyStatus = `Найден: ${found}`;
      } else {
        proxyStatus = "Рабочий прокси не найден";
      }
    } catch (e) {
      proxyStatus = String(e);
    }
  }

  async function checkUpdates() {
    updateStatus = "Проверяю…";
    try {
      const { check } = await import("@tauri-apps/plugin-updater");
      const update = await check();
      if (!update) {
        updateStatus = "Установлена последняя версия";
        return;
      }
      updateStatus = `Скачиваю ${update.version}…`;
      await update.downloadAndInstall();
      updateStatus = "Готово, перезапускаю…";
      const { relaunch } = await import("@tauri-apps/plugin-process");
      await relaunch();
    } catch (e) {
      updateStatus = String(e);
    }
  }

  async function addFolder() {
    const selected = await open({ directory: true, multiple: false });
    if (typeof selected !== "string") return;
    await scanFolder(selected);
  }

  async function removeFolder(folder: string) {
    const current = $settings;
    await saveSettings({
      ...current,
      musicFolders: current.musicFolders.filter((item) => item !== folder),
    });
    await scanSavedFolders();
  }

  async function clearFolders() {
    await saveSettings({ ...$settings, musicFolders: [] });
    await invoke("library_scan", { folders: [] });
  }

  async function changeVolume(event: Event) {
    const value = Number((event.currentTarget as HTMLInputElement).value);
    await setVolume(value);
  }

  async function setVolumeStep(step: number) {
    await saveSettings({ ...$settings, volumeStep: step });
  }

  async function toggleBackdrop() {
    await saveSettings({
      ...$settings,
      coverBackdrop: !$settings.coverBackdrop,
    });
  }

  function currentBands() {
    const bands = $settings.eqBands ?? [];
    return [0, 1, 2, 3, 4].map((index) => Number(bands[index] ?? 0));
  }

  async function toggleEq() {
    await setEq(!$settings.eqEnabled, currentBands());
  }

  async function changeEqBand(index: number, value: number) {
    const bands = currentBands();
    bands[index] = value;
    await setEq($settings.eqEnabled, bands);
  }

  async function applyEqPreset(bands: number[]) {
    await setEq(true, bands);
  }

  async function openSoundSettings() {
    try {
      await invoke("open_sound_settings");
    } catch (e) {
      dataStatus = String(e);
    }
  }

  async function exportData() {
    const path = await save({
      defaultPath: "onyx-backup.json",
      filters: [{ name: "JSON", extensions: ["json"] }],
    });
    if (typeof path !== "string") return;
    try {
      await invoke("export_data", { path });
      dataStatus = "Данные экспортированы";
    } catch (e) {
      dataStatus = String(e);
    }
  }

  async function importData() {
    const path = await open({
      multiple: false,
      filters: [{ name: "JSON", extensions: ["json"] }],
    });
    if (typeof path !== "string") return;
    try {
      dataStatus = await invoke<string>("import_data", { path });
      await Promise.all([loadTracks(), loadPlaylists(), loadFavorites()]);
    } catch (e) {
      dataStatus = String(e);
    }
  }
</script>

<div class="page settings-page">
  <header class="head">
    <button class="circle" aria-label="Назад" onclick={onBack}>
      <Icon name="arrow-back" size={20} />
    </button>
    <div>
      <h1 class="page-title">Настройки</h1>
      <div class="page-sub">ONYX · v{version}</div>
    </div>
  </header>

  <div class="segments">
    <button class="pill" class:selected={tab === "main"} onclick={() => (tab = "main")}>
      Основные
    </button>
    <button class="pill" class:selected={tab === "look"} onclick={() => (tab = "look")}>
      Внешний вид
    </button>
    <button
      class="pill"
      class:selected={tab === "integrations"}
      onclick={() => (tab = "integrations")}
    >
      Интеграции
    </button>
  </div>

  {#if tab === "main"}
    <Glass class="card" padding={18}>
      <div class="section-label">Управление</div>
      <div class="setting-row">
        <div>
          <div class="setting-title">Громкость</div>
          <div class="setting-sub">Шаг ползунка и текущий уровень</div>
        </div>
        <div class="setting-value">{$settings.volume}%</div>
      </div>
      <input
        class="range"
        type="range"
        min="0"
        max="100"
        step={$settings.volumeStep}
        value={$settings.volume}
        oninput={changeVolume}
        aria-label="Громкость"
      />
      <div class="pill-row step-row">
        {#each [1, 5, 10] as step}
          <button
            class="pill"
            class:selected={$settings.volumeStep === step}
            onclick={() => setVolumeStep(step)}
          >
            {step}%
          </button>
        {/each}
      </div>

      <div class="setting-row spaced">
        <div>
          <div class="setting-title">Вывод звука</div>
          <div class="setting-sub">Устройство, в которое играет mpv</div>
        </div>
      </div>
      <div class="field-row">
        <select
          class="select"
          value={$settings.audioDevice}
          onchange={(event) => setAudioDevice(event.currentTarget.value)}
          aria-label="Устройство вывода"
        >
          <option value="auto">Система (авто)</option>
          {#each audioDevices as device (device.name)}
            <option value={device.name}>{device.description || device.name}</option>
          {/each}
        </select>
        <button class="pill" onclick={reloadDevices}>Обновить</button>
        <button class="pill" onclick={openSoundSettings}>Настройки звука</button>
      </div>
    </Glass>

    <Glass class="card" padding={18}>
      <div class="section-label">Эффект</div>
      <div class="setting-row">
        <div>
          <div class="setting-title">Эквалайзер</div>
          <div class="setting-sub">5 полос, применяется к mpv</div>
        </div>
        <button
          class="toggle"
          class:on={$settings.eqEnabled}
          aria-label="Эквалайзер"
          onclick={toggleEq}
        ></button>
      </div>
      <div class="eq-bands" class:disabled={!$settings.eqEnabled}>
        {#each eqFreqs as frequency, index}
          <div class="eq-row">
            <span class="eq-freq">{frequency}</span>
            <input
              type="range"
              min="-12"
              max="12"
              step="1"
              value={currentBands()[index]}
              oninput={(event) =>
                changeEqBand(index, Number(event.currentTarget.value))}
              aria-label={`Эквалайзер ${frequency}`}
            />
            <span class="eq-value">{currentBands()[index]}</span>
          </div>
        {/each}
      </div>
      <div class="pill-row">
        {#each eqPresets as preset}
          <button class="pill" onclick={() => applyEqPreset(preset.bands)}>
            {preset.name}
          </button>
        {/each}
      </div>
    </Glass>

    <Glass class="card" padding={18}>
      <div class="section-label">Локальная музыка</div>
      {#if $settings.musicFolders.length === 0}
        <p class="card-text">Папки не выбраны.</p>
      {:else}
        {#each $settings.musicFolders as folder (folder)}
          <div class="folder-row">
            <span title={folder}>{folder}</span>
            <button
              class="icon-btn"
              aria-label="Убрать папку"
              onclick={() => removeFolder(folder)}
            >
              <Icon name="trash" size={16} />
            </button>
          </div>
        {/each}
      {/if}
      <div class="pill-row">
        <button class="pill selected" onclick={addFolder}>Добавить папку</button>
        <button class="pill" onclick={clearFolders}>Очистить всё</button>
      </div>
    </Glass>

    <Glass class="card" padding={18}>
      <div class="section-label">Данные</div>
      <p class="card-text">
        Экспорт и импорт плейлистов, любимых и настроек в JSON. Файлы Spotify и
        Discord не затрагиваются.
      </p>
      <div class="pill-row">
        <button class="pill" onclick={exportData}>Экспорт данных</button>
        <button class="pill" onclick={importData}>Импорт данных</button>
      </div>
      {#if dataStatus}
        <div class="status">{dataStatus}</div>
      {/if}
    </Glass>

    <Glass class="card" padding={18}>
      <div class="section-label">Обновления</div>
      <div class="setting-row">
        <div>
          <div class="setting-title">Версия {version}</div>
          <div class="setting-sub">Проверка обновлений ONYX Desktop</div>
        </div>
      </div>
      <div class="pill-row">
        <button class="pill" onclick={checkUpdates}>Проверить обновления</button>
      </div>
      {#if updateStatus}
        <div class="status">{updateStatus}</div>
      {/if}
    </Glass>

    <Glass class="card" padding={18}>
      <div class="section-label">О приложении</div>
      <p class="card-text">
        ONYX Desktop · Rust + Tauri + Svelte 5. Плеер на mpv, YouTube через
        yt-dlp, текст из LRCLIB.
      </p>
      <div class="status">Версия {version}</div>
    </Glass>
  {:else if tab === "look"}
    <Glass class="card" padding={18}>
      <div class="section-label">Панель навигации</div>
      <div class="pill-row">
        {#each panels as panel}
          <button
            class="pill"
            class:selected={$settings.panelPosition === panel.key}
            onclick={() => setPanelPosition(panel.key)}
          >
            {panel.label}
          </button>
        {/each}
      </div>
      <p class="card-text">
        Положение стеклянной панели с разделами. По умолчанию — снизу, как на
        телефоне.
      </p>
    </Glass>

    <Glass class="card" padding={18}>
      <div class="section-label">Тема</div>
      <div class="pill-row">
        <button
          class="pill"
          class:selected={$settings.theme === "dark"}
          onclick={() => setTheme("dark")}
        >
          Тёмная
        </button>
        <button
          class="pill"
          class:selected={$settings.theme === "light"}
          onclick={() => setTheme("light")}
        >
          Светлая
        </button>
      </div>
    </Glass>

    <Glass class="card" padding={18}>
      <div class="section-label">Фон</div>
      <div class="setting-row">
        <div>
          <div class="setting-title">Фон под обложку</div>
          <div class="setting-sub">
            Размытая обложка текущего трека на фоне приложения
          </div>
        </div>
        <button
          class="toggle"
          class:on={$settings.coverBackdrop}
          aria-label="Фон под обложку"
          onclick={toggleBackdrop}
        ></button>
      </div>
    </Glass>
  {:else}
    <Glass class="card" padding={18}>
      <div class="card-head">
        <div class="section-label">Хаб ONYX · синхронизация</div>
        <span class="dot" class:on={hub?.running}></span>
      </div>
      <p class="card-text">
        {hub?.running
          ? `Работает на порту ${hub.port}`
          : "Выключен. Телефон и ПК синхронизируются по локальной сети."}
        {hub?.error ? ` · ${hub.error}` : ""}
      </p>

      {#if hub?.running}
        <div class="pair-box">
          <div>
            <span>Код сопряжения</span>
            <strong>{hub.pairCode}</strong>
          </div>
          <div>
            <span>Адрес</span>
            <strong>{hub.addresses[0] ?? "127.0.0.1"}:{hub.port}</strong>
          </div>
        </div>

        <div class="pill-row">
          <button class="pill" onclick={regenerateCode}>Новый код</button>
          <button class="pill" onclick={pushToPhone}>На телефон</button>
          <button class="pill" onclick={pullFromPhone}>С телефона</button>
          <button class="pill" onclick={requestIndex}>Список треков</button>
          <button class="pill" onclick={stopHub}>Остановить</button>
        </div>

        <div class="status">{hub.syncStatus}</div>

        {#if hub.devices.length > 0}
          <div class="devices">
            {#each hub.devices as device (device.token)}
              <div class="device">
                <Icon name="computer" size={14} />
                <span>{device.name}</span>
              </div>
            {/each}
            <button class="pill" onclick={forgetDevices}>Забыть устройства</button>
          </div>
        {:else}
          <p class="card-text">
            Подключи телефон: в его настройках введи код и адрес этого ПК.
          </p>
        {/if}
      {:else}
        <div class="pill-row">
          <button class="pill selected" onclick={startHub} disabled={hubBusy}>
            {hubBusy ? "Запускаю…" : "Запустить хаб"}
          </button>
        </div>
      {/if}

      {#if hubError}
        <div class="error">{hubError}</div>
      {/if}

      <div class="field-row">
        <label>
          <span>Порт</span>
          <input
            type="number"
            min="1024"
            max="65535"
            value={$settings.hubPort}
            onchange={(event) => saveHubPort(Number(event.currentTarget.value))}
          />
        </label>
        <label>
          <span>Имя ПК</span>
          <input
            value={$settings.hubName}
            placeholder={hub?.name ?? "ONYX-PC"}
            onchange={(event) => saveHubName(event.currentTarget.value)}
          />
        </label>
      </div>
    </Glass>

    <Glass class="card" padding={18}>
      <div class="section-label">Discord Rich Presence</div>
      <div class="setting-row">
        <div>
          <div class="setting-title">Показывать трек в Discord</div>
          <div class="setting-sub">
            Трек, исполнитель, альбом и прогресс. Когда ПК молчит — трек с
            телефона.
          </div>
        </div>
        <button
          class="toggle"
          class:on={$settings.discordEnabled}
          aria-label="Discord Rich Presence"
          onclick={toggleDiscord}
        ></button>
      </div>
      <div class="pill-row">
        <button class="pill" onclick={testDiscord}>Проверить связь</button>
      </div>
      <div class="field-row">
        <label class="wide">
          <span>Application ID</span>
          <input
            value={$settings.discordClientId}
            onchange={(event) => saveDiscordId(event.currentTarget.value)}
          />
        </label>
      </div>
      {#if discordStatus}
        <div class="status">{discordStatus}</div>
      {/if}
    </Glass>

    <Glass class="card" padding={18}>
      <div class="section-label">YouTube · прокси</div>
      <p class="card-text">
        Пусто — авто-определение (Happ 20808, v2rayN 10809, Clash 7890). Можно
        указать вручную: http://127.0.0.1:20808 или socks5://127.0.0.1:10808.
      </p>
      <div class="field-row">
        <label class="wide">
          <span>Прокси</span>
          <input
            value={proxyValue}
            placeholder="auto"
            onchange={(event) => saveProxy(event.currentTarget.value)}
          />
        </label>
      </div>
      <div class="pill-row">
        <button class="pill" onclick={checkProxy}>Проверить</button>
        <button class="pill" onclick={autoProxy}>Авто-определение</button>
      </div>
      {#if proxyStatus}
        <div class="status">{proxyStatus}</div>
      {/if}
    </Glass>
  {/if}
</div>

<style>
  .settings-page {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .head {
    display: flex;
    align-items: center;
    gap: 14px;
    margin-bottom: 2px;
  }

  .segments {
    display: flex;
    gap: 8px;
    overflow-x: auto;
    padding-bottom: 2px;
  }

  .card-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  .card-text {
    margin: 10px 0 0;
    color: var(--dim);
    font-size: 12px;
    line-height: 18px;
  }

  .setting-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
    margin-top: 12px;
  }

  .setting-row.spaced {
    margin-top: 20px;
  }

  .setting-title {
    font-size: 14px;
    font-weight: 600;
  }

  .setting-sub {
    margin-top: 3px;
    color: var(--faint);
    font-size: 11px;
    line-height: 16px;
  }

  .setting-value {
    color: var(--dim);
    font-size: 13px;
    font-weight: 600;
  }

  .range {
    width: 100%;
    margin-top: 14px;
    accent-color: var(--accent);
  }

  .step-row {
    margin-top: 10px;
  }

  .eq-bands {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-top: 14px;
  }

  .eq-bands.disabled {
    opacity: 0.45;
    pointer-events: none;
  }

  .eq-row {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .eq-freq {
    width: 42px;
    color: var(--faint);
    font-size: 11px;
    text-align: right;
  }

  .eq-row input {
    flex: 1;
    accent-color: var(--accent);
  }

  .eq-value {
    width: 32px;
    color: var(--dim);
    font-size: 11px;
    text-align: right;
  }

  .select {
    flex: 1;
    min-width: 0;
    min-height: 42px;
    padding: 0 14px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: rgba(255, 255, 255, 0.03);
    color: var(--white);
    outline: none;
  }

  .select option {
    background: var(--elevated);
    color: var(--white);
  }

  .folder-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
    padding: 8px 10px;
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.035);
  }

  .folder-row span {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--dim);
    font-size: 12px;
  }

  .status {
    margin-top: 10px;
    color: var(--faint);
    font-size: 11px;
    line-height: 16px;
  }

  .error {
    margin-top: 10px;
    color: #ff8a5c;
    font-size: 11px;
    line-height: 16px;
  }

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.16);
  }

  .dot.on {
    background: #62d98a;
    box-shadow: 0 0 10px rgba(98, 217, 138, 0.6);
  }

  .pair-box {
    display: flex;
    gap: 10px;
    margin-top: 12px;
  }

  .pair-box div {
    flex: 1;
    min-width: 0;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: rgba(255, 255, 255, 0.035);
  }

  .pair-box span {
    display: block;
    color: var(--faint);
    font-size: 10px;
    letter-spacing: 1.2px;
    text-transform: uppercase;
  }

  .pair-box strong {
    display: block;
    margin-top: 4px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 16px;
    letter-spacing: 1px;
  }

  .devices {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-top: 12px;
  }

  .device {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 10px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.05);
    color: var(--dim);
    font-size: 11px;
  }

  .field-row {
    display: flex;
    gap: 10px;
    margin-top: 12px;
  }

  .field-row label {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
    color: var(--faint);
    font-size: 10px;
    letter-spacing: 1.1px;
    text-transform: uppercase;
  }

  .field-row label.wide {
    flex: 1;
  }

  .field-row input {
    min-height: 42px;
    padding: 0 12px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.04);
    color: var(--white);
    font-size: 13px;
    letter-spacing: normal;
    text-transform: none;
    outline: none;
  }

  .field-row input:focus {
    border-color: var(--line-strong);
  }
</style>
