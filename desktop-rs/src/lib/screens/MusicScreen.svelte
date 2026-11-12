<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { get } from "svelte/store";
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
  import { setQueue, queue, queueIndex } from "../stores/queue";
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
  let selecting = $state(false);
  let selected = $state<string[]>([]);
  let lastSelected = $state<number | null>(null);
  let longPressTimer: ReturnType<typeof setTimeout> | null = null;
  let suppressClick = false;
  let bulkAlbumOpen = $state(false);
  let bulkAlbum = $state("");
  let bulkArtistOpen = $state(false);
  let bulkArtist = $state("");
  let deleteOpen = $state(false);
  let deleteBusy = $state(false);

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

  function startSelection(track?: Track) {
    selecting = true;
    if (track && !selected.includes(track.id)) {
      selected = [...selected, track.id];
    }
  }

  function toggleSelect(track: Track) {
    selected = selected.includes(track.id)
      ? selected.filter((id) => id !== track.id)
      : [...selected, track.id];
  }

  function selectAll() {
    selected = visible.map((track) => track.id);
  }

  function clearSelection() {
    selected = [];
    selecting = false;
    lastSelected = null;
    bulkAlbumOpen = false;
    bulkArtistOpen = false;
    deleteOpen = false;
  }

  function pointerDown(track: Track, event: PointerEvent) {
    if (event.button !== 0 || selecting) return;
    if (longPressTimer) clearTimeout(longPressTimer);
    longPressTimer = setTimeout(() => {
      suppressClick = true;
      startSelection(track);
    }, 450);
  }

  function pointerUp() {
    if (longPressTimer) {
      clearTimeout(longPressTimer);
      longPressTimer = null;
    }
  }

  function rowClick(track: Track, index: number, event: MouseEvent) {
    if (suppressClick) {
      suppressClick = false;
      return;
    }
    if (!selecting) {
      play(index);
      return;
    }
    if (event.shiftKey && lastSelected !== null) {
      const start = Math.min(lastSelected, index);
      const end = Math.max(lastSelected, index);
      const range = visible.slice(start, end + 1).map((item) => item.id);
      selected = [...new Set([...selected, ...range])];
    } else {
      toggleSelect(track);
    }
    lastSelected = index;
  }

  function openBulkAlbum() {
    const chosen = $tracks.filter((track) => selected.includes(track.id));
    bulkAlbum = chosen.length === 1 ? chosen[0].album : "";
    bulkAlbumOpen = true;
  }

  function openBulkArtist() {
    const chosen = $tracks.filter((track) => selected.includes(track.id));
    bulkArtist = chosen.length === 1 ? chosen[0].artist : "";
    bulkArtistOpen = true;
  }

  async function applyBulkAlbum() {
    if (selected.length === 0) return;
    try {
      await invoke("tracks_update_album", {
        paths: selected,
        album: bulkAlbum,
      });
      tracks.update((list) =>
        list.map((track) =>
          selected.includes(track.id) ? { ...track, album: bulkAlbum } : track,
        ),
      );
      clearSelection();
    } catch (e) {
      error = String(e);
    }
  }

  async function applyBulkArtist() {
    if (selected.length === 0) return;
    try {
      await invoke("tracks_update_artist", {
        paths: selected,
        artist: bulkArtist,
      });
      tracks.update((list) =>
        list.map((track) =>
          selected.includes(track.id) ? { ...track, artist: bulkArtist } : track,
        ),
      );
      clearSelection();
    } catch (e) {
      error = String(e);
    }
  }

  function requestDelete() {
    if (selected.length === 0) return;
    if ($settings.confirmDelete) {
      deleteOpen = true;
    } else {
      performDelete();
    }
  }

  async function performDelete() {
    if (selected.length === 0) return;
    const paths = [...selected];
    deleteBusy = true;
    try {
      const removed = await invoke<number>("tracks_delete", { paths });
      const removedSet = new Set(paths);
      const oldQueue = get(queue);
      const oldIndex = get(queueIndex);
      const removedBefore = oldQueue
        .slice(0, oldIndex)
        .filter((track) => removedSet.has(track.path)).length;
      tracks.update((list) => list.filter((track) => !removedSet.has(track.id)));
      queue.update((list) => list.filter((track) => !removedSet.has(track.path)));
      queueIndex.set(Math.max(0, oldIndex - removedBefore));
      const playing = $currentPath;
      if (playing && removedSet.has(playing)) {
        await invoke("player_pause").catch(() => {});
        currentPath.set(null);
        currentTrack.set(null);
      }
      clearSelection();
      error = removed > 0 ? `Удалено: ${removed}` : "";
    } catch (e) {
      error = String(e);
    } finally {
      deleteBusy = false;
    }
  }
</script>

<div class="page music-page">
  <header class="head">
    <div>
      <h1 class="page-title">Музыка</h1>
      <div class="page-sub">{$settings.musicFolders.length} папки</div>
    </div>
    <div class="head-actions">
      <button
        class="pill"
        class:selected={selecting}
        onclick={() => {
          if (selecting) {
            clearSelection();
          } else {
            selecting = true;
          }
        }}
      >
        Выбрать
      </button>
      <button
        class="circle"
        aria-label="Обновить библиотеку"
        onclick={refresh}
        disabled={$scanning}
      >
        <Icon name="refresh" size={19} />
      </button>
    </div>
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

  {#if selecting}
    <div class="selection-bar">
      <Glass padding={12}>
        <div class="selection-count">Выбрано {selected.length}</div>
        <div class="selection-actions">
          <button class="pill" onclick={selectAll}>Все</button>
          <button
            class="pill"
            onclick={openBulkAlbum}
            disabled={selected.length === 0}
          >
            Альбом
          </button>
          <button
            class="pill"
            onclick={openBulkArtist}
            disabled={selected.length === 0}
          >
            Исполнитель
          </button>
          <button
            class="pill danger"
            onclick={requestDelete}
            disabled={selected.length === 0}
          >
            Удалить
          </button>
          <button class="pill" onclick={clearSelection}>Отмена</button>
        </div>
      </Glass>
    </div>
  {/if}

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
        <div class="track-row" class:selected={selecting && selected.includes(track.id)}>
          <button
            class="track-main"
            onclick={(event) => rowClick(track, index, event)}
            onpointerdown={(event) => pointerDown(track, event)}
            onpointerup={pointerUp}
            onpointerleave={pointerUp}
            oncontextmenu={(event) => {
              event.preventDefault();
              startSelection(track);
            }}
          >
            {#if selecting}
              <span class="select-mark" class:on={selected.includes(track.id)}>
                {#if selected.includes(track.id)}
                  <Icon name="check" size={13} />
                {/if}
              </span>
            {/if}
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

{#if bulkAlbumOpen}
  <div class="modal" role="presentation" onclick={() => (bulkAlbumOpen = false)}>
    <div
      class="modal-card"
      role="dialog"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => {
        if (event.key === "Escape") bulkAlbumOpen = false;
      }}
    >
      <div class="modal-head">
        <div class="section-label">Альбом для {selected.length} треков</div>
        <button class="icon-btn" aria-label="Закрыть" onclick={() => (bulkAlbumOpen = false)}>
          <Icon name="close" size={16} />
        </button>
      </div>
      <label class="field">
        <span>Альбом</span>
        <input bind:value={bulkAlbum} placeholder="Название альбома" />
      </label>
      <button class="pill selected save" onclick={applyBulkAlbum}>Сохранить</button>
    </div>
  </div>
{/if}

{#if bulkArtistOpen}
  <div class="modal" role="presentation" onclick={() => (bulkArtistOpen = false)}>
    <div
      class="modal-card"
      role="dialog"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => {
        if (event.key === "Escape") bulkArtistOpen = false;
      }}
    >
      <div class="modal-head">
        <div class="section-label">Исполнитель для {selected.length} треков</div>
        <button class="icon-btn" aria-label="Закрыть" onclick={() => (bulkArtistOpen = false)}>
          <Icon name="close" size={16} />
        </button>
      </div>
      <label class="field">
        <span>Исполнитель</span>
        <input bind:value={bulkArtist} placeholder="Исполнитель" />
      </label>
      <button class="pill selected save" onclick={applyBulkArtist}>Сохранить</button>
    </div>
  </div>
{/if}

{#if deleteOpen}
  <div class="modal" role="presentation" onclick={() => (deleteOpen = false)}>
    <div
      class="modal-card"
      role="dialog"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => {
        if (event.key === "Escape") deleteOpen = false;
      }}
    >
      <div class="modal-head">
        <div class="section-label">Удаление</div>
        <button class="icon-btn" aria-label="Закрыть" onclick={() => (deleteOpen = false)}>
          <Icon name="close" size={16} />
        </button>
      </div>
      <p class="modal-text">
        Удалить {selected.length} трек(ов) с диска? Это действие нельзя отменить.
      </p>
      <div class="pill-row">
        <button class="pill danger" onclick={performDelete} disabled={deleteBusy}>
          {deleteBusy ? "Удаляю…" : "Удалить"}
        </button>
        <button class="pill" onclick={() => (deleteOpen = false)}>Отмена</button>
      </div>
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

  .head-actions {
    display: flex;
    align-items: center;
    gap: 8px;
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

  .selection-bar {
    position: sticky;
    bottom: 12px;
    z-index: 5;
  }

  .selection-bar :global(.glass) {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    flex-wrap: wrap;
  }

  .selection-count {
    color: var(--dim);
    font-size: 12px;
  }

  .selection-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .pill.danger {
    border-color: rgba(255, 100, 80, 0.35);
    color: #ff8a5c;
  }

  .pill.danger:hover {
    background: rgba(255, 100, 80, 0.12);
    color: #ffb09a;
  }

  .track-row.selected {
    border-radius: 14px;
    background: rgba(255, 255, 255, 0.06);
  }

  .select-mark {
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
    flex: 0 0 auto;
    border: 1px solid var(--line-strong);
    border-radius: 999px;
    color: #08080a;
  }

  .select-mark.on {
    border-color: transparent;
    background: var(--accent);
  }

  .modal-text {
    margin: 10px 0 16px;
    color: var(--dim);
    font-size: 13px;
    line-height: 19px;
  }
</style>
