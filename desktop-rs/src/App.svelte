<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { onMount } from "svelte";
  import NavDock from "./lib/components/NavDock.svelte";
  import PlaceholderScreen from "./lib/screens/PlaceholderScreen.svelte";
  import PlayerScreen from "./lib/screens/PlayerScreen.svelte";
  import SettingsScreen from "./lib/screens/SettingsScreen.svelte";
  import { refreshPlayer, startPolling } from "./lib/stores/player";
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
</script>

<div class="shell" data-panel={$settings.panelPosition}>
  <NavDock
    position={$settings.panelPosition}
    active={active}
    onSelect={(key) => (active = key)}
  />

  <main class="content">
    {#if active === "player"}
      <PlayerScreen {width} />
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
    height: 100%;
    overflow-y: auto;
    overflow-x: hidden;
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
