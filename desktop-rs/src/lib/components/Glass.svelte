<script lang="ts">
  let { strong = false, padding = 24, class: className = "", children } = $props();
</script>

<div class="glass {className}" class:strong style={`padding:${padding}px`}>
  {@render children?.()}
</div>
