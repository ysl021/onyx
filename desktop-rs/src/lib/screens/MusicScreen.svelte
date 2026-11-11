<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { onMount } from "svelte";
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import Cover from "../components/Cover.svelte";
  import {
    loadTracks,
    scanSavedFolders,
    scanning,
    tracks,
  } from "../stores/library";
  import {
    currentPath,
    playTrack,
  } from "../stores/player";
  import { setQueue } from "../stores/queue";
  import {
    favoriteIdOf,
    favorites,
    loadFavorites,
    toggleFavorite,
  } from "../stores/favorites";
  import { settings } from "../stores/settings";
  import type { Track } from "../stores/library";

  let query = $state("");
  let selectedFolder = $state<string | null>(null);
  let tab = $state<"tracks" | "favorites">("tracks");
  let error = $state("");
  let missingFolders = $state<string[]>([]);
  let editTrack = $state<Track | null>(null);
  let editTitle = $state("");
  let editArtist = $state("");
  let editAlbum = $state("");

  onMount(() => {
    loadTracks();
    loadFavorites();
    refreshMissing();
  });

  async function refreshMissing() {
    try {
      missingFolders = await invoke<string[]>("missing_folders", {
        folders: $settings.musicFolders,
      });
    } catch {
      missingFolders = [];
    }
  }

  const visible = $derived(
    $tracks.filter((track) => {
      if (
        selectedFolder &&
        !track.path
          .toLowerCase()
          .startsWith(selectedFolder.toLowerCase())
      ) {
        return false;
      }
      if (tab === "favorites" && !$favorites.includes(favoriteIdOf(track))) {
        return false;
      }
      const needle = query.trim().toLowerCase();
      if (!needle) return true;
      return `${track.title} ${track.artist} ${track.album}`
        .toLowerCase()
        .includes(needle);
    }),
  );

  function folderName(folder: string) {
    const parts = folder.split(/[\\/]/).filter(Boolean);
    return parts[parts.length - 1] ?? folder;
  }

  function fmt(ms: number) {
    const total = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
  }

  async function refresh() {
    try {
      await scanSavedFolders();
      await refreshMissing();
    } catch (e) {
      error = String(e);
    }
  }

  async function play(index: number) {
    const track = visible[index];
    if (!track) return;
    setQueue(visible, index);
    try {
      await playTrack(track.path, track);
    } catch (e) {
      error = String(e);
    }
  }

  function startEdit(track: Track) {
    editTrack = track;
    editTitle = track.title;
    editArtist = track.artist;
    editAlbum = track.album;
  }

  async function saveEdit() {
    if (!editTrack) return;
    try {
      await invoke("track_update_tags", {
        path: editTrack.path,
        title: editTitle,
        artist: editArtist,
        album: editAlbum,
      });
      tracks.update((list) =>
        list.map((track) =>
          track.path === editTrack?.path
            ? {
                ...track,
                title: editTitle || track.title,
                artist: editArtist,
                album: editAlbum,
              }
            : track,
        ),
      );
      editTrack = null;
    } catch (e) {
      error = String(e);
    }
  }
</script>

<div class="page music-page">
  <header class="head">
    <div>
      <h1 class="page-title">Музыка</h1>
      <div class="page-sub">{$settings.musicFolders.length} папки</div>
    </div>
    <button
      class="circle"
      aria-label="Обновить библиотеку"
      onclick={refresh}
      disabled={$scanning}
    >
      <Icon name="refresh" size={19} />
    </button>
  </header>

  <div class="folder-pills">
    <button
      class="pill"
      class:selected={selectedFolder === null}
      onclick={() => (selectedFolder = null)}
    >
      Все
    </button>
    {#each $settings.musicFolders as folder (folder)}
      <button
        class="pill"
        class:selected={selectedFolder === folder}
        onclick={() => (selectedFolder = folder)}
      >
        {folderName(folder)}
      </button>
    {/each}
  </div>

  <div class="search-field">
    <Icon name="search" size={20} />
    <input bind:value={query} placeholder="Поиск" />
  </div>

  {#each missingFolders as folder (folder)}
    <div class="warning">
      Нет доступа к папке «{folderName(folder)}». Добавь её заново.
    </div>
  {/each}

  <div class="tabs">
    <button
      class="pill"
      class:selected={tab === "tracks"}
      onclick={() => (tab = "tracks")}
    >
      Треки
    </button>
    <button
      class="pill"
      class:selected={tab === "favorites"}
      onclick={() => (tab = "favorites")}
    >
      Любимые
    </button>
  </div>

  {#if error}
    <div class="error">{error}</div>
  {/if}

  {#if $scanning}
    <div class="status">Сканирую папки…</div>
  {/if}

  {#if visible.length === 0}
    <Glass class="card" padding={18}>
      <div class="section-label">Пусто</div>
      <p class="card-text">
        {$tracks.length === 0
          ? "Добавь папку с музыкой в настройках."
          : "По этому фильтру ничего нет."}
      </p>
    </Glass>
  {:else}
    <div class="track-list">
      {#each visible as track, index (track.id)}
        <div class="track-row">
          <button class="track-main" onclick={() => play(index)}>
            <Cover path={track.path} size={56} radius={10} />
            <div class="track-meta">
              <div
                class="track-title"
                class:playing={$currentPath === track.path}
              >
                {track.title}
              </div>
              <div class="track-artist">
                {track.artist || "<unknown>"}
              </div>
            </div>
            <div class="track-time">{fmt(track.durationMs)}</div>
          </button>
          <button
            class="icon-btn"
            class:liked={$favorites.includes(favoriteIdOf(track))}
            aria-label="В любимые"
            onclick={() => toggleFavorite(track)}
          >
            <Icon
              name={$favorites.includes(favoriteIdOf(track)) ? "favorite" : "favorite-border"}
              size={19}
            />
          </button>
          <button
            class="icon-btn"
            aria-label="Изменить теги"
            onclick={() => startEdit(track)}
          >
            <Icon name="edit" size={18} />
          </button>
        </div>
      {/each}
    </div>
  {/if}
</div>

{#if editTrack}
  <div class="modal" role="presentation" onclick={() => (editTrack = null)}>
    <div
      class="modal-card"
      role="dialog"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => {
        if (event.key === "Escape") editTrack = null;
      }}
    >
      <div class="modal-head">
        <div class="section-label">Теги трека</div>
        <button class="icon-btn" aria-label="Закрыть" onclick={() => (editTrack = null)}>
          <Icon name="close" size={16} />
        </button>
      </div>
      <label class="field">
        <span>Название</span>
        <input bind:value={editTitle} />
      </label>
      <label class="field">
        <span>Исполнитель</span>
        <input bind:value={editArtist} />
      </label>
      <label class="field">
        <span>Альбом</span>
        <input bind:value={editAlbum} />
      </label>
      <button class="pill selected save" onclick={saveEdit}>Сохранить</button>
    </div>
  </div>
{/if}

<style>
  .music-page {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
  }

  .folder-pills {
    display: flex;
    gap: 8px;
    overflow-x: auto;
    padding-bottom: 2px;
  }

  .folder-pills .pill {
    flex: 0 0 auto;
  }

  .tabs {
    display: flex;
    gap: 8px;
  }

  .card-text {
    margin: 10px 0 0;
    color: var(--dim);
    font-size: 12px;
    line-height: 18px;
  }

  .status {
    color: var(--faint);
    font-size: 12px;
  }

  .error {
    color: #ff8a5c;
    font-size: 12px;
  }

  .warning {
    color: var(--dim);
    font-size: 12px;
    line-height: 18px;
  }

  .track-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .track-row {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .track-main {
    display: flex;
    align-items: center;
    gap: 12px;
    flex: 1;
    min-width: 0;
    padding: 7px 4px;
    border-radius: 14px;
    text-align: left;
    color: var(--white);
  }

  .track-main:hover {
    background: rgba(255, 255, 255, 0.04);
  }

  .track-meta {
    flex: 1;
    min-width: 0;
  }

  .track-title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 15px;
    font-weight: 500;
  }

  .track-title.playing {
    color: var(--white);
  }

  .track-artist {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    margin-top: 2px;
    color: var(--faint);
    font-size: 12px;
  }

  .track-time {
    color: var(--faint);
    font-size: 11px;
  }

  .icon-btn.liked {
    color: var(--white);
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
    padding: 18px;
    border: 1px solid var(--line);
    border-radius: 26px;
    background: var(--card-strong);
  }

  .modal-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    margin-bottom: 12px;
  }

  .field {
    display: flex;
    flex-direction: column;
    gap: 5px;
    margin-top: 10px;
    color: var(--faint);
    font-size: 10px;
    letter-spacing: 1.1px;
    text-transform: uppercase;
  }

  .field input {
    min-height: 44px;
    padding: 0 13px;
    border: 1px solid var(--line);
    border-radius: 13px;
    background: rgba(255, 255, 255, 0.04);
    color: var(--white);
    font-size: 14px;
    letter-spacing: normal;
    text-transform: none;
    outline: none;
  }

  .field input:focus {
    border-color: var(--line-strong);
  }

  .save {
    width: 100%;
    margin-top: 16px;
  }
</style>
