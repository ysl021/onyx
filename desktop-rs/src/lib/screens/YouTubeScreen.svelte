<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { onDestroy, onMount } from "svelte";
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import Cover from "../components/Cover.svelte";
  import {
    addDownload,
    cancelDownload,
    clearDownloads,
    downloads,
    loadDownloads,
    loadPlaylist,
    playVideo,
    playingId,
    removeDownload,
    searching,
    search,
    videos,
  } from "../stores/youtube";
  import type { Video } from "../stores/youtube";
  import { queue } from "../stores/queue";
  import { toggleFavorite } from "../stores/favorites";
  import type { Track } from "../stores/library";

  let {
    onBack,
    onOpenSettings,
  }: { onBack: () => void; onOpenSettings: () => void } = $props();

  const RECENT_KEY = "onyx.youtube.recent";

  let query = $state("");
  let musicOnly = $state(true);
  let error = $state("");
  let showRecent = $state(false);
  let linkOpen = $state(false);
  let linkValue = $state("");
  let downloadTarget = $state<Video | null>(null);
  let mode = $state<"whole" | "section">("whole");
  let startText = $state("0:00");
  let endText = $state("");
  let recent = $state<string[]>(loadRecent());
  let polling: ReturnType<typeof setInterval> | null = null;

  onMount(() => {
    loadDownloads();
    polling = setInterval(() => {
      if (
        $downloads.some(
          (job) =>
            job.status === "downloading" ||
            job.status === "converting" ||
            job.status === "queued",
        )
      ) {
        loadDownloads();
      }
    }, 1000);
  });

  onDestroy(() => {
    if (polling !== null) clearInterval(polling);
  });

  function loadRecent(): string[] {
    try {
      const raw = localStorage.getItem(RECENT_KEY);
      return raw ? (JSON.parse(raw) as string[]) : [];
    } catch {
      return [];
    }
  }

  function remember(term: string) {
    recent = [term, ...recent.filter((item) => item !== term)].slice(0, 8);
    try {
      localStorage.setItem(RECENT_KEY, JSON.stringify(recent));
    } catch {
      // Storage can be unavailable; the list still works this session.
    }
  }

  function removeRecent(term: string) {
    recent = recent.filter((item) => item !== term);
    try {
      localStorage.setItem(RECENT_KEY, JSON.stringify(recent));
    } catch {}
  }

  function clearRecent() {
    recent = [];
    try {
      localStorage.removeItem(RECENT_KEY);
    } catch {}
  }

  function extractVideoId(value: string): string | null {
    const text = value.trim();
    const match = text.match(
      /(?:v=|youtu\.be\/|shorts\/|embed\/)([A-Za-z0-9_-]{11})/,
    );
    if (match) return match[1];
    if (/^[A-Za-z0-9_-]{11}$/.test(text)) return text;
    return null;
  }

  async function run(term?: string) {
    const value = (term ?? query).trim();
    if (!value) return;
    error = "";
    showRecent = false;
    const id = extractVideoId(value);
    if (id) {
      await playVideo({
        id,
        title: "YouTube",
        uploader: "",
        durationMs: 0,
        thumbnail: `https://i.ytimg.com/vi/${id}/hqdefault.jpg`,
      }).catch((e) => (error = String(e)));
      return;
    }
    query = value;
    remember(value);
    try {
      await search(value, musicOnly);
    } catch (e) {
      error = String(e);
    }
  }

  async function toggleMusic() {
    musicOnly = !musicOnly;
    if (query.trim() && !extractVideoId(query)) {
      await run(query);
    }
  }

  async function submitLink() {
    const value = linkValue.trim();
    if (!value) return;
    linkOpen = false;
    error = "";
    showRecent = false;
    if (value.includes("list=") || value.includes("/playlist")) {
      try {
        await loadPlaylist(value);
        if ($videos.length === 0) {
          error = "В миксе не нашлось треков";
        }
      } catch (e) {
        error = String(e);
      }
      return;
    }
    await run(value);
  }

  function fmt(ms: number) {
    const total = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
  }

  function parseTime(value: string): number {
    const parts = value
      .trim()
      .split(":")
      .map((part) => Number(part.replace(",", ".")));
    if (parts.some((part) => Number.isNaN(part))) return 0;
    if (parts.length === 1) return Math.max(0, parts[0] * 1000);
    if (parts.length === 2) {
      return Math.max(0, (parts[0] * 60 + parts[1]) * 1000);
    }
    return Math.max(0, ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000);
  }

  function videoTrack(video: Video): Track {
    return {
      id: `yt:${video.id}`,
      path: `yt:${video.id}`,
      title: video.title,
      artist: video.uploader,
      album: "",
      durationMs: video.durationMs,
      hasCover: false,
      artwork: video.thumbnail,
    };
  }

  function enqueue(video: Video) {
    const track = videoTrack(video);
    queue.update((list) =>
      list.some((item) => item.id === track.id) ? list : [...list, track],
    );
  }

  function openDownload(video: Video) {
    downloadTarget = video;
    mode = "whole";
    startText = "0:00";
    endText = fmt(video.durationMs);
    error = "";
  }

  async function startDownload() {
    if (!downloadTarget) return;
    error = "";
    const sections =
      mode === "section"
        ? [{ startMs: parseTime(startText), endMs: parseTime(endText) }]
        : [];
    if (mode === "section" && sections[0].endMs <= sections[0].startMs) {
      error = "Конец фрагмента должен быть позже начала";
      return;
    }
    try {
      await addDownload(downloadTarget, sections);
      downloadTarget = null;
    } catch (e) {
      error = String(e);
    }
  }

  function statusLabel(status: string) {
    switch (status) {
      case "queued":
        return "В очереди";
      case "downloading":
        return "Скачивание";
      case "converting":
        return "Нарезка";
      case "done":
        return "Готово";
      case "cancelled":
        return "Отменено";
      default:
        return "Ошибка";
    }
  }

  async function openFolder() {
    try {
      await invoke("open_downloads_folder");
    } catch (e) {
      error = String(e);
    }
  }

  function scrollToDownloads() {
    document.querySelector(".downloads-card")?.scrollIntoView({
      behavior: "smooth",
      block: "start",
    });
  }
</script>

<div class="page youtube-page">
  <header class="head">
    <button class="circle" aria-label="Назад" onclick={onBack}>
      <Icon name="arrow-back" size={20} />
    </button>
    <div class="head-meta">
      <h1 class="page-title">YouTube</h1>
      <div class="page-sub">Поиск треков и нарезка миксов…</div>
    </div>
    <div class="head-actions">
      <button
        class="circle"
        aria-label="Недавние запросы"
        onclick={() => {
          showRecent = true;
          query = "";
          videos.set([]);
        }}
      >
        <Icon name="history" size={19} />
      </button>
      <button class="circle" aria-label="Загрузки" onclick={scrollToDownloads}>
        <Icon name="queue-music" size={19} />
      </button>
      <button class="circle" aria-label="Папка загрузок" onclick={openFolder}>
        <Icon name="folder" size={19} />
      </button>
    </div>
  </header>

  <div class="top-pills">
    <button class="pill" onclick={() => (linkOpen = true)}>
      <Icon name="link" size={15} />
      Микс по ссылке
    </button>
    <span class="source">onyx</span>
  </div>

  <div class="music-row">
    <button class="pill" class:selected={musicOnly} onclick={toggleMusic}>
      <Icon name="music-note" size={15} />
      Только музыка
    </button>
    <span class="hint">поиск в YouTube Music</span>
  </div>

  <div class="search-field">
    <Icon name="search" size={20} />
    <input
      bind:value={query}
      placeholder="Название трека, микса или канала"
      onkeydown={(event) => event.key === "Enter" && run()}
    />
    {#if query}
      <button class="icon-btn" aria-label="Очистить" onclick={() => (query = "")}>
        <Icon name="close" size={16} />
      </button>
    {/if}
  </div>

  {#if error}
    <div class="error">{error}</div>
    {#if /vpn|прокси/i.test(error)}
      <button class="pill" onclick={onOpenSettings}>Настройки → Интеграции</button>
    {/if}
  {/if}

  {#if $downloads.length > 0}
    <Glass class="card downloads-card" padding={16}>
      <div class="downloads-head">
        <div class="section-label">Загрузки</div>
        <div class="download-actions">
          <button class="pill" onclick={openFolder}>Папка</button>
          <button class="pill" onclick={clearDownloads}>Очистить</button>
        </div>
      </div>
      <div class="jobs">
        {#each $downloads as job (job.id)}
          <div class="job">
            <div class="job-meta">
              <div class="job-title">{job.title}</div>
              <div class="job-sub">
                <span class:active={job.status === "downloading" || job.status === "converting"}>
                  {statusLabel(job.status)}
                </span>
                {#if job.message && job.status !== "done"} · {job.message}{/if}
                {#if job.status === "done" && job.outputs.length > 0}
                  · {job.outputs.length} файл(ов)
                {/if}
              </div>
              {#if job.status === "downloading" || job.status === "converting"}
                <div class="progress">
                  <div style={`width:${Math.min(100, job.progress)}%`}></div>
                </div>
              {/if}
            </div>
            {#if job.status === "downloading" || job.status === "converting" || job.status === "queued"}
              <button
                class="icon-btn"
                aria-label="Отменить"
                onclick={() => cancelDownload(job.id)}
              >
                <Icon name="close" size={15} />
              </button>
            {:else}
              <button
                class="icon-btn"
                aria-label="Убрать"
                onclick={() => removeDownload(job.id)}
              >
                <Icon name="close" size={15} />
              </button>
            {/if}
          </div>
        {/each}
      </div>
    </Glass>
  {/if}

  {#if $searching}
    <div class="status">Ищу в YouTube…</div>
  {:else if showRecent || ($videos.length === 0 && !query)}
    <div class="recent-head">
      <div class="section-label">Недавние запросы</div>
      {#if recent.length > 0}
        <button class="pill" onclick={clearRecent}>Очистить</button>
      {/if}
    </div>
    {#if recent.length === 0}
      <Glass class="card" padding={18}>
        <div class="section-label">Рекомендации</div>
        <p class="card-text">
          Введи запрос или вставь ссылку — ONYX найдёт трек, включит его в mpv
          или поставит в загрузки.
        </p>
      </Glass>
    {:else}
      <div class="recent-list">
        {#each recent as term (term)}
          <div class="recent-row">
            <button class="recent-main" onclick={() => run(term)}>
              <Icon name="history" size={18} />
              <span>{term}</span>
            </button>
            <button
              class="icon-btn"
              aria-label="Удалить запрос"
              onclick={() => removeRecent(term)}
            >
              <Icon name="trash" size={16} />
            </button>
          </div>
        {/each}
      </div>
    {/if}
  {:else}
    <div class="section-label">Результаты</div>
    <div class="result-list">
      {#each $videos as video (video.id)}
        <div class="result-row">
          <button
            class="result-main"
            onclick={() => playVideo(video).catch((e) => (error = String(e)))}
            disabled={$playingId === video.id}
          >
            <Cover src={video.thumbnail} size={64} radius={12} icon={20} />
            <div class="result-meta">
              <div class="result-title">{video.title}</div>
              <div class="result-sub">
                {[video.uploader, fmt(video.durationMs)].filter(Boolean).join(" · ")}
              </div>
            </div>
            <div class="play-icon">
              <Icon name={$playingId === video.id ? "pause" : "play"} size={16} />
            </div>
          </button>
          <div class="result-actions">
            <button
              class="icon-btn"
              aria-label="Исполнитель"
              onclick={() => video.uploader && run(video.uploader)}
            >
              <Icon name="user" size={17} />
            </button>
            <button class="icon-btn" aria-label="В очередь" onclick={() => enqueue(video)}>
              <Icon name="queue-music" size={17} />
            </button>
            <button
              class="icon-btn"
              aria-label="В любимые"
              onclick={() => toggleFavorite(videoTrack(video))}
            >
              <Icon name="favorite-border" size={17} />
            </button>
            <button class="icon-btn" aria-label="Скачать" onclick={() => openDownload(video)}>
              <Icon name="download" size={17} />
            </button>
          </div>
        </div>
      {/each}
    </div>
  {/if}
</div>

{#if linkOpen}
  <div class="modal" role="presentation" onclick={() => (linkOpen = false)}>
    <div
      class="modal-card"
      role="dialog"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => {
        if (event.key === "Escape") linkOpen = false;
      }}
    >
      <div class="modal-head">
        <div class="section-label">Микс по ссылке</div>
        <button class="icon-btn" aria-label="Закрыть" onclick={() => (linkOpen = false)}>
          <Icon name="close" size={16} />
        </button>
      </div>
      <label class="field">
        <span>Ссылка YouTube</span>
        <input
          bind:value={linkValue}
          placeholder="https://youtube.com/watch?v=… или ссылка на микс"
          onkeydown={(event) => event.key === "Enter" && submitLink()}
        />
      </label>
      <button class="pill selected save" onclick={submitLink}>Открыть</button>
    </div>
  </div>
{/if}

{#if downloadTarget}
  <div class="modal" role="presentation" onclick={() => (downloadTarget = null)}>
    <div
      class="modal-card"
      role="dialog"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => {
        if (event.key === "Escape") downloadTarget = null;
      }}
    >
      <div class="modal-head">
        <div>
          <div class="section-label">Скачать</div>
          <div class="modal-title">{downloadTarget.title}</div>
          <div class="modal-sub">{downloadTarget.uploader}</div>
        </div>
        <button class="icon-btn" aria-label="Закрыть" onclick={() => (downloadTarget = null)}>
          <Icon name="close" size={16} />
        </button>
      </div>

      <div class="mode-row">
        <button class="pill" class:selected={mode === "whole"} onclick={() => (mode = "whole")}>
          Целиком
        </button>
        <button class="pill" class:selected={mode === "section"} onclick={() => (mode = "section")}>
          Фрагмент
        </button>
      </div>

      {#if mode === "section"}
        <div class="time-row">
          <label>
            <span>Начало</span>
            <input bind:value={startText} placeholder="0:00" />
          </label>
          <label>
            <span>Конец</span>
            <input bind:value={endText} placeholder="1:30" />
          </label>
        </div>
        <p class="hint">Формат: мм:сс или ч:мм:сс. Нарезка идёт через mpv.</p>
      {/if}

      {#if error}
        <div class="error">{error}</div>
      {/if}

      <button class="pill selected save" onclick={startDownload}>
        <Icon name="download" size={14} />
        {mode === "section" ? "Скачать фрагмент" : "Скачать трек"}
      </button>
    </div>
  </div>
{/if}

<style>
  .youtube-page {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .head {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .head-meta {
    flex: 1;
    min-width: 0;
  }

  .head-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .top-pills {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  .source {
    color: var(--faint);
    font-size: 12px;
  }

  .music-row {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .hint {
    color: var(--faint);
    font-size: 12px;
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

  .downloads-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  .download-actions {
    display: flex;
    gap: 8px;
  }

  .jobs {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin-top: 10px;
  }

  .job {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 10px;
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.035);
  }

  .job-meta {
    flex: 1;
    min-width: 0;
  }

  .job-title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 13px;
    font-weight: 500;
  }

  .job-sub {
    margin-top: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--faint);
    font-size: 11px;
  }

  .job-sub .active {
    color: var(--white);
  }

  .progress {
    height: 3px;
    margin-top: 6px;
    overflow: hidden;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.08);
  }

  .progress div {
    height: 100%;
    border-radius: 999px;
    background: var(--white);
    transition: width 240ms ease;
  }

  .recent-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  .recent-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .recent-row {
    display: flex;
    align-items: center;
    gap: 4px;
    border-radius: 16px;
    background: rgba(255, 255, 255, 0.035);
  }

  .recent-main {
    display: flex;
    align-items: center;
    gap: 12px;
    flex: 1;
    min-width: 0;
    padding: 14px 14px;
    text-align: left;
    color: var(--white);
  }

  .recent-main span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 15px;
  }

  .result-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .result-row {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .result-main {
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

  .result-main:hover {
    background: rgba(255, 255, 255, 0.04);
  }

  .result-main:disabled {
    opacity: 0.7;
  }

  .result-meta {
    flex: 1;
    min-width: 0;
  }

  .result-title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 15px;
    font-weight: 500;
  }

  .result-sub {
    margin-top: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--faint);
    font-size: 12px;
  }

  .play-icon {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--dim);
    flex: 0 0 auto;
  }

  .result-actions {
    display: flex;
    gap: 0;
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
    width: min(460px, 100%);
    padding: 18px;
    border: 1px solid var(--line);
    border-radius: 26px;
    background: var(--card-strong);
  }

  .modal-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
  }

  .modal-title {
    margin-top: 6px;
    font-size: 15px;
    font-weight: 600;
    line-height: 20px;
  }

  .modal-sub {
    margin-top: 2px;
    color: var(--faint);
    font-size: 11px;
  }

  .mode-row {
    display: flex;
    gap: 8px;
    margin-top: 16px;
  }

  .time-row {
    display: flex;
    gap: 10px;
    margin-top: 12px;
  }

  .time-row label,
  .field {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 5px;
    margin-top: 10px;
    color: var(--faint);
    font-size: 10px;
    letter-spacing: 1.1px;
    text-transform: uppercase;
  }

  .time-row input,
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

  .time-row input:focus,
  .field input:focus {
    border-color: var(--line-strong);
  }

  .save {
    width: 100%;
    margin-top: 16px;
  }
</style>
