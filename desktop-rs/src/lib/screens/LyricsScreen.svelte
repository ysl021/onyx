<script lang="ts">
  import { onMount } from "svelte";
  import { player, seekTo } from "../stores/player";
  import { lyrics, lyricsLoading, loadLyrics } from "../stores/lyrics";

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

<div class="lyrics">
  <header>
    <div class="eyebrow">Текст</div>
    <h1>{$player.title || "Ничего не играет"}</h1>
    <div class="artist">{$player.artist}</div>
  </header>

  {#if $lyricsLoading}
    <div class="muted">Ищем текст…</div>
  {:else if !$lyrics}
    <div class="muted">
      Текст не найден. Проверь название и исполнителя или обнови поиск.
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

  <div class="toolbar">
    <button class="pill" onclick={() => (fontSize = Math.max(16, fontSize - 2))}>
      A−
    </button>
    <button class="pill" onclick={() => (fontSize = Math.min(48, fontSize + 2))}>
      A+
    </button>
    <button class="pill" onclick={() => (offsetMs -= 100)}>−0.1</button>
    <button
      class="pill"
      class:selected={offsetMs !== 0}
      onclick={() => (offsetMs = 0)}
    >
      {offsetMs} мс
    </button>
    <button class="pill" onclick={() => (offsetMs += 100)}>+0.1</button>
    <button class="pill" onclick={() => loadLyrics(true)}>Обновить</button>
  </div>
</div>

<style>
  .lyrics {
    max-width: 820px;
    margin: 0 auto;
    padding: 22px 18px 132px;
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

  .artist {
    margin-top: 4px;
    color: var(--dim);
    font-size: 14px;
  }

  .muted {
    margin-top: 24px;
    color: var(--dim);
    font-size: 14px;
    line-height: 21px;
  }

  .lines {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin-top: 22px;
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

  .toolbar {
    position: sticky;
    bottom: 76px;
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 22px;
    padding: 10px 0;
    background: linear-gradient(180deg, transparent, rgba(5, 5, 6, 0.9) 30%);
  }
</style>
