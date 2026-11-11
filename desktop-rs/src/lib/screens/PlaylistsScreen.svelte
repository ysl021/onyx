<script lang="ts">
  import { onMount } from "svelte";
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import { loadTracks, tracks, type Track } from "../stores/library";
  import { playTrack } from "../stores/player";
  import { setQueue } from "../stores/queue";
  import {
    createPlaylist,
    deletePlaylist,
    loadPlaylists,
    playlists,
    removeTrackFromPlaylist,
    renamePlaylist,
  } from "../stores/playlists";
  import type { Playlist } from "../stores/playlists";

  let newName = $state("");
  let selectedId = $state<string | null>(null);
  let renameValue = $state("");
  let error = $state("");

  onMount(() => {
    loadPlaylists();
    loadTracks();
  });

  const selected = $derived(
    $playlists.find((item) => item.id === selectedId) ?? null,
  );
  const selectedTracks = $derived(
    (selected?.trackIds ?? [])
      .map((id) => $tracks.find((track) => track.id === id || track.path === id))
      .filter((track) => !!track) as Track[],
  );

  async function create() {
    const name = newName.trim();
    if (!name) return;
    await createPlaylist(name);
    newName = "";
  }

  function open(playlist: Playlist) {
    selectedId = playlist.id;
    renameValue = playlist.name;
  }

  async function playAll() {
    if (selectedTracks.length === 0) return;
    setQueue(selectedTracks, 0);
    try {
      await playTrack(selectedTracks[0].path);
    } catch (e) {
      error = String(e);
    }
  }

  async function playAt(index: number) {
    if (index < 0 || index >= selectedTracks.length) return;
    setQueue(selectedTracks, index);
    try {
      await playTrack(selectedTracks[index].path);
    } catch (e) {
      error = String(e);
    }
  }

  async function removePlaylist(playlist: Playlist) {
    if (!confirm(`Удалить плейлист «${playlist.name}»?`)) return;
    await deletePlaylist(playlist.id);
    if (selectedId === playlist.id) selectedId = null;
  }

  async function saveName() {
    if (!selected) return;
    const name = renameValue.trim();
    if (!name) return;
    await renamePlaylist(selected.id, name);
  }

  function fmt(ms: number) {
    const total = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
  }
</script>

<div class="playlists">
  {#if selected}
    <header class="head">
      <button
        class="circle"
        aria-label="Назад"
        onclick={() => (selectedId = null)}
      >
        <Icon name="arrow-back" size={20} />
      </button>
      <div class="head-meta">
        <h1>{selected.name}</h1>
        <div class="count">{selectedTracks.length} треков</div>
      </div>
      <button
        class="pill selected"
        onclick={playAll}
        disabled={selectedTracks.length === 0}
      >
        <Icon name="play" size={14} />
        Играть
      </button>
    </header>

    <Glass class="card" padding={16}>
      <div class="section-label">Название</div>
      <div class="rename-row">
        <input bind:value={renameValue} />
        <button class="pill" onclick={saveName}>Сохранить</button>
      </div>
    </Glass>

    {#if selectedTracks.length === 0}
      <Glass class="card" padding={18}>
        <div class="section-label">Треки</div>
        <p class="muted">
          Пока пусто. Добавляй треки из «Плеера» кнопкой «В плейлист».
        </p>
      </Glass>
    {:else}
      <Glass class="list" padding={8}>
        {#each selectedTracks as track, index (track.id)}
          <div class="row">
            <button class="row-main" onclick={() => playAt(index)}>
              <div class="thumb"><Icon name="music-note" size={18} /></div>
              <div class="row-meta">
                <div class="row-title">{track.title}</div>
                <div class="row-sub">
                  {[track.artist, track.album].filter(Boolean).join(" · ")}
                </div>
              </div>
              <div class="row-time">{fmt(track.durationMs)}</div>
            </button>
            <button
              class="remove"
              aria-label="Убрать"
              onclick={() => removeTrackFromPlaylist(selected.id, track.id)}
            >
              <Icon name="close" size={16} />
            </button>
          </div>
        {/each}
      </Glass>
    {/if}

    <button class="pill danger" onclick={() => removePlaylist(selected)}>
      Удалить плейлист
    </button>
  {:else}
    <header class="head">
      <div>
        <div class="eyebrow">Коллекции</div>
        <h1>Плейлисты</h1>
        <div class="count">{$playlists.length}</div>
      </div>
    </header>

    <Glass class="card" padding={16}>
      <div class="section-label">Новый плейлист</div>
      <div class="rename-row">
        <input
          bind:value={newName}
          placeholder="Название"
          onkeydown={(event) => event.key === "Enter" && create()}
        />
        <button class="pill selected" onclick={create}>Создать</button>
      </div>
    </Glass>

    {#if $playlists.length === 0}
      <Glass class="card" padding={18}>
        <div class="section-label">Пока пусто</div>
        <p class="muted">
          Создай первый плейлист и добавляй в него треки из «Музыки» и «Плеера».
        </p>
      </Glass>
    {:else}
      <div class="grid">
        {#each $playlists as playlist (playlist.id)}
          <div class="playlist-card">
            <button class="playlist-main" onclick={() => open(playlist)}>
              <div class="playlist-cover">
                <Icon name="playlist-play" size={28} />
              </div>
              <div class="playlist-name">{playlist.name}</div>
              <div class="playlist-count">
                {playlist.trackIds.length} треков
              </div>
            </button>
            <button
              class="remove"
              aria-label="Удалить"
              onclick={() => removePlaylist(playlist)}
            >
              <Icon name="close" size={16} />
            </button>
          </div>
        {/each}
      </div>
    {/if}
  {/if}

  {#if error}
    <div class="error">{error}</div>
  {/if}
</div>

<style>
  .playlists {
    max-width: 820px;
    margin: 0 auto;
    padding: 22px 18px 132px;
  }

  .head {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 16px;
  }

  .head-meta {
    min-width: 0;
    flex: 1;
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
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .count {
    margin-top: 4px;
    color: var(--faint);
    font-size: 11px;
  }

  .playlists :global(.card) {
    margin-bottom: 14px;
  }

  .rename-row {
    display: flex;
    gap: 8px;
    margin-top: 10px;
  }

  .rename-row input {
    flex: 1;
    min-width: 0;
    padding: 9px 12px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.04);
    color: var(--white);
    font: inherit;
    font-size: 13px;
    outline: none;
  }

  .rename-row input:focus {
    border-color: var(--line-strong);
  }

  .muted {
    margin: 10px 0 0;
    color: var(--dim);
    font-size: 13px;
    line-height: 19px;
  }

  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
    gap: 12px;
  }

  .playlist-card {
    position: relative;
    border: 1px solid var(--line);
    border-radius: 20px;
    background:
      linear-gradient(180deg, rgba(255, 255, 255, 0.035), rgba(255, 255, 255, 0.01)),
      rgba(16, 16, 19, 0.4);
    overflow: hidden;
  }

  .playlist-main {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    width: 100%;
    padding: 12px;
    text-align: left;
    color: var(--white);
  }

  .playlist-cover {
    display: grid;
    place-items: center;
    width: 100%;
    aspect-ratio: 1;
    border-radius: 14px;
    background:
      radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.07), transparent 55%),
      var(--deep);
    color: var(--faint);
  }

  .playlist-name {
    width: 100%;
    margin-top: 10px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 14px;
    font-weight: 600;
  }

  .playlist-count {
    margin-top: 2px;
    color: var(--faint);
    font-size: 11px;
  }

  .remove {
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    border-radius: 999px;
    color: var(--faint);
    background: rgba(0, 0, 0, 0.35);
    transition: color 160ms ease, background 160ms ease;
  }

  .playlist-card > .remove {
    position: absolute;
    top: 8px;
    right: 8px;
  }

  .remove:hover {
    color: var(--white);
    background: rgba(0, 0, 0, 0.55);
  }

  .row {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 0 4px;
  }

  .row-main {
    display: flex;
    align-items: center;
    gap: 12px;
    flex: 1;
    min-width: 0;
    padding: 8px 10px;
    border-radius: 14px;
    text-align: left;
    color: var(--white);
  }

  .row-main:hover {
    background: rgba(255, 255, 255, 0.05);
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

  .danger {
    margin-top: 6px;
    color: #ff8a5c;
  }

  .error {
    margin-top: 12px;
    color: #ff8a5c;
    font-size: 12px;
  }
</style>
