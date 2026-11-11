<script lang="ts">
  let { strong = false, children } = $props();
</script>

<div class="glass" class:strong>
  {@render children?.()}
</div>

<style>
  .glass {
    padding: 24px;
    border: 1px solid var(--line);
    border-radius: 28px;
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.07), rgba(255, 255, 255, 0.028));
    backdrop-filter: blur(30px);
    -webkit-backdrop-filter: blur(30px);
  }

  .strong {
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.115), rgba(255, 255, 255, 0.045));
    border-color: var(--line-strong);
  }
</style>
