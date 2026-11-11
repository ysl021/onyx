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
  import VisualizerScreen from "./lib/screens/VisualizerScreen.svelte";
  import YouTubeScreen from "./lib/screens/YouTubeScreen.svelte";
  import { loadFavorites, migrateLegacyFavorites } from "./lib/stores/favorites";
  import { loadTracks } from "./lib/stores/library";
  import { cover, refreshPlayer, startPolling } from "./lib/stores/player";
  import { loadSettings, settings } from "./lib/stores/settings";

  let active = $state("player");
  let version = $state("0.9.0");
  let settingsTab = $state<"main" | "look" | "integrations">("main");

  const titles: Record<string, string> = {
    lyrics: "Текст",
    playlists: "Плейлист",
    music: "Музыка",
    youtube: "YouTube",
    visualizer: "Визуал",
  };

  onMount(() => {
    loadSettings();
    loadFavorites();
    loadTracks().then(() => migrateLegacyFavorites());
    refreshPlayer();
    const stopPolling = startPolling();
    invoke<string>("app_version")
      .then((value) => (version = value))
      .catch(() => {});
    return () => {
      stopPolling();
    };
  });

  $effect(() => {
    document.documentElement.dataset.theme =
      $settings.theme === "light" ? "light" : "dark";
  });
</script>

<div class="shell" data-panel={$settings.panelPosition}>
  {#if $settings.coverBackdrop && $cover}
    <div class="backdrop" style={`background-image:url(${$cover})`}></div>
    <div class="scrim"></div>
  {/if}

  <NavDock
    position={$settings.panelPosition}
    active={active}
    onSelect={(key) => {
      if (key === "settings") settingsTab = "main";
      active = key;
    }}
  />

  <main class="content">
    {#if active === "player"}
      <PlayerScreen onOpenLyrics={() => (active = "lyrics")} />
    {:else if active === "lyrics"}
      <LyricsScreen onBack={() => (active = "player")} />
    {:else if active === "music"}
      <MusicScreen />
    {:else if active === "playlists"}
      <PlaylistsScreen onBack={() => (active = "player")} />
    {:else if active === "youtube"}
      <YouTubeScreen
        onBack={() => (active = "player")}
        onOpenSettings={() => {
          settingsTab = "integrations";
          active = "settings";
        }}
      />
    {:else if active === "visualizer"}
      <VisualizerScreen />
    {:else if active === "settings"}
      <SettingsScreen
        {version}
        initialTab={settingsTab}
        onBack={() => (active = "player")}
      />
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
    inset: -90px;
    background-size: cover;
    background-position: center;
    filter: blur(64px) saturate(130%);
    opacity: 0.5;
    transform: scale(1.18);
    pointer-events: none;
  }

  .scrim {
    position: absolute;
    inset: 0;
    background: linear-gradient(
      180deg,
      rgba(5, 5, 6, 0.5),
      rgba(5, 5, 6, 0.82) 52%,
      rgba(5, 5, 6, 0.94)
    );
    pointer-events: none;
  }

  [data-panel="top"] .content {
    padding-top: 104px;
  }

  [data-panel="left"] .content {
    padding-left: 120px;
  }

  [data-panel="right"] .content {
    padding-right: 120px;
  }
</style>
