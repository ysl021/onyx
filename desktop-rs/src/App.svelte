<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { onMount } from "svelte";
  import NavDock from "./lib/components/NavDock.svelte";
  import LyricsScreen from "./lib/screens/LyricsScreen.svelte";
  import MusicScreen from "./lib/screens/MusicScreen.svelte";
  import PlaceholderScreen from "./lib/screens/PlaceholderScreen.svelte";
  import PlayerScreen from "./lib/screens/PlayerScreen.svelte";
  import PlaylistsScreen from "./lib/screens/PlaylistsScreen.svelte";
  import SettingsScreen from "./lib/screens/SettingsScreen.svelte";
  import YouTubeScreen from "./lib/screens/YouTubeScreen.svelte";
  import { cover, refreshPlayer, startPolling } from "./lib/stores/player";
  import { loadSettings, settings } from "./lib/stores/settings";

  let active = $state("player");
  let version = $state("0.9.0");
  let width = $state(1200);

  const titles: Record<string, string> = {
    lyrics: "Текст",
    playlists: "Плейлист",
    music: "Музыка",
    youtube: "YouTube",
    visualizer: "Визуал",
  };

  onMount(() => {
    loadSettings();
    refreshPlayer();
    const stopPolling = startPolling();
    const onResize = () => (width = window.innerWidth);
    onResize();
    window.addEventListener("resize", onResize);
    invoke<string>("app_version")
      .then((value) => (version = value))
      .catch(() => {});
    return () => {
      stopPolling();
      window.removeEventListener("resize", onResize);
    };
  });

  $effect(() => {
    document.documentElement.dataset.theme =
      $settings.theme === "light" ? "light" : "dark";
  });
</script>

<div class="shell" data-panel={$settings.panelPosition}>
  {#if $cover}
    <div class="backdrop" style={`background-image:url(${$cover})`}></div>
    <div class="scrim"></div>
  {/if}

  <NavDock
    position={$settings.panelPosition}
    active={active}
    onSelect={(key) => (active = key)}
  />

  <main class="content">
    {#if active === "player"}
      <PlayerScreen {width} onOpenLyrics={() => (active = "lyrics")} />
    {:else if active === "lyrics"}
      <LyricsScreen />
    {:else if active === "music"}
      <MusicScreen />
    {:else if active === "playlists"}
      <PlaylistsScreen />
    {:else if active === "youtube"}
      <YouTubeScreen />
    {:else if active === "settings"}
      <SettingsScreen {version} onBack={() => (active = "player")} />
    {:else}
      <PlaceholderScreen title={titles[active] ?? active} />
    {/if}
  </main>
</div>

<style>
  .shell {
    position: relative;
    height: 100%;
    overflow: hidden;
  }

  .content {
    position: relative;
    z-index: 1;
    height: 100%;
    overflow-y: auto;
    overflow-x: hidden;
  }

  .backdrop {
    position: absolute;
    inset: -60px;
    background-size: cover;
    background-position: center;
    filter: blur(48px) saturate(120%);
    opacity: 0.42;
    transform: scale(1.1);
    pointer-events: none;
  }

  .scrim {
    position: absolute;
    inset: 0;
    background: linear-gradient(
      180deg,
      rgba(5, 5, 6, 0.5),
      rgba(5, 5, 6, 0.8) 55%,
      rgba(5, 5, 6, 0.92)
    );
    pointer-events: none;
  }

  [data-panel="top"] .content {
    padding-top: 84px;
  }

  [data-panel="left"] .content {
    padding-left: 130px;
  }

  [data-panel="right"] .content {
    padding-right: 130px;
  }
</style>
