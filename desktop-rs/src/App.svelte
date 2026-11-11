<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { onMount } from "svelte";
  import { derived } from "svelte/store";
  import Glass from "./lib/components/Glass.svelte";
  import { player, refreshPlayer } from "./lib/stores/player";

  type Dest = { key: string; label: string; icon: string };

  const dests: Dest[] = [
    { key: "player", label: "Плеер", icon: "≋" },
    { key: "lyrics", label: "Текст", icon: "文" },
    { key: "playlists", label: "Плейлист", icon: "≡" },
    { key: "music", label: "Музыка", icon: "♫" },
    { key: "youtube", label: "YouTube", icon: "▶" },
    { key: "visualizer", label: "Визуал", icon: "▥" },
    { key: "settings", label: "Настройки", icon: "⚙" },
  ];

  let active = $state("player");
  let version = $state("0.9.0");

  const progress = derived(player, ($p) =>
    $p.durationMs > 0 ? Math.min(100, ($p.positionMs / $p.durationMs) * 100) : 0,
  );

  function fmt(ms: number) {
    const total = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
  }

  onMount(() => {
    invoke<string>("app_version")
      .then((v) => (version = v))
      .catch(() => {});
    refreshPlayer();
  });
</script>

<div class="shell">
  <aside class="rail">
    <div class="brand">
      <div class="wordmark">ONYX</div>
      <div class="tagline">music control center</div>
    </div>

    <nav>
      {#each dests as dest}
        <button class:active={active === dest.key} onclick={() => (active = dest.key)}>
          <span class="icon">{dest.icon}</span>
          <span>{dest.label}</span>
        </button>
      {/each}
    </nav>

    <div class="version">v{version}</div>
  </aside>

  <main>
    <header class="topbar">
      <div>
        <div class="eyebrow">{dests.find((d) => d.key === active)?.label}</div>
        <h1>{active === "player" ? "Сейчас играет" : dests.find((d) => d.key === active)?.label}</h1>
      </div>
      <div class="pill">Rust + Tauri · Svelte 5</div>
    </header>

    {#if active === "player"}
      <section class="player">
        <Glass strong>
          <div class="player-inner">
            <div class="cover"><div class="note">♪</div></div>

            <div class="meta">
              <div class="title">{$player.title || "Ничего не играет"}</div>
              <div class="artist">{$player.artist || "Запусти музыку на компьютере"}</div>
              <div class="album">{$player.album}</div>

              <div class="progress">
                <div class="bar"><div class="fill" style={`width:${$progress}%`}></div></div>
                <div class="times">
                  <span>{fmt($player.positionMs)}</span>
                  <span>{fmt($player.durationMs)}</span>
                </div>
              </div>

              <div class="controls">
                <button class="ghost" aria-label="Предыдущий">⏮</button>
                <button
                  class="play"
                  aria-label={$player.playing ? "Пауза" : "Играть"}
                  onclick={() => invoke("player_toggle").then(refreshPlayer)}
                >
                  {$player.playing ? "⏸" : "▶"}
                </button>
                <button class="ghost" aria-label="Следующий">⏭</button>
              </div>
            </div>
          </div>
        </Glass>
      </section>
    {:else}
      <section class="placeholder">
        <Glass>
          <div class="placeholder-title">{dests.find((d) => d.key === active)?.label}</div>
          <p>Экран будет перенесён из Kotlin-версии на следующем этапе.</p>
        </Glass>
      </section>
    {/if}
  </main>
</div>

<style>
  .shell {
    display: grid;
    grid-template-columns: 232px 1fr;
    height: 100%;
  }

  .rail {
    display: flex;
    flex-direction: column;
    gap: 22px;
    padding: 26px 18px;
    border-right: 1px solid var(--line);
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.035), transparent 55%);
  }

  .wordmark {
    font-size: 30px;
    font-weight: 900;
    letter-spacing: 7px;
    line-height: 1;
  }

  .tagline {
    margin-top: 6px;
    color: var(--faint);
    font-size: 9px;
    letter-spacing: 2.6px;
    text-transform: uppercase;
  }

  nav {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  nav button {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 12px;
    border-radius: 14px;
    color: var(--dim);
    text-align: left;
    transition: background 160ms ease, color 160ms ease;
  }

  nav button:hover {
    color: var(--white);
    background: rgba(255, 255, 255, 0.05);
  }

  nav button.active {
    color: #08080a;
    background: var(--accent);
    font-weight: 600;
  }

  .icon {
    width: 18px;
    text-align: center;
    font-size: 15px;
  }

  .version {
    margin-top: auto;
    color: var(--faint);
    font-size: 10px;
    letter-spacing: 1.4px;
  }

  main {
    overflow: auto;
    padding: 30px 34px 40px;
  }

  .topbar {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 26px;
  }

  .eyebrow {
    color: var(--faint);
    font-size: 10px;
    letter-spacing: 2.4px;
    text-transform: uppercase;
  }

  h1 {
    margin: 4px 0 0;
    font-size: 34px;
    font-weight: 800;
    letter-spacing: -1px;
  }

  .pill {
    padding: 8px 13px;
    border: 1px solid var(--line);
    border-radius: 999px;
    color: var(--dim);
    font-size: 11px;
    letter-spacing: 0.4px;
  }

  .player-inner {
    display: grid;
    grid-template-columns: 280px 1fr;
    gap: 34px;
    align-items: center;
    padding: 8px;
  }

  .cover {
    display: grid;
    place-items: center;
    aspect-ratio: 1;
    border-radius: 24px;
    background:
      radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.08), transparent 55%),
      var(--deep);
    border: 1px solid var(--line);
  }

  .note {
    color: var(--faint);
    font-size: 64px;
  }

  .meta {
    min-width: 0;
  }

  .title {
    font-size: 30px;
    font-weight: 800;
    letter-spacing: -0.8px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .artist {
    margin-top: 6px;
    color: var(--dim);
    font-size: 15px;
  }

  .album {
    min-height: 18px;
    margin-top: 3px;
    color: var(--faint);
    font-size: 12px;
  }

  .progress {
    margin-top: 22px;
  }

  .bar {
    height: 6px;
    overflow: hidden;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.08);
  }

  .fill {
    height: 100%;
    border-radius: 999px;
    background: var(--accent);
  }

  .times {
    display: flex;
    justify-content: space-between;
    margin-top: 7px;
    color: var(--faint);
    font-size: 11px;
  }

  .controls {
    display: flex;
    align-items: center;
    gap: 18px;
    margin-top: 22px;
  }

  .ghost,
  .play {
    display: grid;
    place-items: center;
    border-radius: 999px;
  }

  .ghost {
    width: 44px;
    height: 44px;
    border: 1px solid var(--line);
    color: var(--dim);
    font-size: 16px;
  }

  .play {
    width: 62px;
    height: 62px;
    background: var(--accent);
    color: #08080a;
    font-size: 22px;
  }

  .placeholder {
    max-width: 720px;
  }

  .placeholder-title {
    font-size: 22px;
    font-weight: 700;
  }

  .placeholder p {
    color: var(--dim);
    font-size: 14px;
    line-height: 1.6;
  }
</style>
