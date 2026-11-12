<script lang="ts">
  import { onMount } from "svelte";
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import Cover from "../components/Cover.svelte";
  import { cover, currentTrack, player, seekTo } from "../stores/player";
  import { lyrics, lyricsLoading, loadLyrics } from "../stores/lyrics";

  let { onBack }: { onBack: () => void } = $props();

  let fontSize = $state(28);
  let offsetMs = $state(0);

  const trackKey = $derived(
    `${$player.artist}|${$player.title}|${$player.album}|${$player.durationMs}`,
  );

  $effect(() => {
    trackKey;
    loadLyrics();
  });

  const activeIndex = $derived.by(() => {
    const data = $lyrics;
    if (!data || !data.synced) return -1;
    const position = $player.positionMs + offsetMs;
    let index = -1;
    for (let i = 0; i < data.lines.length; i++) {
      if (data.lines[i].timeMs <= position) index = i;
      else break;
    }
    return index;
  });

  $effect(() => {
    const index = activeIndex;
    if (index < 0) return;
    const element = document.querySelector(
      `.lyric-line[data-index="${index}"]`,
    );
    element?.scrollIntoView({ block: "center", behavior: "smooth" });
  });

  onMount(() => {
    loadLyrics(true);
  });

  async function seek(line: { timeMs: number }) {
    if (line.timeMs > 0) await seekTo(line.timeMs / 1000);
  }
</script>

<div class="page lyrics-page">
  <header class="head">
    <button class="circle" aria-label="Назад" onclick={onBack}>
      <Icon name="arrow-back" size={20} />
    </button>
    <Cover src={$cover} size={44} radius={10} icon={16} />
    <div class="head-meta">
      <div class="track-name">{$player.title || "ONYX"}</div>
      <div class="track-artist">{$player.artist}</div>
    </div>
    <button
      class="circle"
      aria-label="Обновить текст"
      onclick={() => loadLyrics(true)}
    >
      <Icon name="refresh" size={19} />
    </button>
  </header>

  {#if $lyricsLoading}
    <div class="empty">
      <div class="empty-title">Ищем текст…</div>
    </div>
  {:else if !$lyrics}
    <div class="empty">
      <div class="empty-title">Тишина</div>
      <div class="empty-sub">
        {$currentTrack && $player.title
          ? "Текст не найден. Проверь название и исполнителя."
          : "Запусти трек — текст подтянется сам."}
      </div>
    </div>
  {:else}
    <div class="lines" class:plain={!$lyrics.synced}>
      {#each $lyrics.lines as line, index}
        <button
          class="lyric-line"
          class:active={index === activeIndex}
          data-index={index}
          style={`font-size:${fontSize}px`}
          onclick={() => seek(line)}
        >
          {line.text}
        </button>
      {/each}
    </div>
  {/if}

  <div class="toolbar-wrap">
    <Glass class="lyrics-toolbar" padding={16}>
      <div class="toolbar-row">
        <button class="pill" onclick={() => (fontSize = Math.max(16, fontSize - 2))}>
          A−
        </button>
        <button class="pill" onclick={() => (fontSize = Math.min(48, fontSize + 2))}>
          A+
        </button>
        <span class="font-label">Aa {fontSize}</span>
        <button class="pill" onclick={() => loadLyrics(true)}>
          <Icon name="refresh" size={15} />
        </button>
      </div>
      <div class="toolbar-row offset-row">
        <span class="offset-label">Смещение</span>
        <button class="pill" onclick={() => (offsetMs -= 100)}>−0.1</button>
        <button
          class="pill"
          class:selected={offsetMs !== 0}
          onclick={() => (offsetMs = 0)}
        >
          {offsetMs} мс
        </button>
        <button class="pill" onclick={() => (offsetMs += 100)}>+0.1</button>
      </div>
    </Glass>
  </div>
</div>

<style>
  .lyrics-page {
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-height: calc(100vh - 24px);
    padding-bottom: 24px;
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

  .track-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 18px;
    font-weight: 700;
    letter-spacing: -0.3px;
  }

  .track-artist {
    margin-top: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--faint);
    font-size: 11px;
  }

  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    flex: 1;
    min-height: 0;
    text-align: center;
  }

  .empty-title {
    font-size: 30px;
    font-weight: 700;
    letter-spacing: -0.6px;
  }

  .empty-sub {
    max-width: 320px;
    color: var(--dim);
    font-size: 14px;
    line-height: 20px;
  }

  .lines {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding-top: 8px;
  }

  .lines.plain {
    gap: 4px;
  }

  .lyric-line {
    display: block;
    width: 100%;
    padding: 6px 0;
    border: 0;
    background: none;
    color: var(--faint);
    font-weight: 700;
    line-height: 1.35;
    text-align: left;
    transition: color 180ms ease, transform 180ms ease;
    scroll-margin: 40vh;
  }

  .lyric-line.active {
    color: var(--white);
    transform: translateX(4px);
  }

  .lyric-line:hover {
    color: var(--dim);
  }

  .toolbar-wrap {
    position: sticky;
    bottom: 18px;
    margin-top: 0;
  }

  .toolbar-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }

  .toolbar-row + .toolbar-row {
    margin-top: 10px;
  }

  .font-label {
    color: var(--faint);
    font-size: 12px;
  }

  .offset-row {
    gap: 8px;
  }

  .offset-label {
    color: var(--faint);
    font-size: 12px;
    margin-right: 2px;
  }
</style>
