<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { get } from "svelte/store";
  import { onDestroy, onMount } from "svelte";
  import Icon from "../components/Icon.svelte";
  import {
    cover,
    currentPath,
    player,
    playNext,
    playPrevious,
    togglePlayer,
  } from "../stores/player";

  const BANDS = 64;
  const BARS = 108;

  let canvas = $state<HTMLCanvasElement | null>(null);
  let bands = new Float32Array(BANDS);
  let display = new Float32Array(BARS);
  let target = new Float32Array(BARS);
  let idle = true;
  let timer: ReturnType<typeof setInterval> | null = null;
  let raf = 0;
  let lastFrame = 0;

  $effect(() => {
    const path = $currentPath;
    if (!path || path.startsWith("yt:")) return;
    invoke("visualizer_prepare", { path, proxy: null }).catch(() => {});
  });

  onMount(() => {
    const path = get(currentPath);
    if (path && !path.startsWith("yt:")) {
      invoke("visualizer_prepare", { path, proxy: null }).catch(() => {});
    }
    timer = setInterval(async () => {
      const state = get(player);
      try {
        const values = await invoke<number[]>("visualizer_bands", {
          positionMs: state.positionMs,
          playing: state.playing,
        });
        bands = Float32Array.from(values);
        const has = values.some((value) => value > 0.03);
        idle = !has;
        if (has) {
          for (let index = 0; index < BARS; index += 1) {
            const position = (index / (BARS - 1)) * (BANDS - 1);
            const low = Math.min(BANDS - 1, Math.max(0, Math.floor(position)));
            const high = Math.min(BANDS - 1, low + 1);
            const blend = position - low;
            target[index] = Math.min(
              1,
              Math.max(0, bands[low] * (1 - blend) + bands[high] * blend),
            );
          }
        }
      } catch {
        // The backend is not ready yet.
      }
    }, 50);
    raf = requestAnimationFrame(render);
  });

  onDestroy(() => {
    if (timer !== null) clearInterval(timer);
    cancelAnimationFrame(raf);
    invoke("visualizer_stop").catch(() => {});
  });

  function rgb(): string {
    return document.documentElement.dataset.theme === "light"
      ? "10, 10, 12"
      : "245, 245, 242";
  }

  function render(now: number) {
    const dt =
      lastFrame === 0
        ? 0.016
        : Math.min(0.05, Math.max(0.001, (now - lastFrame) / 1000));
    lastFrame = now;
    const time = now / 1000;
    for (let index = 0; index < BARS; index += 1) {
      const goal = idle
        ? Math.min(
            0.16,
            Math.max(
              0,
              0.05 +
                0.05 * Math.sin(time * 1.4 + index * 0.33) +
                0.02 * Math.sin(time * 2.1 - index * 0.2),
            ),
          )
        : target[index];
      const speed = goal > display[index] ? 14 : 5;
      display[index] += (goal - display[index]) * (1 - Math.exp(-speed * dt));
    }
    draw();
    raf = requestAnimationFrame(render);
  }

  function draw() {
    const element = canvas;
    if (!element) return;
    const ratio = window.devicePixelRatio || 1;
    const width = element.clientWidth;
    const height = element.clientHeight;
    if (width === 0 || height === 0) return;
    if (element.width !== width * ratio || element.height !== height * ratio) {
      element.width = width * ratio;
      element.height = height * ratio;
    }
    const context = element.getContext("2d");
    if (!context) return;
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, width, height);

    const centerX = width / 2;
    const centerY = height / 2;
    const min = Math.min(width, height);
    const inner = min * 0.235;
    const maxLength = min * 0.225;
    const twoPi = Math.PI * 2;
    const color = rgb();

    for (let index = 0; index < BARS; index += 1) {
      const angle = -Math.PI / 2 + (index * twoPi) / BARS;
      const dx = Math.cos(angle);
      const dy = Math.sin(angle);
      const amplitude = Math.max(1.5, display[index] * maxLength);
      const startX = centerX + dx * inner;
      const startY = centerY + dy * inner;

      context.beginPath();
      context.strokeStyle = `rgba(${color}, 0.08)`;
      context.lineWidth = 4.6;
      context.lineCap = "round";
      context.moveTo(startX, startY);
      context.lineTo(
        centerX + dx * (inner + amplitude * 1.28),
        centerY + dy * (inner + amplitude * 1.28),
      );
      context.stroke();

      context.beginPath();
      context.strokeStyle = `rgba(${color}, 0.86)`;
      context.lineWidth = 1.8;
      context.moveTo(startX, startY);
      context.lineTo(centerX + dx * (inner + amplitude), centerY + dy * (inner + amplitude));
      context.stroke();
    }

    context.beginPath();
    context.strokeStyle = `rgba(${color}, 0.16)`;
    context.lineWidth = 1.1;
    context.arc(centerX, centerY, inner, 0, twoPi);
    context.stroke();

    context.beginPath();
    context.strokeStyle = `rgba(${color}, 0.06)`;
    context.lineWidth = 0.8;
    context.arc(centerX, centerY, inner + maxLength + 2, 0, twoPi);
    context.stroke();
  }
</script>

<div class="visualizer">
  <header>
    <div class="eyebrow">Визуализатор</div>
    <h1>{$player.title || "Тишина"}</h1>
    <div class="artist">{$player.artist}</div>
  </header>

  <div class="stage">
    <div class="art">
      {#if $cover}
        <img src={$cover} alt="" />
      {:else}
        <Icon name="music-note" size={34} />
      {/if}
    </div>
    <canvas bind:this={canvas}></canvas>
  </div>

  <div class="transport">
    <button class="skip" aria-label="Предыдущий" onclick={playPrevious}>
      <Icon name="skip-previous" size={22} />
    </button>
    <button class="play" aria-label={$player.playing ? "Пауза" : "Играть"} onclick={togglePlayer}>
      <Icon name={$player.playing ? "pause" : "play"} size={28} />
    </button>
    <button class="skip" aria-label="Следующий" onclick={playNext}>
      <Icon name="skip-next" size={22} />
    </button>
  </div>
</div>

<style>
  .visualizer {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    height: 100%;
    padding: 16px 20px 112px;
    background: radial-gradient(circle at 50% 46%, rgba(255, 255, 255, 0.08), transparent 62%);
    overflow: hidden;
  }

  header {
    text-align: center;
    max-width: 90%;
  }

  .eyebrow {
    color: var(--faint);
    font-size: 10px;
    letter-spacing: 2.6px;
    text-transform: uppercase;
  }

  h1 {
    margin: 8px 0 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 20px;
    font-weight: 700;
    letter-spacing: -0.3px;
  }

  .artist {
    margin-top: 4px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--dim);
    font-size: 12px;
  }

  .stage {
    position: relative;
    display: grid;
    place-items: center;
    width: min(100%, 620px);
    flex: 1;
    min-height: 300px;
    margin: 10px 0;
  }

  .stage canvas {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }

  .art {
    position: relative;
    z-index: 1;
    display: grid;
    place-items: center;
    width: 150px;
    height: 150px;
    overflow: hidden;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--deep);
    color: var(--faint);
    box-shadow: 0 18px 60px rgba(0, 0, 0, 0.35);
  }

  .art img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  .transport {
    display: flex;
    align-items: center;
    gap: 16px;
    padding-bottom: 4px;
  }

  .skip,
  .play {
    display: grid;
    place-items: center;
    border-radius: 999px;
    color: var(--white);
    transition: transform 140ms ease, background 160ms ease;
  }

  .skip {
    width: 62px;
    height: 62px;
    border: 1px solid var(--line);
    background: rgba(255, 255, 255, 0.04);
  }

  .play {
    width: 94px;
    height: 94px;
    background: var(--accent);
    color: #08080a;
  }

  .skip:hover,
  .play:hover {
    transform: scale(1.04);
  }
</style>
