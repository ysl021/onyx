<script lang="ts">
  import Glass from "../components/Glass.svelte";

  let { title }: { title: string } = $props();
</script>

<div class="placeholder">
  <Glass class="card" padding={18}>
    <div class="section-label">{title}</div>
    <p>Экран будет перенесён из Kotlin-версии на следующем этапе.</p>
  </Glass>
</div>

<style>
  .placeholder {
    max-width: 720px;
    margin: 0 auto;
    padding: 22px 18px 132px;
  }

  p {
    margin: 10px 0 0;
    color: var(--dim);
    font-size: 13px;
    line-height: 19px;
  }
</style>
