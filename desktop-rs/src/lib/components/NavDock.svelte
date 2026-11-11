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
          <button
            class:active={active === item.key}
            aria-label={item.label}
            onclick={() => onSelect(item.key)}
          >
            <span class="item">
              <Icon name={item.icon} size={22} />
              <span class="label">{item.label}</span>
            </span>
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
          <button
            class:active={active === item.key}
            aria-label={item.label}
            onclick={() => onSelect(item.key)}
          >
            <span class="item">
              <Icon name={item.icon} size={21} />
              <span class="label">{item.label}</span>
            </span>
          </button>
        {/each}
      </div>
    </Glass>
  </div>
{/if}

<style>
  .dock-wrap {
    position: absolute;
    left: 16px;
    right: 16px;
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
    width: min(760px, 100%);
    border-radius: 30px;
  }

  .dock-inner {
    display: flex;
    align-items: center;
    gap: 0;
    height: 86px;
    padding: 10px 12px;
  }

  .dock-inner button {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    height: 66px;
    padding: 0;
    color: var(--dim);
  }

  .item {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 5px;
    width: 58px;
    height: 66px;
    border-radius: 22px;
    transition: background 160ms ease, color 160ms ease;
  }

  .dock-inner button:hover .item {
    color: var(--white);
  }

  .dock-inner button.active .item {
    background: var(--accent);
    color: #08080a;
  }

  .label {
    font-size: 9px;
    line-height: 11px;
    font-weight: 500;
    white-space: nowrap;
  }

  .dock-inner button.active .label {
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
    width: 92px;
    border-radius: 30px;
  }

  .rail-inner {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 10px 8px;
  }

  .rail-inner button {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0;
    color: var(--dim);
  }

  .rail-inner .item {
    width: 58px;
    height: 62px;
  }

  .rail-inner button:hover .item {
    color: var(--white);
  }

  .rail-inner button.active .item {
    background: var(--accent);
    color: #08080a;
  }
</style>
