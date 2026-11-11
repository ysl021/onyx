<script lang="ts">
  import { derived, get } from "svelte/store";
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import { openAudioFile, player, seekTo, togglePlayer } from "../stores/player";
  import { saveSettings, setVolume, settings } from "../stores/settings";

  let { width }: { width: number } = $props();
  let error = $state("");

  const progress = derived(player, ($p) =>
    $p.durationMs > 0 ? Math.min(100, ($p.positionMs / $p.durationMs) * 100) : 0,
  );
  const remaining = derived(player, ($p) =>
    Math.max(0, $p.durationMs - $p.positionMs),
  );

  function fmt(ms: number) {
    const total = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
  }

  async function pickFile() {
    error = "";
    try {
      await openAudioFile();
    } catch (e) {
      error = String(e);
    }
  }

  async function seek(event: MouseEvent) {
    const el = event.currentTarget as HTMLElement;
    const rect = el.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    const current = get(player);
    const seconds = (ratio * current.durationMs) / 1000;
    if (seconds > 0) await seekTo(seconds);
  }

  async function seekKey(event: KeyboardEvent) {
    const step = event.key === "ArrowRight" ? 5 : event.key === "ArrowLeft" ? -5 : 0;
    const current = get(player);
    if (step === 0 || current.durationMs <= 0) return;
    event.preventDefault();
    const at = current.positionMs / 1000;
    const seconds = Math.max(0, Math.min(current.durationMs / 1000, at + step));
    await seekTo(seconds);
  }

  async function changeVolume(event: Event) {
    const value = Number((event.currentTarget as HTMLInputElement).value);
    await setVolume(value);
  }

  async function toggleDiscord() {
    const current = get(settings);
    await saveSettings({ ...current, discordEnabled: !current.discordEnabled });
  }
</script>

{#snippet header()}
  <header class="header">
    <div class="brand">
      <div class="wordmark">ONYX</div>
      <div class="tagline">music control center</div>
    </div>
    <div class="header-actions">
      <button class="circle" aria-label="Открыть файл" title="Открыть файл" onclick={pickFile}>
        <Icon name="library-music" size={17} />
      </button>
      <button class="pill" class:selected={$settings.discordEnabled} onclick={toggleDiscord}>
        <Icon name="cast" size={14} />
        Discord
        {#if $settings.discordEnabled}<span class="dot"></span>{/if}
      </button>
    </div>
  </header>
{/snippet}

{#snippet hero(compact: boolean)}
  <div class="hero" class:compact>
    <button class="cover" aria-label="Открыть текст песни">
      <Icon name="music-note" size={compact ? 42 : 56} />
    </button>
    <div class="title">{$player.title || "Ничего не играет"}</div>
    <div class="artist">{$player.artist || "Выберите трек в библиотеке"}</div>
    {#if $player.album}
      <div class="album">{$player.album}</div>
    {/if}
    <div class="hero-actions">
      <button class="pill">
        <Icon name="translate" size={14} />
        Текст песни
      </button>
      <button class="circle" aria-label="В любимые">
        <Icon name="favorite-border" size={17} />
      </button>
      <button class="circle" aria-label="В плейлист">
        <Icon name="playlist-add" size={17} />
      </button>
    </div>
  </div>
{/snippet}

{#snippet controls()}
  <div class="controls">
    <div
      class="slider"
      role="slider"
      tabindex="0"
      aria-valuemin="0"
      aria-valuemax={$player.durationMs}
      aria-valuenow={$player.positionMs}
      onclick={seek}
      onkeydown={seekKey}
    >
      <div class="track">
        <div class="fill" style={`width:${$progress}%`}></div>
        <div class="thumb" style={`left:${$progress}%`}></div>
      </div>
    </div>
    <div class="times">
      <span>{fmt($player.positionMs)}</span>
      <span>-{fmt($remaining)}</span>
    </div>
    <div class="transport">
      <button class="skip" aria-label="Предыдущий трек">
        <Icon name="skip-previous" size={24} />
      </button>
      <button class="play" aria-label={$player.playing ? "Пауза" : "Играть"} onclick={togglePlayer}>
        <Icon name={$player.playing ? "pause" : "play"} size={32} />
      </button>
      <button class="skip" aria-label="Следующий трек">
        <Icon name="skip-next" size={24} />
      </button>
    </div>
    {#if error}
      <div class="error">{error}</div>
    {/if}
  </div>
{/snippet}

{#snippet outputCard()}
  <Glass class="card" padding={18}>
    <div class="section-label">Вывод звука</div>
    <div class="device-row">
      <button class="pill selected">
        <Icon name="computer" size={14} />
        Система
      </button>
      <button class="pill">
        <Icon name="headphones" size={14} />
        Наушники
      </button>
    </div>
    <div class="volume-row">
      <Icon name="volume" size={16} />
      <input
        type="range"
        min="0"
        max="100"
        value={$settings.volume}
        oninput={changeVolume}
        aria-label="Громкость"
      />
      <span>{$settings.volume}%</span>
    </div>
  </Glass>
{/snippet}

{#snippet sleepCard()}
  <Glass class="card" padding={18}>
    <div class="section-label">Таймер сна</div>
    <div class="pill-row">
      {#each [15, 30, 45, 60] as minutes}
        <button class="pill">{minutes} мин</button>
      {/each}
      <button class="pill">Выкл</button>
    </div>
  </Glass>
{/snippet}

{#snippet queueCard()}
  <Glass class="card" padding={18}>
    <div class="section-label">Очередь</div>
    <div class="queue-empty">
      Очередь появится, когда добавим библиотеку и плейлисты.
    </div>
  </Glass>
{/snippet}

{#if width >= 900}
  <div class="wide">
    <div class="wide-hero">
      {@render header()}
      {@render hero(true)}
      {@render controls()}
    </div>
    <div class="wide-side">
      {@render outputCard()}
      {@render sleepCard()}
      {@render queueCard()}
    </div>
  </div>
{:else}
  <div class="phone">
    {@render header()}
    {@render hero(false)}
    {@render controls()}
    {@render outputCard()}
    {@render sleepCard()}
    {@render queueCard()}
  </div>
{/if}

<style>
  .phone {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 16px;
    padding: 16px 18px 132px;
  }

  .phone :global(.card) {
    width: 100%;
    max-width: 720px;
  }

  .wide {
    display: flex;
    justify-content: center;
    align-items: flex-start;
    gap: 28px;
    padding: 22px 28px 116px;
    max-width: 100%;
    box-sizing: border-box;
  }

  .wide-hero {
    flex: 0 0 380px;
    width: 380px;
    min-width: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 14px;
  }

  .wide-side {
    flex: 0 0 480px;
    width: 480px;
    min-width: 0;
    max-width: 480px;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .wide-side :global(.card) {
    width: 100%;
  }

  .header {
    width: 100%;
    max-width: 720px;
    min-width: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .wide-hero .header {
    max-width: 380px;
  }

  .brand {
    min-width: 0;
  }

  .wordmark {
    font-size: 26px;
    font-weight: 900;
    letter-spacing: 6px;
    line-height: 1;
    white-space: nowrap;
  }

  .tagline {
    margin-top: 5px;
    color: var(--faint);
    font-size: 9px;
    letter-spacing: 2.4px;
    text-transform: uppercase;
    white-space: nowrap;
  }

  .header-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .dot {
    width: 7px;
    height: 7px;
    border-radius: 999px;
    background: #57c77a;
  }

  .hero {
    width: 100%;
    max-width: 720px;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .wide-hero .hero {
    max-width: 380px;
  }

  .cover {
    display: grid;
    place-items: center;
    width: min(68%, 240px);
    aspect-ratio: 1;
    border-radius: 26px;
    border: 1px solid rgba(255, 255, 255, 0.05);
    background:
      radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.07), transparent 55%),
      var(--deep);
    color: var(--faint);
    transition: transform 180ms ease;
  }

  .cover:hover {
    transform: scale(1.01);
  }

  .wide-hero .cover {
    width: 100%;
    max-width: 280px;
  }

  .hero.compact .cover {
    max-width: 200px;
  }

  .title {
    width: 92%;
    margin-top: 18px;
    color: var(--white);
    font-size: 25px;
    line-height: 30px;
    font-weight: 700;
    letter-spacing: -0.4px;
    text-align: center;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }

  .hero.compact .title {
    margin-top: 10px;
    font-size: 21px;
    line-height: 25px;
    -webkit-line-clamp: 1;
  }

  .artist {
    width: 90%;
    margin-top: 6px;
    color: var(--dim);
    font-size: 14px;
    text-align: center;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .hero.compact .artist {
    margin-top: 3px;
  }

  .album {
    margin-top: 3px;
    color: var(--faint);
    font-size: 11px;
    max-width: 90%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .hero-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 12px;
  }

  .hero.compact .hero-actions {
    margin-top: 8px;
  }

  .controls {
    width: 100%;
    max-width: 720px;
  }

  .wide-hero .controls {
    max-width: 380px;
  }

  .slider {
    padding: 6px 0;
    cursor: pointer;
  }

  .track {
    position: relative;
    height: 6px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.08);
  }

  .fill {
    height: 100%;
    border-radius: 999px;
    background: var(--accent);
  }

  .thumb {
    position: absolute;
    top: 50%;
    width: 3px;
    height: 18px;
    border-radius: 999px;
    background: var(--accent);
    transform: translate(-50%, -50%);
  }

  .times {
    display: flex;
    justify-content: space-between;
    margin-top: 2px;
    color: var(--faint);
    font-size: 11px;
    letter-spacing: 0.6px;
  }

  .transport {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 22px;
    margin-top: 10px;
  }

  .skip {
    display: grid;
    place-items: center;
    width: 54px;
    height: 54px;
    border-radius: 999px;
    border: 1px solid var(--line);
    color: var(--dim);
    transition: color 160ms ease, background 160ms ease;
  }

  .skip:hover {
    color: var(--white);
    background: rgba(255, 255, 255, 0.05);
  }

  .play {
    display: grid;
    place-items: center;
    width: 74px;
    height: 74px;
    border-radius: 999px;
    background: var(--accent);
    color: #08080a;
  }

  .error {
    margin-top: 12px;
    color: #ff8a5c;
    font-size: 12px;
    line-height: 17px;
    text-align: center;
  }

  .device-row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }

  .volume-row {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 14px;
    color: var(--dim);
  }

  .volume-row input {
    flex: 1;
    accent-color: var(--accent);
  }

  .volume-row span {
    min-width: 36px;
    color: var(--faint);
    font-size: 11px;
    text-align: right;
  }

  .pill-row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }

  .queue-empty {
    margin-top: 10px;
    color: var(--faint);
    font-size: 12px;
    line-height: 17px;
  }
</style>
