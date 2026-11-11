<script lang="ts">
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import {
    playVideo,
    playingId,
    searching,
    search,
    videos,
  } from "../stores/youtube";

  let query = $state("");
  let error = $state("");

  async function run() {
    error = "";
    try {
      await search(query);
    } catch (e) {
      error = String(e);
    }
  }

  function fmt(ms: number) {
    const total = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
  }
</script>

<div class="youtube">
  <header>
    <div class="eyebrow">Стриминг</div>
    <h1>YouTube</h1>
    <div class="count">{$videos.length} результатов</div>
  </header>

  <Glass padding={14}>
    <div class="search-row">
      <Icon name="video-library" size={18} />
      <input
        bind:value={query}
        placeholder="Название, ссылка или исполнитель"
        onkeydown={(event) => event.key === "Enter" && run()}
      />
      <button class="pill selected" onclick={run} disabled={$searching}>
        {$searching ? "Ищем…" : "Найти"}
      </button>
    </div>
  </Glass>

  {#if error}
    <div class="error">{error}</div>
  {/if}

  {#if $videos.length === 0 && !$searching}
    <Glass padding={18}>
      <div class="section-label">Поиск</div>
      <p class="muted">
        Введи запрос — ONYX найдёт треки через yt-dlp и запустит аудио в mpv.
      </p>
    </Glass>
  {:else}
    <Glass class="list" padding={8}>
      {#each $videos as video (video.id)}
        <button
          class="row"
          onclick={() => playVideo(video).catch((e) => (error = String(e)))}
          disabled={$playingId === video.id}
        >
          <div class="thumb">
            {#if video.thumbnail}
              <img src={video.thumbnail} alt="" loading="lazy" />
            {:else}
              <Icon name="video-library" size={20} />
            {/if}
          </div>
          <div class="meta">
            <div class="title">{video.title}</div>
            <div class="sub">
              {[video.uploader, fmt(video.durationMs)].filter(Boolean).join(" · ")}
            </div>
          </div>
          <div class="play-icon">
            <Icon name={$playingId === video.id ? "pause" : "play"} size={16} />
          </div>
        </button>
      {/each}
    </Glass>
  {/if}
</div>

<style>
  .youtube {
    max-width: 820px;
    margin: 0 auto;
    padding: 22px 18px 132px;
    display: flex;
    flex-direction: column;
    gap: 14px;
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

  .search-row {
    display: flex;
    align-items: center;
    gap: 10px;
    color: var(--dim);
  }

  .search-row input {
    flex: 1;
    min-width: 0;
    padding: 8px 0;
    border: 0;
    background: none;
    color: var(--white);
    font: inherit;
    font-size: 14px;
    outline: none;
  }

  .search-row input::placeholder {
    color: var(--faint);
  }

  .muted {
    margin: 10px 0 0;
    color: var(--dim);
    font-size: 13px;
    line-height: 19px;
  }

  .error {
    color: #ff8a5c;
    font-size: 12px;
    line-height: 17px;
  }

  .row {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    padding: 7px 9px;
    border-radius: 14px;
    text-align: left;
    color: var(--white);
  }

  .row:hover {
    background: rgba(255, 255, 255, 0.05);
  }

  .row:disabled {
    opacity: 0.7;
  }

  .thumb {
    display: grid;
    place-items: center;
    width: 64px;
    height: 40px;
    overflow: hidden;
    border-radius: 9px;
    background: rgba(255, 255, 255, 0.05);
    color: var(--faint);
    flex: 0 0 auto;
  }

  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  .meta {
    flex: 1;
    min-width: 0;
  }

  .title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 14px;
    font-weight: 500;
  }

  .sub {
    margin-top: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--faint);
    font-size: 11px;
  }

  .play-icon {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    border-radius: 999px;
    border: 1px solid var(--line);
    color: var(--dim);
    flex: 0 0 auto;
  }
</style>
