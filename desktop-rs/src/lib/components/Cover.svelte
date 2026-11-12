<script lang="ts">
  import { convertFileSrc, invoke } from "@tauri-apps/api/core";
  import { onMount } from "svelte";
  import Icon from "./Icon.svelte";

  let {
    path,
    src = null,
    size = 40,
    radius = 10,
    icon = 16,
  }: {
    path?: string | null;
    src?: string | null;
    size?: number;
    radius?: number;
    icon?: number;
  } = $props();

  const cache = new Map<string, string | null>();
  let image = $state<string | null>(null);
  let element = $state<HTMLElement | null>(null);
  let visible = $state(false);

  onMount(() => {
    if (!element) return;
    if (src) {
      visible = true;
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          visible = true;
          observer.disconnect();
        }
      },
      { rootMargin: "240px" },
    );
    observer.observe(element);
    return () => observer.disconnect();
  });

  $effect(() => {
    if (src) {
      image = src;
      return;
    }
    const key = path ?? "";
    if (!key || !visible) {
      if (!key) image = null;
      return;
    }
    if (cache.has(key)) {
      image = cache.get(key) ?? null;
      return;
    }
    image = null;
    invoke<string | null>("track_cover", { path: key })
      .then((value) => {
        const url = value ? convertFileSrc(value) : null;
        cache.set(key, url);
        image = url;
      })
      .catch(() => cache.set(key, null));
  });
</script>

<div
  bind:this={element}
  class="cover"
  style={`width:${size}px;height:${size}px;border-radius:${radius}px`}
>
  {#if image}
    <img src={image} alt="" loading="lazy" />
  {:else}
    <Icon name="music-note" size={icon} />
  {/if}
</div>

<style>
  .cover {
    display: grid;
    place-items: center;
    overflow: hidden;
    background:
      radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.07), transparent 55%),
      var(--deep);
    color: var(--faint);
    flex: 0 0 auto;
  }

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
</style>
