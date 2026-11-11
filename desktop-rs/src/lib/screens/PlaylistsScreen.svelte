<script lang="ts">
  import { get } from "svelte/store";
  import { onMount } from "svelte";
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import Cover from "../components/Cover.svelte";
  import { loadTracks, tracks, type Track } from "../stores/library";
  import { playTrack } from "../stores/player";
  import { setQueue } from "../stores/queue";
  import { addDownload, playVideo } from "../stores/youtube";
  import {
    createPlaylist,
    deletePlaylist,
    loadPlaylists,
    playlists,
    removeTrackFromPlaylist,
    updatePlaylist,
  } from "../stores/playlists";
  import type { Playlist, StreamTrack } from "../stores/playlists";

  let { onBack }: { onBack: () => void } = $props();

  let segment = $state<"mine" | "youtube">("mine");
  let selectedId = $state<string | null>(null);
  let newName = $state("");
  let showCreate = $state(false);
  let editOpen = $state(false);
  let editName = $state("");
  let editDescription = $state("");
  let query = $state("");
  let sort = $state<"added" | "name" | "duration" | "artist">("added");
  let error = $state("");
  let downloadStatus = $state("");

  onMount(() => {
    loadPlaylists();
    loadTracks();
  });

  const selected = $derived(
    $playlists.find((item) => item.id === selectedId) ?? null,
  );

  const list = $derived(
    $playlists
      .filter((playlist) =>
        segment === "youtube" ? playlist.streams.length > 0 : true,
      )
      .sort((a, b) => {
        if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
        return b.createdAt - a.createdAt;
      }),
  );

  type Item =
    | { kind: "local"; id: string; track: Track; index: number }
    | { kind: "yt"; id: string; stream: StreamTrack; index: number };

  const items = $derived.by(() => {
    if (!selected) return [] as Item[];
    return (selected.trackIds ?? [])
      .map((id, index): Item | null => {
        if (id.startsWith("yt:")) {
          const meta = (selected.streams ?? []).find((stream) => stream.id === id);
          return {
            kind: "yt",
            id,
            index,
            stream:
              meta ?? {
                id,
                videoId: id.slice(3),
                title: "YouTube",
                artist: "",
                durationMs: 0,
                thumbnail: null,
              },
          };
        }
        const track = $tracks.find((item) => item.id === id || item.path === id);
        return track ? { kind: "local", id, track, index } : null;
      })
      .filter((item): item is Item => !!item);
  });

  const filtered = $derived.by(() => {
    const needle = query.trim().toLowerCase();
    let result = items.filter((item) => {
      if (!needle) return true;
      const title = item.kind === "local" ? item.track.title : item.stream.title;
      const artist =
        item.kind === "local" ? item.track.artist : item.stream.artist;
      return `${title} ${artist}`.toLowerCase().includes(needle);
    });
    if (sort === "name") {
      result = [...result].sort((a, b) =>
        (a.kind === "local" ? a.track.title : a.stream.title).localeCompare(
          b.kind === "local" ? b.track.title : b.stream.title,
        ),
      );
    } else if (sort === "duration") {
      result = [...result].sort((a, b) => {
        const left = a.kind === "local" ? a.track.durationMs : a.stream.durationMs;
        const right = b.kind === "local" ? b.track.durationMs : b.stream.durationMs;
        return left - right;
      });
    } else if (sort === "artist") {
      result = [...result].sort((a, b) =>
        (a.kind === "local" ? a.track.artist : a.stream.artist).localeCompare(
          b.kind === "local" ? b.track.artist : b.stream.artist,
        ),
      );
    }
    return result;
  });

  const localTracks = $derived(
    items
      .filter((item): item is Extract<Item, { kind: "local" }> => item.kind === "local")
      .map((item) => item.track),
  );
  const streamCount = $derived(
    items.filter((item) => item.kind === "yt").length,
  );
  const totalDuration = $derived(
    items.reduce(
      (total, item) =>
        total +
        (item.kind === "local" ? item.track.durationMs : item.stream.durationMs),
      0,
    ),
  );
  const firstLocal = $derived(
    items.find((item): item is Extract<Item, { kind: "local" }> => item.kind === "local")
      ?.track ?? null,
  );
  const firstStream = $derived(
    items.find((item): item is Extract<Item, { kind: "yt" }> => item.kind === "yt")
      ?.stream ?? null,
  );

  function fmt(ms: number) {
    const total = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
  }

  function totalMinutes(ms: number) {
    return Math.max(1, Math.round(ms / 60000));
  }

  function open(playlist: Playlist) {
    selectedId = playlist.id;
    query = "";
    sort = "added";
    editName = playlist.name;
    editDescription = playlist.description ?? "";
    downloadStatus = "";
  }

  async function create() {
    const name = newName.trim();
    if (!name) return;
    await createPlaylist(name);
    newName = "";
    showCreate = false;
  }

  async function playAll() {
    if (localTracks.length > 0) {
      setQueue(localTracks, 0);
      try {
        await playTrack(localTracks[0].path, localTracks[0]);
      } catch (e) {
        error = String(e);
      }
      return;
    }
    if (firstStream) {
      try {
        await playStream(firstStream);
      } catch (e) {
        error = String(e);
      }
    }
  }

  async function playItem(item: Item) {
    if (item.kind === "yt") {
      try {
        await playStream(item.stream);
      } catch (e) {
        error = String(e);
      }
      return;
    }
    const index = localTracks.findIndex(
      (track) => track.path === item.track.path,
    );
    setQueue(localTracks, Math.max(0, index));
    try {
      await playTrack(item.track.path, item.track);
    } catch (e) {
      error = String(e);
    }
  }

  async function playStream(stream: StreamTrack) {
    await playVideo({
      id: stream.videoId,
      title: stream.title,
      uploader: stream.artist,
      durationMs: stream.durationMs,
      thumbnail: stream.thumbnail ?? "",
    });
  }

  async function downloadAll() {
    const streams = items.filter(
      (item): item is Extract<Item, { kind: "yt" }> => item.kind === "yt",
    );
    if (streams.length === 0) return;
    downloadStatus = `Ставлю в очередь ${streams.length} трек(ов)…`;
    try {
      for (const item of streams) {
        await addDownload(
          {
            id: item.stream.videoId,
            title: item.stream.title,
            uploader: item.stream.artist,
            durationMs: item.stream.durationMs,
            thumbnail: item.stream.thumbnail ?? "",
          },
          [],
        );
      }
      downloadStatus = `Добавлено в загрузки: ${streams.length}`;
    } catch (e) {
      downloadStatus = String(e);
    }
  }

  async function downloadStream(stream: StreamTrack) {
    try {
      await addDownload(
        {
          id: stream.videoId,
          title: stream.title,
          uploader: stream.artist,
          durationMs: stream.durationMs,
          thumbnail: stream.thumbnail ?? "",
        },
        [],
      );
      downloadStatus = "Добавлено в загрузки";
    } catch (e) {
      downloadStatus = String(e);
    }
  }

  async function togglePin(playlist: Playlist) {
    await updatePlaylist(
      playlist.id,
      playlist.name,
      playlist.description ?? "",
      !playlist.pinned,
    );
  }

  async function saveEdit() {
    if (!selected) return;
    await updatePlaylist(
      selected.id,
      editName,
      editDescription,
      selected.pinned,
    );
    editOpen = false;
  }

  async function removePlaylist(playlist: Playlist) {
    if (!confirm(`Удалить плейлист «${playlist.name}»?`)) return;
    await deletePlaylist(playlist.id);
    if (selectedId === playlist.id) selectedId = null;
  }
</script>

<div class="page playlists-page">
  <div class="segments">
    <button
      class="pill"
      class:selected={segment === "mine"}
      onclick={() => (segment = "mine")}
    >
      Мои
    </button>
    <button
      class="pill"
      class:selected={segment === "youtube"}
      onclick={() => (segment = "youtube")}
    >
      YouTube
    </button>
  </div>

  {#if selected}
    <header class="head detail-head">
      <button class="circle" aria-label="Назад" onclick={() => (selectedId = null)}>
        <Icon name="arrow-back" size={20} />
      </button>
      <div class="head-meta">
        <h1 class="page-title">{selected.name}</h1>
        <div class="page-sub">
          {selected.trackIds.length} треков · {totalMinutes(totalDuration)} мин
        </div>
      </div>
      <button
        class="circle"
        class:selected={selected.pinned}
        aria-label="Закрепить"
        onclick={() => togglePin(selected)}
      >
        <Icon name="pin" size={18} />
      </button>
      {#if streamCount > 0}
        <button class="circle" aria-label="Скачать все" onclick={downloadAll}>
          <Icon name="download" size={18} />
        </button>
      {/if}
    </header>

    <div class="hero-cover">
      <Cover
        path={firstLocal?.path ?? null}
        src={firstStream?.thumbnail ?? null}
        size={260}
        radius={28}
        icon={48}
      />
    </div>

    <div class="detail-actions">
      <button class="pill selected" onclick={playAll} disabled={items.length === 0}>
        <Icon name="play" size={15} />
        Играть
      </button>
      <button class="pill" onclick={() => (editOpen = true)}>Правка</button>
      {#if streamCount > 0}
        <button class="circle" aria-label="Скачать все" onclick={downloadAll}>
          <Icon name="download" size={18} />
        </button>
      {/if}
    </div>

    <div class="sort-pills">
      <button class="pill" class:selected={sort === "added"} onclick={() => (sort = "added")}>
        Как добавлял
      </button>
      <button class="pill" class:selected={sort === "name"} onclick={() => (sort = "name")}>
        Название
      </button>
      <button class="pill" class:selected={sort === "duration"} onclick={() => (sort = "duration")}>
        Длительность
      </button>
      <button class="pill" class:selected={sort === "artist"} onclick={() => (sort = "artist")}>
        Исполнитель
      </button>
    </div>

    <div class="search-field">
      <Icon name="search" size={20} />
      <input bind:value={query} placeholder="Фильтр треков" />
    </div>

    {#if error}
      <div class="error">{error}</div>
    {/if}
    {#if downloadStatus}
      <div class="status">{downloadStatus}</div>
    {/if}

    {#if filtered.length === 0}
      <Glass class="card" padding={18}>
        <div class="section-label">Треки</div>
        <p class="card-text">
          {items.length === 0
            ? "Пока пусто. Добавляй треки из «Плеера» кнопкой «В плейлист»."
            : "По этому фильтру ничего нет."}
        </p>
      </Glass>
    {:else}
      <div class="track-list">
        {#each filtered as item (item.id)}
          <div class="track-row">
            <button class="track-main" onclick={() => playItem(item)}>
              {#if item.kind === "local"}
                <Cover path={item.track.path} size={56} radius={10} />
              {:else}
                <Cover
                  src={item.stream.thumbnail}
                  size={56}
                  radius={10}
                  icon={18}
                />
              {/if}
              <div class="track-meta">
                <div class="track-title">
                  {item.kind === "local" ? item.track.title : item.stream.title}
                </div>
                <div class="track-artist">
                  {item.kind === "local"
                    ? item.track.artist || "<unknown>"
                    : item.stream.artist || "YouTube"}
                </div>
              </div>
              <div class="track-time">
                {fmt(
                  item.kind === "local"
                    ? item.track.durationMs
                    : item.stream.durationMs,
                )}
              </div>
            </button>
            {#if item.kind === "yt"}
              <button
                class="icon-btn"
                aria-label="Скачать"
                onclick={() => downloadStream(item.stream)}
              >
                <Icon name="download" size={17} />
              </button>
            {/if}
            <button
              class="icon-btn"
              aria-label="Убрать"
              onclick={() => removeTrackFromPlaylist(selected.id, item.id)}
            >
              <Icon name="trash" size={17} />
            </button>
          </div>
        {/each}
      </div>
    {/if}

    <button class="pill danger" onclick={() => removePlaylist(selected)}>
      Удалить плейлист
    </button>
  {:else}
    <header class="head">
      <button class="circle" aria-label="Назад" onclick={onBack}>
        <Icon name="arrow-back" size={20} />
      </button>
      <div class="head-meta">
        <h1 class="page-title">
          {segment === "youtube" ? "YouTube" : "Мои плейлисты"}
        </h1>
        <div class="page-sub">
          {segment === "youtube" ? "ONYX · стриминг" : "ONYX · локальные"}
        </div>
      </div>
      <button class="pill selected" onclick={() => (showCreate = true)}>
        <Icon name="playlist-add" size={15} />
        Плейлист
      </button>
    </header>

    {#if list.length === 0}
      <Glass class="card" padding={18}>
        <div class="section-label">Пока пусто</div>
        <p class="card-text">
          {segment === "youtube"
            ? "В плейлистах пока нет YouTube-треков."
            : "Создай первый плейлист и добавляй в него треки из «Плеера»."}
        </p>
      </Glass>
    {:else}
      {#if list.some((playlist) => playlist.pinned)}
        <div class="section-label group-label">Закреплённые</div>
        <div class="playlist-list">
          {#each list.filter((playlist) => playlist.pinned) as playlist (playlist.id)}
            <div class="playlist-row">
              <button class="playlist-main" onclick={() => open(playlist)}>
                <Cover
                  path={$tracks.find((track) => playlist.trackIds.includes(track.path))?.path ?? null}
                  src={playlist.streams[0]?.thumbnail ?? null}
                  size={72}
                  radius={14}
                  icon={22}
                />
                <div class="playlist-meta">
                  <div class="playlist-name">{playlist.name}</div>
                  <div class="playlist-sub">{playlist.trackIds.length} треков</div>
                </div>
              </button>
              <button
                class="icon-btn pinned"
                aria-label="Открепить"
                onclick={() => togglePin(playlist)}
              >
                <Icon name="pin" size={18} />
              </button>
            </div>
          {/each}
        </div>
      {/if}

      {#if list.filter((playlist) => !playlist.pinned).length > 0}
        <div class="section-label group-label">
          {list.some((playlist) => playlist.pinned) ? "Остальные" : "Плейлисты"}
        </div>
        <div class="playlist-list">
          {#each list.filter((playlist) => !playlist.pinned) as playlist (playlist.id)}
            <div class="playlist-row">
              <button class="playlist-main" onclick={() => open(playlist)}>
                <Cover
                  path={$tracks.find((track) => playlist.trackIds.includes(track.path))?.path ?? null}
                  src={playlist.streams[0]?.thumbnail ?? null}
                  size={72}
                  radius={14}
                  icon={22}
                />
                <div class="playlist-meta">
                  <div class="playlist-name">{playlist.name}</div>
                  <div class="playlist-sub">
                    {playlist.trackIds.length} треков
                    {playlist.streams.length > 0 ? " · YouTube" : ""}
                  </div>
                </div>
              </button>
              <button
                class="icon-btn"
                class:pinned={playlist.pinned}
                aria-label={playlist.pinned ? "Открепить" : "Закрепить"}
                onclick={() => togglePin(playlist)}
              >
                <Icon name="pin" size={18} />
              </button>
            </div>
          {/each}
        </div>
      {/if}
    {/if}
  {/if}
</div>

{#if showCreate}
  <div class="modal" role="presentation" onclick={() => (showCreate = false)}>
    <div
      class="modal-card"
      role="dialog"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => {
        if (event.key === "Escape") showCreate = false;
      }}
    >
      <div class="modal-head">
        <div class="section-label">Новый плейлист</div>
        <button class="icon-btn" aria-label="Закрыть" onclick={() => (showCreate = false)}>
          <Icon name="close" size={16} />
        </button>
      </div>
      <label class="field">
        <span>Название</span>
        <input
          bind:value={newName}
          placeholder="Название"
          onkeydown={(event) => event.key === "Enter" && create()}
        />
      </label>
      <button class="pill selected save" onclick={create}>Создать</button>
    </div>
  </div>
{/if}

{#if editOpen && selected}
  <div class="modal" role="presentation" onclick={() => (editOpen = false)}>
    <div
      class="modal-card"
      role="dialog"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => {
        if (event.key === "Escape") editOpen = false;
      }}
    >
      <div class="modal-head">
        <div class="section-label">Правка плейлиста</div>
        <button class="icon-btn" aria-label="Закрыть" onclick={() => (editOpen = false)}>
          <Icon name="close" size={16} />
        </button>
      </div>
      <label class="field">
        <span>Название</span>
        <input bind:value={editName} />
      </label>
      <label class="field">
        <span>Описание</span>
        <input bind:value={editDescription} placeholder="Необязательно" />
      </label>
      <button class="pill selected save" onclick={saveEdit}>Сохранить</button>
    </div>
  </div>
{/if}

<style>
  .playlists-page {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .segments {
    display: flex;
    gap: 8px;
  }

  .head {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 2px;
  }

  .detail-head {
    align-items: center;
  }

  .head-meta {
    flex: 1;
    min-width: 0;
  }

  .head-meta .page-title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .hero-cover {
    display: flex;
    justify-content: center;
    margin-top: 4px;
  }

  .detail-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 8px;
  }

  .sort-pills {
    display: flex;
    gap: 8px;
    overflow-x: auto;
    padding-bottom: 2px;
  }

  .sort-pills .pill {
    flex: 0 0 auto;
  }

  .group-label {
    margin-top: 6px;
    padding-left: 2px;
  }

  .playlist-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .playlist-row {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .playlist-main {
    display: flex;
    align-items: center;
    gap: 12px;
    flex: 1;
    min-width: 0;
    padding: 7px 4px;
    border-radius: 16px;
    text-align: left;
    color: var(--white);
  }

  .playlist-main:hover {
    background: rgba(255, 255, 255, 0.04);
  }

  .playlist-meta {
    flex: 1;
    min-width: 0;
  }

  .playlist-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 16px;
    font-weight: 600;
  }

  .playlist-sub {
    margin-top: 3px;
    color: var(--faint);
    font-size: 12px;
  }

  .icon-btn.pinned {
    color: var(--white);
  }

  .card-text {
    margin: 10px 0 0;
    color: var(--dim);
    font-size: 12px;
    line-height: 18px;
  }

  .status {
    color: var(--faint);
    font-size: 11px;
  }

  .error {
    color: #ff8a5c;
    font-size: 12px;
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

  .danger {
    margin-top: 4px;
    color: #ff8a5c;
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
