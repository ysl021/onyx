<script lang="ts">
  import Glass from "./Glass.svelte";
  import Icon from "./Icon.svelte";
  import type { PanelPosition } from "../stores/settings";

  let {
    position = "bottom",
    active,
    onSelect,
  }: {
    position?: PanelPosition;
    active: string;
    onSelect: (key: string) => void;
  } = $props();

  const items = [
    { key: "player", label: "Плеер", icon: "graphic-eq" },
    { key: "lyrics", label: "Текст", icon: "translate" },
    { key: "playlists", label: "Плейлист", icon: "playlist-play" },
    { key: "music", label: "Музыка", icon: "library-music" },
    { key: "youtube", label: "YouTube", icon: "video-library" },
    { key: "visualizer", label: "Визуал", icon: "bar-chart" },
    { key: "settings", label: "Настройки", icon: "settings" },
  ];
</script>

{#if position === "bottom" || position === "top"}
  <div class="dock-wrap {position}">
    <Glass strong padding={0} class="dock">
      <div class="dock-inner">
        {#each items as item}
          <button class:active={active === item.key} onclick={() => onSelect(item.key)}>
            <Icon name={item.icon} size={18} />
            <span>{item.label}</span>
          </button>
        {/each}
      </div>
    </Glass>
  </div>
{:else}
  <div class="rail-wrap {position}">
    <Glass strong padding={0} class="rail">
      <div class="rail-inner">
        {#each items as item}
          <button class:active={active === item.key} onclick={() => onSelect(item.key)}>
            <Icon name={item.icon} size={18} />
            <span>{item.label}</span>
          </button>
        {/each}
      </div>
    </Glass>
  </div>
{/if}

<style>
  .dock-wrap {
    position: absolute;
    left: 18px;
    right: 18px;
    display: flex;
    justify-content: center;
    pointer-events: none;
    z-index: 20;
  }

  .dock-wrap.bottom {
    bottom: 12px;
  }

  .dock-wrap.top {
    top: 12px;
  }

  .dock-wrap :global(.dock) {
    pointer-events: auto;
    width: min(720px, 100%);
    border-radius: 24px;
  }

  .dock-inner {
    display: flex;
    gap: 4px;
    padding: 7px 8px;
  }

  .dock-inner button {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 3px;
    padding: 8px 2px;
    border-radius: 16px;
    color: var(--dim);
    transition: background 160ms ease, color 160ms ease;
  }

  .dock-inner button span {
    font-size: 7px;
    line-height: 9px;
    font-weight: 500;
    white-space: nowrap;
  }

  .dock-inner button.active {
    background: var(--accent);
    color: #08080a;
  }

  .dock-inner button.active span {
    font-weight: 600;
  }

  .rail-wrap {
    position: absolute;
    top: 0;
    bottom: 0;
    display: flex;
    align-items: center;
    z-index: 20;
  }

  .rail-wrap.left {
    left: 8px;
  }

  .rail-wrap.right {
    right: 8px;
  }

  .rail-wrap :global(.rail) {
    width: 96px;
    border-radius: 28px;
  }

  .rail-inner {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 8px 6px;
  }

  .rail-inner button {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 3px;
    padding: 8px 2px;
    border-radius: 16px;
    color: var(--dim);
    transition: background 160ms ease, color 160ms ease;
  }

  .rail-inner button span {
    font-size: 7px;
    line-height: 9px;
    font-weight: 500;
    white-space: nowrap;
  }

  .rail-inner button.active {
    background: var(--accent);
    color: #08080a;
  }

  .rail-inner button.active span {
    font-weight: 600;
  }
</style>
