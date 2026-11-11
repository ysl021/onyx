<script lang="ts">
  import Glass from "../components/Glass.svelte";
  import Icon from "../components/Icon.svelte";
  import { setPanelPosition, settings } from "../stores/settings";
  import type { PanelPosition } from "../stores/settings";

  let { version, onBack }: { version: string; onBack: () => void } = $props();

  const panels: { key: PanelPosition; label: string }[] = [
    { key: "bottom", label: "Снизу" },
    { key: "top", label: "Сверху" },
    { key: "left", label: "Слева" },
    { key: "right", label: "Справа" },
  ];
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

  <Glass class="card" padding={18}>
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

  <Glass class="card" padding={18}>
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

  .version {
    margin-top: 8px;
  }
</style>
