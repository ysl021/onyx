<script lang="ts">
  import { onMount } from "svelte";
  import { open } from "@tauri-apps/plugin-dialog";
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import {
    loadTracks,
    scanFolder,
    scanSavedFolders,
    scanning,
    tracks,
  } from "../stores/library";
  import { playTrack, player } from "../stores/player";
  import { setQueue } from "../stores/queue";
  import { loadSettings, settings } from "../stores/settings";

  let error = $state("");

  onMount(async () => {
    await loadSettings();
    await loadTracks();
    if ($tracks.length === 0 && $settings.musicFolders.length > 0) {
      await scanSavedFolders();
    }
  });

  async function chooseFolder() {
    error = "";
    try {
      const selected = await open({ directory: true, multiple: false });
      if (typeof selected === "string") {
        const found = await scanFolder(selected);
        if (found.length === 0) {
          error = "В выбранной папке нет аудиофайлов.";
        }
      }
    } catch (e) {
      error = String(e);
    }
  }

  function fmt(ms: number) {
    const total = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
  }

  async function play(index: number) {
    const track = $tracks[index];
    if (!track) return;
    setQueue($tracks, index);
    try {
      await playTrack(track.path, track);
    } catch (e) {
      error = String(e);
    }
  }
</script>

<div class="music">
  <header class="music-head">
    <div>
      <div class="eyebrow">Библиотека</div>
      <h1>Музыка</h1>
      <div class="count">{$tracks.length} треков</div>
    </div>
    <div class="head-actions">
      <button class="pill" onclick={chooseFolder} disabled={$scanning}>
        <Icon name="library-music" size={14} />
        {$scanning ? "Сканируем…" : "Выбрать папку"}
      </button>
    </div>
  </header>

  {#if error}
    <div class="error">{error}</div>
  {/if}

  {#if $tracks.length === 0}
    <div class="card-slot">
      <Glass padding={18}>
        <div class="section-label">Локальная музыка</div>
        <p class="card-text">
          Папки не выбраны. Добавь папку с треками — ONYX просканирует её и покажет
          список.
        </p>
        <button class="pill selected" onclick={chooseFolder}>Выбрать папку</button>
      </Glass>
    </div>
  {:else}
    <Glass class="list" padding={8}>
      {#each $tracks as track, index (track.id)}
        <button
          class="row"
          class:playing={$player.title === track.title && $player.artist === track.artist}
          onclick={() => play(index)}
        >
          <div class="thumb">
            <Icon name="music-note" size={18} />
          </div>
          <div class="row-meta">
            <div class="row-title">{track.title}</div>
            <div class="row-sub">
              {[track.artist, track.album].filter(Boolean).join(" · ")}
            </div>
          </div>
          <div class="row-time">{fmt(track.durationMs)}</div>
        </button>
      {/each}
    </Glass>
  {/if}
</div>

<style>
  .music {
    max-width: 820px;
    margin: 0 auto;
    padding: 22px 18px 132px;
  }

  .music-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 16px;
  }

  .eyebrow {
    color: var(--faint);
    font-size: 10px;
    letter-spacing: 2px;
    text-transform: uppercase;
  }

  h1 {
    margin: 4px 0 0;
    font-size: 30px;
    font-weight: 700;
    letter-spacing: -0.6px;
  }

  .count {
    margin-top: 4px;
    color: var(--faint);
    font-size: 11px;
  }

  .head-actions {
    display: flex;
    gap: 8px;
  }

  .error {
    margin-bottom: 12px;
    color: #ff8a5c;
    font-size: 12px;
  }

  .card-slot {
    margin-bottom: 14px;
  }

  .card-text {
    margin: 10px 0 14px;
    color: var(--dim);
    font-size: 13px;
    line-height: 19px;
  }

  .row {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    padding: 8px 10px;
    border-radius: 14px;
    text-align: left;
    color: var(--white);
    transition: background 160ms ease;
  }

  .row:hover {
    background: rgba(255, 255, 255, 0.05);
  }

  .row.playing {
    background: rgba(255, 255, 255, 0.08);
  }

  .thumb {
    display: grid;
    place-items: center;
    width: 40px;
    height: 40px;
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.05);
    color: var(--faint);
    flex: 0 0 auto;
  }

  .row-meta {
    min-width: 0;
    flex: 1;
  }

  .row-title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 14px;
    font-weight: 500;
  }

  .row-sub {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    margin-top: 2px;
    color: var(--faint);
    font-size: 11px;
  }

  .row-time {
    color: var(--faint);
    font-size: 11px;
  }
</style>
