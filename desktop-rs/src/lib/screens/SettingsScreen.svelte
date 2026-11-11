<script lang="ts">
  import { open } from "@tauri-apps/plugin-dialog";
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import { scanFolder } from "../stores/library";
  import {
    saveSettings,
    setPanelPosition,
    setTheme,
    setVolume,
    settings,
  } from "../stores/settings";
  import type { PanelPosition } from "../stores/settings";

  let { version, onBack }: { version: string; onBack: () => void } = $props();

  const panels: { key: PanelPosition; label: string }[] = [
    { key: "bottom", label: "Снизу" },
    { key: "top", label: "Сверху" },
    { key: "left", label: "Слева" },
    { key: "right", label: "Справа" },
  ];

  async function addFolder() {
    const selected = await open({ directory: true, multiple: false });
    if (typeof selected !== "string") return;
    await scanFolder(selected);
  }

  async function removeFolder(folder: string) {
    const current = $settings;
    await saveSettings({
      ...current,
      musicFolders: current.musicFolders.filter((item) => item !== folder),
    });
  }

  async function changeVolume(event: Event) {
    const value = Number((event.currentTarget as HTMLInputElement).value);
    await setVolume(value);
  }
</script>

<div class="settings">
  <header class="settings-head">
    <button class="circle" aria-label="Назад" onclick={onBack}>
      <Icon name="arrow-back" size={20} />
    </button>
    <div>
      <h1>Настройки</h1>
      <div class="version">ONYX · v{version}</div>
    </div>
  </header>

  <Glass padding={18}>
    <div class="section-label">Панель навигации</div>
    <div class="pill-row">
      {#each panels as panel}
        <button
          class="pill"
          class:selected={$settings.panelPosition === panel.key}
          onclick={() => setPanelPosition(panel.key)}
        >
          {panel.label}
        </button>
      {/each}
    </div>
    <p>Положение стеклянной панели с разделами. По умолчанию — снизу, как на телефоне.</p>
  </Glass>

  <Glass padding={18}>
    <div class="section-label">Тема</div>
    <div class="pill-row">
      <button
        class="pill"
        class:selected={$settings.theme === "dark"}
        onclick={() => setTheme("dark")}
      >
        Тёмная
      </button>
      <button
        class="pill"
        class:selected={$settings.theme === "light"}
        onclick={() => setTheme("light")}
      >
        Светлая
      </button>
    </div>
  </Glass>

  <Glass padding={18}>
    <div class="section-label">Громкость</div>
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

  <Glass padding={18}>
    <div class="section-label">Локальная музыка</div>
    {#if $settings.musicFolders.length === 0}
      <p>Папки не выбраны.</p>
    {:else}
      {#each $settings.musicFolders as folder (folder)}
        <div class="folder-row">
          <span title={folder}>{folder}</span>
          <button
            class="circle small"
            aria-label="Убрать папку"
            onclick={() => removeFolder(folder)}
          >
            <Icon name="close" size={14} />
          </button>
        </div>
      {/each}
    {/if}
    <button class="pill add-folder" onclick={addFolder}>Добавить папку</button>
  </Glass>

  <Glass padding={18}>
    <div class="section-label">О приложении</div>
    <p>ONYX Desktop · Rust + Tauri + Svelte 5</p>
    <p class="version">Версия {version}</p>
  </Glass>
</div>

<style>
  .settings {
    max-width: 720px;
    margin: 0 auto;
    padding: 22px 18px 132px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .settings-head {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 4px;
  }

  h1 {
    margin: 0;
    font-size: 24px;
    font-weight: 700;
    letter-spacing: -0.5px;
  }

  .version {
    color: var(--faint);
    font-size: 10px;
    letter-spacing: 1.4px;
  }

  .pill-row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }

  p {
    margin: 10px 0 0;
    color: var(--dim);
    font-size: 12px;
    line-height: 17px;
  }

  .volume-row {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 12px;
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

  .folder-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
    padding: 6px 8px;
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.04);
  }

  .folder-row span {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--dim);
    font-size: 12px;
  }

  .add-folder {
    margin-top: 12px;
  }

  .circle.small {
    width: 28px;
    height: 28px;
    flex: 0 0 auto;
  }

  .version {
    margin-top: 8px;
  }
</style>
