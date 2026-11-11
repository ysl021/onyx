<script lang="ts">
  import { derived, get } from "svelte/store";
  import { invoke } from "@tauri-apps/api/core";
  import { onMount } from "svelte";
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import Cover from "../components/Cover.svelte";
  import { favorites, favoriteIdOf, toggleFavorite } from "../stores/favorites";
  import {
    cover,
    currentPath,
    currentTrack,
    openAudioFile,
    player,
    playNext,
    playPrevious,
    playQueueAt,
    seekTo,
    togglePlayer,
  } from "../stores/player";
  import {
    queue,
    queueIndex,
    repeatOne,
    shuffle,
    toggleRepeat,
    toggleShuffle,
  } from "../stores/queue";
  import {
    addTrackToPlaylist,
    createPlaylist,
    loadPlaylists,
    playlists,
  } from "../stores/playlists";
  import {
    cancelSleepTimer,
    sleepRemainingMs,
    startSleepTimer,
  } from "../stores/sleep";
  import {
    setAudioDevice,
    setVolume,
    settings,
  } from "../stores/settings";

  let { onOpenLyrics }: { onOpenLyrics: () => void } = $props();
  let error = $state("");
  let showPlaylistPicker = $state(false);
  let showDevicePicker = $state(false);
  let audioDevices = $state<{ name: string; description: string }[]>([]);

  onMount(() => {
    reloadDevices();
  });

  const progress = derived(player, ($p) =>
    $p.durationMs > 0 ? Math.min(100, ($p.positionMs / $p.durationMs) * 100) : 0,
  );
  const remaining = derived(player, ($p) =>
    Math.max(0, $p.durationMs - $p.positionMs),
  );
  const upcoming = derived([queue, queueIndex], ([$q, $i]) =>
    $q
      .slice($i + 1)
      .map((track, offset) => ({ track, index: $i + 1 + offset })),
  );
  const isFavorite = $derived(
    $currentTrack ? $favorites.includes(favoriteIdOf($currentTrack)) : false,
  );
  const currentDevice = $derived(
    audioDevices.find((device) => device.name === $settings.audioDevice) ?? null,
  );
  const primaryDevice = $derived(currentDevice ?? audioDevices[0] ?? null);
  const currentDeviceLabel = $derived(
    $settings.audioDevice === "auto"
      ? "Система (авто)"
      : currentDevice?.description || $settings.audioDevice,
  );

  async function reloadDevices() {
    try {
      audioDevices = await invoke<{ name: string; description: string }[]>(
        "audio_devices",
      );
    } catch {
      audioDevices = [];
    }
  }

  function fmt(ms: number) {
    const total = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
  }

  function shortDevice(value: string) {
    const cleaned = value.replace(/\s+/g, " ").trim();
    return cleaned.length > 28 ? `${cleaned.slice(0, 27)}…` : cleaned;
  }

  async function pickFile() {
    error = "";
    try {
      await openAudioFile();
    } catch (e) {
      error = String(e);
    }
  }

  async function chooseDevice(name: string) {
    showDevicePicker = false;
    try {
      await setAudioDevice(name);
    } catch (e) {
      error = String(e);
    }
  }

  async function openSoundSettings() {
    try {
      await invoke("open_sound_settings");
    } catch (e) {
      error = String(e);
    }
  }

  async function seek(event: MouseEvent) {
    const el = event.currentTarget as HTMLElement;
    const rect = el.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    const current = get(player);
    const seconds = (ratio * current.durationMs) / 1000;
    if (seconds > 0) await seekTo(seconds);
  }

  async function seekKey(event: KeyboardEvent) {
    const step = event.key === "ArrowRight" ? 5 : event.key === "ArrowLeft" ? -5 : 0;
    const current = get(player);
    if (step === 0 || current.durationMs <= 0) return;
    event.preventDefault();
    const at = current.positionMs / 1000;
    const seconds = Math.max(0, Math.min(current.durationMs / 1000, at + step));
    await seekTo(seconds);
  }

  async function changeVolume(event: Event) {
    const value = Number((event.currentTarget as HTMLInputElement).value);
    await setVolume(value);
  }

  async function addToPlaylist(id: string) {
    const path = $currentPath;
    const track = get(currentTrack);
    const current = get(player);
    if (!path) return;
    await addTrackToPlaylist(id, {
      id: path,
      title: track?.title || current.title,
      artist: track?.artist || current.artist,
      album: track?.album || current.album,
      durationMs: track?.durationMs || current.durationMs,
      thumbnail: track?.artwork ?? null,
    });
    showPlaylistPicker = false;
  }

  async function createPlaylistAndAdd() {
    const path = $currentPath;
    if (!path) return;
    await createPlaylist("Мой плейлист");
    const list = get(playlists);
    const created = list[list.length - 1];
    if (created) await addToPlaylist(created.id);
    showPlaylistPicker = false;
  }

  function removeFromQueue(index: number) {
    const current = get(queueIndex);
    queue.update((list) => list.filter((_, position) => position !== index));
    if (index < current) {
      queueIndex.update((value) => Math.max(0, value - 1));
    }
  }
</script>

<div class="page player-page">
  <header class="head">
    <div class="brand">
      <div class="wordmark">ONYX</div>
      <div class="tagline">music control center</div>
    </div>
    <div class="head-actions">
      <button class="circle" aria-label="Открыть файл" title="Открыть файл" onclick={pickFile}>
        <Icon name="library-music" size={19} />
      </button>
      <button
        class="circle selected"
        aria-label="Вывод звука"
        title="Вывод звука"
        onclick={() => (showDevicePicker = true)}
      >
        <Icon name="cast" size={19} />
      </button>
    </div>
  </header>

  <section class="hero" class:playing={$player.playing}>
    <button class="cover" aria-label="Открыть текст песни" onclick={onOpenLyrics}>
      {#if $cover}
        <img src={$cover} alt="" />
      {:else}
        <Icon name="music-note" size={64} />
      {/if}
    </button>
    <h1 class="title">{$player.title || "Ничего не играет"}</h1>
    <div class="artist">{$player.artist || "Запусти музыку на телефоне"}</div>
    {#if $player.album}
      <div class="album">{$player.album}</div>
    {/if}
    <div class="hero-actions">
      <button class="pill" onclick={onOpenLyrics}>
        <Icon name="translate" size={15} />
        Текст песни
      </button>
      <button
        class="circle"
        class:selected={isFavorite}
        aria-label="В любимые"
        onclick={() => {
          if ($currentTrack) toggleFavorite($currentTrack);
        }}
      >
        <Icon name={isFavorite ? "favorite" : "favorite-border"} size={18} />
      </button>
      <button
        class="circle"
        aria-label="В плейлист"
        onclick={() => {
          loadPlaylists();
          showPlaylistPicker = true;
        }}
      >
        <Icon name="playlist-add" size={18} />
      </button>
    </div>
  </section>

  <section class="controls">
    <div
      class="slider"
      role="slider"
      tabindex="0"
      aria-valuemin="0"
      aria-valuemax={$player.durationMs}
      aria-valuenow={$player.positionMs}
      onclick={seek}
      onkeydown={seekKey}
    >
      <div class="track">
        <div class="fill" style={`width:${$progress}%`}></div>
        <div class="thumb" style={`left:${$progress}%`}></div>
      </div>
    </div>
    <div class="times">
      <span>{fmt($player.positionMs)}</span>
      <span>-{fmt($remaining)}</span>
    </div>
    <div class="transport">
      <button class="skip" aria-label="Предыдущий трек" onclick={playPrevious}>
        <Icon name="skip-previous" size={24} />
      </button>
      <button class="play" aria-label={$player.playing ? "Пауза" : "Играть"} onclick={togglePlayer}>
        <Icon name={$player.playing ? "pause" : "play"} size={34} />
      </button>
      <button class="skip" aria-label="Следующий трек" onclick={playNext}>
        <Icon name="skip-next" size={24} />
      </button>
    </div>
    {#if error}
      <div class="error">{error}</div>
    {/if}
  </section>

  <Glass class="card" padding={18}>
    <div class="section-label">Вывод звука</div>
    <div class="device-pills">
      <button
        class="pill"
        class:selected={$settings.audioDevice === "auto"}
        onclick={() => chooseDevice("auto")}
      >
        <Icon name="computer" size={15} />
        Система
      </button>
      {#if primaryDevice}
        <button
          class="pill device-pill"
          class:selected={$settings.audioDevice === primaryDevice.name}
          onclick={() => chooseDevice(primaryDevice.name)}
        >
          <Icon name="headphones" size={15} />
          <span class="device-text">{shortDevice(primaryDevice.description)}</span>
        </button>
      {/if}
      <button class="pill" onclick={() => (showDevicePicker = true)}>Ещё</button>
    </div>
    <div class="volume-head">
      <span>Громкость</span>
      <span>{$settings.volume}%</span>
    </div>
    <div class="volume-row">
      <Icon name="volume" size={16} />
      <input
        type="range"
        min="0"
        max="100"
        step={$settings.volumeStep}
        value={$settings.volume}
        oninput={changeVolume}
        aria-label="Громкость"
      />
    </div>
  </Glass>

  <Glass class="card" padding={18}>
    <div class="card-head">
      <div class="section-label">Наушники</div>
      <button class="icon-btn" aria-label="Обновить устройства" onclick={reloadDevices}>
        <Icon name="refresh" size={17} />
      </button>
    </div>
    <div class="device-info">
      <Icon name="headphones" size={22} />
      <div class="device-meta">
        <div class="device-name">{currentDeviceLabel}</div>
        <div class="device-sub">Подключено · вывод ONYX</div>
      </div>
    </div>
    <p class="card-text">
      Звук идёт через mpv. Выбор устройства сохраняется и применяется к
      следующему треку.
    </p>
    <div class="pill-row">
      <button class="pill" onclick={openSoundSettings}>Настройки звука</button>
      <button class="pill" onclick={() => (showDevicePicker = true)}>Выбрать устройство</button>
    </div>
  </Glass>

  <Glass class="card" padding={18}>
    <div class="card-head">
      <div class="section-label">
        Таймер сна{#if $sleepRemainingMs > 0} · {fmt($sleepRemainingMs)}{/if}
      </div>
      <Icon name="moon" size={17} />
    </div>
    <p class="card-text">Минуты до автоматической паузы:</p>
    <div class="pill-row">
      {#each [15, 30, 45, 60] as minutes}
        <button class="pill" onclick={() => startSleepTimer(minutes)}>{minutes}</button>
      {/each}
      <button
        class="pill"
        class:selected={$sleepRemainingMs > 0}
        onclick={cancelSleepTimer}
      >
        Выкл
      </button>
    </div>
  </Glass>

  <Glass class="card queue-card" padding={18}>
    <div class="queue-head">
      <div class="section-label">Очередь</div>
      <div class="queue-count">{$queue.length} трек(ов)</div>
    </div>
    <div class="queue-actions">
      <button class="pill" class:selected={$repeatOne} onclick={toggleRepeat}>
        <Icon name="repeat" size={15} />
        Повтор трека
      </button>
      <button class="pill" class:selected={$shuffle} onclick={toggleShuffle}>
        <Icon name="shuffle" size={15} />
        Перемешать
      </button>
    </div>
    {#if $upcoming.length === 0}
      <div class="queue-empty">
        {$queue.length === 0
          ? "Очередь пуста. Запусти трек из «Музыки»."
          : "Это последний трек в очереди."}
      </div>
    {:else}
      <div class="queue-list">
        {#each $upcoming as item (item.index)}
          <div class="queue-row">
            <button class="queue-main" onclick={() => playQueueAt(item.index)}>
              <Cover path={item.track.path} size={40} radius={9} />
              <div class="queue-meta">
                <div class="queue-title">{item.track.title}</div>
                <div class="queue-sub">{item.track.artist}</div>
              </div>
              <div class="queue-time">{fmt(item.track.durationMs)}</div>
            </button>
            <button
              class="icon-btn queue-remove"
              aria-label="Убрать из очереди"
              onclick={() => removeFromQueue(item.index)}
            >
              <Icon name="close" size={15} />
            </button>
          </div>
        {/each}
      </div>
    {/if}
  </Glass>
</div>

{#if showPlaylistPicker}
  <div class="modal" role="presentation" onclick={() => (showPlaylistPicker = false)}>
    <div
      class="modal-card"
      role="dialog"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => {
        if (event.key === "Escape") showPlaylistPicker = false;
      }}
    >
      <div class="modal-head">
        <div class="section-label">В плейлист</div>
        <button class="icon-btn" aria-label="Закрыть" onclick={() => (showPlaylistPicker = false)}>
          <Icon name="close" size={16} />
        </button>
      </div>
      {#if $playlists.length === 0}
        <p class="modal-muted">Плейлистов пока нет.</p>
        <button class="pill selected" onclick={createPlaylistAndAdd}>Создать и добавить</button>
      {:else}
        <div class="modal-list">
          {#each $playlists as playlist (playlist.id)}
            <button class="modal-row" onclick={() => addToPlaylist(playlist.id)}>
              <Icon name="playlist-play" size={18} />
              <span class="modal-name">{playlist.name}</span>
              <span class="modal-count">{playlist.trackIds.length}</span>
            </button>
          {/each}
        </div>
        <button class="pill" onclick={createPlaylistAndAdd}>Новый плейлист</button>
      {/if}
    </div>
  </div>
{/if}

{#if showDevicePicker}
  <div class="modal" role="presentation" onclick={() => (showDevicePicker = false)}>
    <div
      class="modal-card"
      role="dialog"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => {
        if (event.key === "Escape") showDevicePicker = false;
      }}
    >
      <div class="modal-head">
        <div class="section-label">Устройство вывода</div>
        <button class="icon-btn" aria-label="Закрыть" onclick={() => (showDevicePicker = false)}>
          <Icon name="close" size={16} />
        </button>
      </div>
      <div class="modal-list">
        <button class="modal-row" onclick={() => chooseDevice("auto")}>
          <Icon name="computer" size={18} />
          <span class="modal-name">Система (авто)</span>
          {#if $settings.audioDevice === "auto"}<Icon name="check" size={16} />{/if}
        </button>
        {#each audioDevices as device (device.name)}
          <button class="modal-row" onclick={() => chooseDevice(device.name)}>
            <Icon name="headphones" size={18} />
            <span class="modal-name">{device.description || device.name}</span>
            {#if $settings.audioDevice === device.name}<Icon name="check" size={16} />{/if}
          </button>
        {/each}
      </div>
    </div>
  </div>
{/if}

<style>
  .player-page {
    display: flex;
    flex-direction: column;
    gap: 18px;
  }

  .head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    padding-top: 2px;
  }

  .wordmark {
    font-size: 30px;
    font-weight: 900;
    letter-spacing: 6px;
    line-height: 1;
  }

  .tagline {
    margin-top: 7px;
    color: var(--faint);
    font-size: 9px;
    letter-spacing: 2.4px;
    text-transform: uppercase;
  }

  .head-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .hero {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding-top: 4px;
  }

  .cover {
    display: grid;
    place-items: center;
    width: min(68%, 286px);
    aspect-ratio: 1;
    padding: 0;
    overflow: hidden;
    border-radius: 28px;
    border: 1px solid rgba(255, 255, 255, 0.05);
    background:
      radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.08), transparent 55%),
      var(--deep);
    color: var(--faint);
    box-shadow: 0 24px 70px rgba(0, 0, 0, 0.4);
  }

  .cover img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
    animation: cover-drift 26s ease-in-out infinite alternate;
    animation-play-state: paused;
  }

  .hero.playing .cover img {
    animation-play-state: running;
  }

  @keyframes cover-drift {
    0% {
      transform: scale(1.02) translate3d(-1.2%, -0.8%, 0);
    }
    50% {
      transform: scale(1.08) translate3d(1.4%, 1%, 0);
    }
    100% {
      transform: scale(1.04) translate3d(-0.6%, 1.4%, 0);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .cover img {
      animation: none;
    }
  }

  .title {
    max-width: 100%;
    margin: 12px 0 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 26px;
    font-weight: 700;
    letter-spacing: -0.6px;
    text-align: center;
  }

  .artist {
    margin-top: 5px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--dim);
    font-size: 14px;
    text-align: center;
  }

  .album {
    margin-top: 3px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--faint);
    font-size: 11px;
    text-align: center;
  }

  .hero-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 10px;
  }

  .controls {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .slider {
    padding: 8px 0;
    cursor: pointer;
    outline: none;
  }

  .track {
    position: relative;
    height: 4px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.1);
  }

  .fill {
    height: 100%;
    border-radius: 999px;
    background: var(--white);
  }

  .thumb {
    position: absolute;
    top: 50%;
    width: 12px;
    height: 12px;
    border-radius: 999px;
    background: var(--white);
    transform: translate(-50%, -50%);
    box-shadow: 0 0 0 4px rgba(5, 5, 6, 0.4);
  }

  .times {
    display: flex;
    justify-content: space-between;
    color: var(--faint);
    font-size: 11px;
  }

  .transport {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 18px;
    margin-top: 8px;
  }

  .skip,
  .play {
    display: grid;
    place-items: center;
    border-radius: 999px;
    transition: transform 140ms ease, background 160ms ease;
  }

  .skip {
    width: 56px;
    height: 56px;
    border: 1px solid var(--line);
    background: rgba(255, 255, 255, 0.03);
    color: var(--white);
  }

  .play {
    width: 82px;
    height: 82px;
    background: var(--accent);
    color: #08080a;
  }

  .skip:hover,
  .play:hover {
    transform: scale(1.03);
  }

  .error {
    margin-top: 8px;
    color: #ff8a5c;
    font-size: 12px;
    line-height: 17px;
    text-align: center;
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

  .device-pills {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }

  .device-pill {
    max-width: 100%;
  }

  .device-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .volume-head {
    display: flex;
    justify-content: space-between;
    margin-top: 16px;
    color: var(--faint);
    font-size: 11px;
  }

  .volume-row {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 8px;
    color: var(--dim);
  }

  .volume-row input {
    flex: 1;
    accent-color: var(--accent);
  }

  .device-info {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 14px;
    color: var(--white);
  }

  .device-meta {
    min-width: 0;
  }

  .device-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 15px;
    font-weight: 600;
  }

  .device-sub {
    margin-top: 2px;
    color: var(--faint);
    font-size: 11px;
  }

  :global(.queue-card) {
    padding-bottom: 8px;
  }

  .queue-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  .queue-count {
    color: var(--faint);
    font-size: 11px;
  }

  .queue-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }

  .queue-empty {
    margin-top: 14px;
    color: var(--faint);
    font-size: 12px;
    line-height: 17px;
  }

  .queue-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
    margin-top: 10px;
  }

  .queue-row {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .queue-main {
    display: flex;
    align-items: center;
    gap: 10px;
    flex: 1;
    min-width: 0;
    padding: 6px 4px;
    border-radius: 12px;
    text-align: left;
    color: var(--white);
  }

  .queue-main:hover {
    background: rgba(255, 255, 255, 0.04);
  }

  .queue-meta {
    flex: 1;
    min-width: 0;
  }

  .queue-title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 13px;
    font-weight: 500;
  }

  .queue-sub {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    margin-top: 2px;
    color: var(--faint);
    font-size: 11px;
  }

  .queue-time {
    color: var(--faint);
    font-size: 11px;
  }

  .queue-remove {
    opacity: 0;
    transition: opacity 140ms ease;
  }

  .queue-row:hover .queue-remove {
    opacity: 1;
  }

  .modal {
    position: fixed;
    inset: 0;
    z-index: 40;
    display: grid;
    place-items: center;
    padding: 24px;
    background: rgba(0, 0, 0, 0.6);
    backdrop-filter: blur(6px);
  }

  .modal-card {
    width: min(440px, 100%);
    max-height: 72vh;
    overflow-y: auto;
    padding: 18px;
    border: 1px solid var(--line);
    border-radius: 26px;
    background: var(--card-strong);
    backdrop-filter: blur(30px);
  }

  .modal-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  .modal-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
    margin: 12px 0;
  }

  .modal-row {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 10px;
    border-radius: 14px;
    color: var(--white);
    text-align: left;
  }

  .modal-row:hover {
    background: rgba(255, 255, 255, 0.06);
  }

  .modal-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 13px;
  }

  .modal-count {
    color: var(--faint);
    font-size: 11px;
  }

  .modal-muted {
    margin: 12px 0;
    color: var(--dim);
    font-size: 13px;
  }
</style>
